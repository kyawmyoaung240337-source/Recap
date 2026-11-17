package com.kgrecap

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.ScrollView
import android.widget.TextView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Subtitled MP4 + Myanmar voice-over → Movies/Recap. Background thread ကနေ ခေါ်ပါ။ */
object VideoExporter {
    private data class VoiceTrack(val input: File, val startMs: Long, val speed: Double)

    @Volatile private var lastStage = ""

    private fun stage(s: String) {
        lastStage = s
        Breadcrumb.mark(s)
    }

    fun export(
        context: Context,
        source: Uri,
        segments: List<Segment>,
        translations: Array<String?>,
        ttsFiles: List<File?>,
        ttsSpeeds: DoubleArray
    ): Uri {
        lastStage = "စတင်နေ"
        require(segments.isNotEmpty()) { "No video segments to export." }
        require(translations.size == segments.size && ttsFiles.size == segments.size && ttsSpeeds.size == segments.size) {
            "Video data changed. Please try again."
        }

        val voices = segments.indices.mapNotNull { i ->
            val text = translations[i]
            val file = ttsFiles[i]
            if (text.isNullOrBlank() || file == null || !file.isFile || file.length() <= 44L) null
            else VoiceTrack(file, segments[i].startMs, ttsSpeeds[i].coerceIn(1.0, 2.0))
        }
        require(voices.isNotEmpty()) { "No generated voice-over audio. Generate TTS first." }

        val cache = File(context.cacheDir, "recap_export").apply { mkdirs() }
        val inputVideo = File(cache, "source.mp4")
        val subtitleFile = File(cache, "captions.ass")
        val videoPart = File(cache, "video_sub.mp4")
        val audioPart = File(cache, "audio.m4a")
        val outputVideo = File(cache, "rendered.mp4")
        val fontDir = File(cache, "fonts").apply { mkdirs() }
        val fontFile = File(fontDir, "NotoSansMyanmar.ttf")

        try {
            stage("1/7 source video ကူးနေ")
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(inputVideo).use { output -> input.copyTo(output) }
            } ?: error("Cannot read the selected video.")

            stage("2/7 font ကူးနေ (assets/fonts/NotoSansMyanmar.ttf)")
            copyBundledFont(context, fontFile)
            setFontDir(context, fontDir)

            stage("3/7 caption ဖိုင်ရေးနေ")
            val durationMs = segments.maxOf { it.endMs }
            val (width, height) = videoSize(inputVideo)
            writeAss(subtitleFile, segments, translations, width, height)
            val tSec = String.format(Locale.US, "%.3f", durationMs / 1000.0)

            // ၁) video + subtitle (အသံမပါ)
            videoPart.delete()
            runFfmpeg(
                "4/7 FFmpeg: video + subtitle",
                listOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-i", inputVideo.absolutePath,
                    "-an",
                    "-vf", subtitleFilter(subtitleFile, fontDir, minOf(width, height)),
                    "-t", tSec,
                    "-c:v", "mpeg4", "-q:v", "4", "-threads", "2",
                    videoPart.absolutePath
                ),
                videoPart
            )

            // ၂) မြန်မာအသံတွေ ပေါင်း
            audioPart.delete()
            val audioArgs = mutableListOf("-hide_banner", "-loglevel", "error", "-y")
            voices.forEach { audioArgs.addAll(listOf("-i", it.input.absolutePath)) }
            audioArgs.addAll(
                listOf(
                    "-filter_complex", buildAudioGraph(voices),
                    "-map", "[aout]",
                    "-t", tSec,
                    "-c:a", "aac", "-b:a", "128k",
                    audioPart.absolutePath
                )
            )
            runFfmpeg("5/7 FFmpeg: audio mix", audioArgs, audioPart)

