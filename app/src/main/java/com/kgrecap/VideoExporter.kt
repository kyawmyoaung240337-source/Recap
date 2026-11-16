package com.kgrecap

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Creates a subtitled MP4 with the app's generated Myanmar voice-over and saves it to Movies/Recap. */
object VideoExporter {
    private data class VoiceTrack(val input: File, val startMs: Long, val speed: Double)

    fun export(
        context: Context,
        source: Uri,
        segments: List<Segment>,
        translations: Array<String?>,
        ttsFiles: List<File?>,
        ttsSpeeds: DoubleArray
    ): Uri {
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
        val outputVideo = File(cache, "rendered.mp4")
        val fontDir = File(cache, "fonts").apply { mkdirs() }
        val fontFile = File(fontDir, "NotoSansMyanmar.ttf")

        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(inputVideo).use { output -> input.copyTo(output) }
            } ?: error("Cannot read the selected video.")

            copyBundledFont(context, fontFile)
            val durationMs = segments.maxOf { it.endMs }
            writeAss(subtitleFile, segments, translations, inputVideo)
            outputVideo.delete()

            val audioGraph = buildAudioGraph(voices)
            val baseArgs = mutableListOf(
                "-hide_banner", "-loglevel", "error", "-y", "-i", inputVideo.absolutePath
            )
            voices.forEach { baseArgs.addAll(listOf("-i", it.input.absolutePath)) }
            baseArgs.addAll(
                listOf(
                    "-filter_complex", audioGraph,
                    "-map", "0:v:0",
                    "-map", "[aout]",
                    "-vf", subtitleFilter(subtitleFile, fontDir, inputVideo),
                    "-t", String.format(Locale.US, "%.3f", durationMs / 1000.0),
                    "-c:a", "aac", "-b:a", "128k",
                    "-movflags", "+faststart",
                    outputVideo.absolutePath
                )
            )

            // Try device H.264 hardware encoder first; fall back to the widely supported MPEG-4 encoder.
            val hardwareArgs = baseArgs.toMutableList().apply {
                val at = indexOf("-c:a")
                addAll(at, listOf("-c:v", "h264_mediacodec", "-b:v", "4M"))
            }
            var session = FFmpegKit.executeWithArguments(hardwareArgs.toTypedArray())
            if (!ReturnCode.isSuccess(session.returnCode) || !outputVideo.isFile || outputVideo.length() < 1024L) {
                outputVideo.delete()
                val fallbackArgs = baseArgs.toMutableList().apply {
                    val at = indexOf("-c:a")
                    addAll(at, listOf("-c:v", "mpeg4", "-q:v", "4"))
                }
                session = FFmpegKit.executeWithArguments(fallbackArgs.toTypedArray())
            }
            if (!ReturnCode.isSuccess(session.returnCode) || !outputVideo.isFile || outputVideo.length() < 1024L) {
                val details = session.output.orEmpty().takeLast(1800)
                throw IllegalStateException(details.ifBlank { "Video encoding failed. Please try another video." })
            }

            return saveToGallery(context, outputVideo)
        } finally {
            inputVideo.delete()
            subtitleFile.delete()
            outputVideo.delete()
            fontFile.delete()
        }
    }

    private fun buildAudioGraph(voices: List<VoiceTrack>): String {
        val parts = ArrayList<String>()
        voices.forEachIndexed { i, track ->
            val delay = track.startMs.coerceAtLeast(0L)
            val speed = String.format(Locale.US, "%.3f", track.speed)
            parts.add(
                "[${i + 1}:a]aresample=48000,atempo=$speed,adelay=delays=$delay:all=1[a$i]"
            )
        }
        val inputs = voices.indices.joinToString(separator = "") { "[a$it]" }
        parts.add("${inputs}amix=inputs=${voices.size}:duration=longest:normalize=0:dropout_transition=0,alimiter=limit=0.95[aout]")
        return parts.joinToString(";")
    }

    private fun writeAss(
        target: File,
        segments: List<Segment>,
        translations: Array<String?>,
        videoFile: File
    ) {
        // Use the Android decoder metadata to size subtitles proportionally to the source frame.
        val retriever = android.media.MediaMetadataRetriever()
        val (width, height) = try {
            retriever.setDataSource(videoFile.absolutePath)
            val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
            val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
            w to h
        } finally {
            retriever.release()
        }
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

    private fun subtitleFilter(
        subtitle: File,
        fontDir: File,
        videoFile: File
    ): String {
        // App-private absolute paths contain no shell quoting; escape filter separators explicitly.
        fun esc(file: File): String = file.absolutePath
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
        val retriever = android.media.MediaMetadataRetriever()
        val minDim = try {
            retriever.setDataSource(videoFile.absolutePath)
            minOf(
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720,
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
            )
        } finally {
            retriever.release()
        }
        val size = (minDim * 0.052).toInt().coerceIn(28, 68)
        return "subtitles='${esc(subtitle)}':fontsdir='${esc(fontDir)}':force_style='FontName=Noto Sans Myanmar,FontSize=$size,Outline=2.5,Shadow=1,Alignment=2'"
    }

    private fun copyBundledFont(context: Context, target: File) {
        context.assets.open("fonts/NotoSansMyanmar.ttf").use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
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