            // ၃) video + audio ပေါင်း (re-encode မလုပ်)
            outputVideo.delete()
            runFfmpeg(
                "6/7 FFmpeg: mux",
                listOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-i", videoPart.absolutePath,
                    "-i", audioPart.absolutePath,
                    "-map", "0:v:0", "-map", "1:a:0",
                    "-c", "copy",
                    "-movflags", "+faststart",
                    outputVideo.absolutePath
                ),
                outputVideo
            )

            stage("7/7 gallery ထဲ save နေ")
            val uri = saveToGallery(context, outputVideo)
            Breadcrumb.clear()
            return uri
        } catch (t: Throwable) {
            val st = lastStage
            Breadcrumb.clear()
            showError(context, st, t)
            throw t
        } finally {
            inputVideo.delete()
            subtitleFile.delete()
            videoPart.delete()
            audioPart.delete()
            outputVideo.delete()
            fontFile.delete()
        }
    }

    // Error အပြည့်ကို dialog နဲ့ ပြ (Toast က စာတိုတိုပဲ ပြလို့)
    private fun showError(context: Context, st: String, t: Throwable) {
        val a = context as? Activity ?: return
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val full = "အဆင့်: $st\n\n$sw"
        val short = full.lines().filter {
            it.startsWith("အဆင့်") || it.contains("Exception") || it.contains("Error") ||
                it.contains("Caused by") || it.contains("kgrecap") || it.contains("ffmpegkit") ||
                it.contains("dlopen") || it.contains("symbol") || it.contains(".so")
        }.take(40).joinToString("\n")
        a.runOnUiThread {
            try {
                val tv = TextView(a)
                tv.text = short
                tv.textSize = 11f
                tv.setTextIsSelectable(true)
                tv.setPadding(32, 16, 32, 16)
                val sv = ScrollView(a)
                sv.addView(tv)
                AlertDialog.Builder(a)
                    .setTitle("Export error")
                    .setView(sv)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("error", full.take(12000)))
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Throwable) { }
        }
    }

    // FFmpegKitConfig.setFontDirectory ကို reflection နဲ့ ခေါ် (library fork မှာ မရှိလည်း build မပျက်အောင်)
    private fun setFontDir(context: Context, dir: File) {
        try {
            val c = Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig")
            val m = c.getMethod(
                "setFontDirectory",
                Context::class.java, String::class.java, Map::class.java
            )
            m.invoke(null, context, dir.absolutePath, null)
        } catch (e: Throwable) { }
    }

    private fun runFfmpeg(st: String, args: List<String>, out: File) {
        stage(st)
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode) || !out.isFile || out.length() < 1024L) {
            val details = session.output.orEmpty().takeLast(1800)
            throw IllegalStateException("[$st] " + details.ifBlank { "FFmpeg မအောင်မြင်ပါ" })
        }
    }

    private fun videoSize(f: File): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
            w to h
        } finally {
            r.release()
        }
    }

    private fun buildAudioGraph(voices: List<VoiceTrack>): String {
        val parts = ArrayList<String>()
        voices.forEachIndexed { i, track ->
            val delay = track.startMs.coerceAtLeast(0L)
            val speed = String.format(Locale.US, "%.3f", track.speed)
            parts.add("[$i:a]aresample=48000,atempo=$speed,adelay=delays=$delay:all=1[a$i]")
        }
        val inputs = voices.indices.joinToString(separator = "") { "[a$it]" }
        parts.add("${inputs}amix=inputs=${voices.size}:duration=longest:normalize=0:dropout_transition=0,alimiter=limit=0.95[aout]")
        return parts.joinToString(";")
    }

    private fun writeAss(
        target: File,
        segments: List<Segment>,
        translations: Array<String?>,
        width: Int,
        height: Int
    ) {
        val fontSize = (minOf(width, height) * 0.052).toInt().coerceIn(28, 68)
        val marginV = (height * 0.045).toInt().coerceAtLeast(24)
        val content = buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("WrapStyle: 0")
            appendLine("ScaledBorderAndShadow: yes")
            appendLine("PlayResX: $width")
            appendLine("PlayResY: $height")
            appendLine()
            appendLine("[V4+ Styles]")
            appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
            appendLine("Style: Default,Noto Sans Myanmar,$fontSize,&H00FFFFFF,&H000000FF,&HCC000000,&H90000000,0,0,0,0,100,100,0,0,1,2.5,1,2,${(width * 0.06).toInt()},${(width * 0.06).toInt()},$marginV,1")
            appendLine()
            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            segments.forEachIndexed { i, segment ->
                val caption = translations[i]?.trim().orEmpty()
                if (caption.isNotEmpty() && segment.endMs > segment.startMs) {
                    val escaped = caption
                        .replace("\\", "\\\\")
                        .replace("{", "\\{")
                        .replace("}", "\\}")
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .replace("\n", "\\N")
                    appendLine("Dialogue: 0,${assTime(segment.startMs)},${assTime(segment.endMs)},Default,,0,0,0,,$escaped")
                }
            }
        }
        target.writeText(content, Charsets.UTF_8)
    }

    private fun assTime(ms: Long): String {
        val totalCs = (ms.coerceAtLeast(0L) + 5L) / 10L
        val cs = totalCs % 100
        val totalSec = totalCs / 100
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hour = totalSec / 3600
        return String.format(Locale.US, "%d:%02d:%02d.%02d", hour, min, sec, cs)
    }

    private fun subtitleFilter(subtitle: File, fontDir: File, minDim: Int): String {
        fun esc(file: File): String = file.absolutePath
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
        val size = (minDim * 0.052).toInt().coerceIn(28, 68)
        return "subtitles='${esc(subtitle)}':fontsdir='${esc(fontDir)}':force_style='FontName=Noto Sans Myanmar,FontSize=$size,Outline=2.5,Shadow=1,Alignment=2'"
    }

    private fun copyBundledFont(context: Context, target: File) {
        val input = try {
            context.assets.open("fonts/NotoSansMyanmar.ttf")
        } catch (e: Exception) {
            throw IllegalStateException("assets/fonts/NotoSansMyanmar.ttf မရှိပါ (app/src/main/assets/fonts/ ထဲ ထည့်ပါ)")
        }
        input.use { i -> FileOutputStream(target).use { o -> i.copyTo(o) } }
    }

    private fun saveToGallery(context: Context, source: File): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Recap_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Recap")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("Could not create a video in Movies/Recap.")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not save the rendered video.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, ready, null, null)
            }
            return uri
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }
}
