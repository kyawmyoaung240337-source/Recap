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
import android.widget.LinearLayout
import android.widget.ProgressBar
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

class ScenePlan(val dur: Double, val vSpeed: Double, val aSpeed: Double)

private const val MAX_AUDIO_SQUEEZE = 1.15
private const val MAX_AUDIO_HARD = 1.4
private const val MIN_VIDEO_SPEED = 0.75

/** c = မူရင်း clip စက္ကန့်၊ t = အသံ စက္ကန့်၊ vs = video speed */
fun planScene(c: Double, t: Double, vs: Double): ScenePlan {
    if (t <= 0.05) return ScenePlan(c / vs, vs, 1.0)
    val fast = c / vs
    if (t <= fast) return ScenePlan(fast, vs, 1.0)
    if (t <= c) return ScenePlan(t, c / t, 1.0)
    var a = minOf(t / c, MAX_AUDIO_SQUEEZE)
    var d = t / a
    if (c / d < MIN_VIDEO_SPEED) {
        d = c / MIN_VIDEO_SPEED
        a = minOf(t / d, MAX_AUDIO_HARD)
        d = t / a
    }
    return ScenePlan(d, c / d, a)
}

/** ခန်းတစ်ခုချင်း render → ပေါင်း → Movies/Recap. Background thread ကနေ ခေါ်ပါ။ */
object VideoExporter {
    private class Cancelled : RuntimeException("Render ရပ်လိုက်ပါပြီ")

    @Volatile private var lastStage = ""
    @Volatile private var cancelled = false

    private fun stage(s: String) {
        lastStage = s
        Breadcrumb.mark(s)
    }

    private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)

    private class RenderDialog(private val act: Activity?) {
        private var dialog: AlertDialog? = null
        private var msg: TextView? = null
        private var bar: ProgressBar? = null

        fun show(total: Int, onCancel: () -> Unit) {
            val a = act ?: return
            a.runOnUiThread {
                try {
                    val box = LinearLayout(a)
                    box.orientation = LinearLayout.VERTICAL
                    box.setPadding(48, 32, 48, 8)
                    val t = TextView(a)
                    t.textSize = 15f
                    t.text = "စတင်နေပါတယ်..."
                    val b = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
                    b.max = total
                    box.addView(t)
                    box.addView(b)
                    msg = t
                    bar = b
                    dialog = AlertDialog.Builder(a)
                        .setTitle("Render လုပ်နေပါတယ်")
                        .setView(box)
                        .setCancelable(false)
                        .setNegativeButton("ရပ်မယ်") { _, _ -> onCancel() }
                        .show()
                } catch (e: Throwable) { }
            }
        }

        fun update(done: Int, total: Int, line: String) {
            act?.runOnUiThread {
                try {
                    msg?.text = "ခန်း $done/$total ပြီးပြီ • ကျန် ${total - done}\n$line"
                    bar?.progress = done
                } catch (e: Throwable) { }
            }
        }

        fun dismiss() {
            act?.runOnUiThread {
                try { dialog?.dismiss() } catch (e: Throwable) { }
            }
        }
    }

    fun export(
        context: Context,
        source: Uri,
        segments: List<Segment>,
        translations: Array<String?>,
        ttsFiles: List<File?>,
        videoSpeed: Double,
        gain: Double
    ): Uri {
        lastStage = "စတင်နေ"
        cancelled = false
        require(segments.isNotEmpty()) { "No video segments to export." }
        require(translations.size == segments.size && ttsFiles.size == segments.size) {
            "Video data changed. Please try again."
        }
        fun voiceOf(i: Int): File? {
            val text = translations[i]
            val file = ttsFiles[i]
            return if (text.isNullOrBlank() || file == null || !file.isFile || file.length() <= 44L) null
            else file
        }
        require(segments.indices.any { voiceOf(it) != null }) { "No generated voice-over audio. Generate TTS first." }

        val vs = videoSpeed.coerceIn(1.0, 2.0)
        val total = segments.size
        val ui = RenderDialog(context as? Activity)

        val cache = File(context.cacheDir, "recap_export").apply { mkdirs() }
        cache.listFiles()?.forEach { if (it.isFile) it.delete() }
        val inputVideo = File(cache, "source.mp4")
        val listFile = File(cache, "scenes.txt")
        val outputVideo = File(cache, "rendered.mp4")
        val fontDir = File(cache, "fonts").apply { mkdirs() }
        val fontFile = File(fontDir, "NotoSansMyanmar.ttf")
        val temps = ArrayList<File>()

        try {
            ui.show(total) {
                cancelled = true
                try { FFmpegKit.cancel() } catch (e: Throwable) { }
            }

            stage("1 source video ကူးနေ")
            ui.update(0, total, "Video ကူးနေပါတယ်...")
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(inputVideo).use { output -> input.copyTo(output) }
            } ?: error("Cannot read the selected video.")

            stage("2 font ကူးနေ (assets/fonts/NotoSansMyanmar.ttf)")
            copyBundledFont(context, fontFile)
            setFontDir(context, fontDir)

            val (width, height) = videoSize(inputVideo)
            val minDim = minOf(width, height)

            val scenes = ArrayList<File>()
            for (i in 0 until total) {
                if (cancelled) throw Cancelled()
                val seg = segments[i]
                val c = (seg.endMs - seg.startMs) / 1000.0
                if (c <= 0.05) continue
                val voice = voiceOf(i)
                val t = if (voice != null) durationOf(voice) / 1000.0 else 0.0
                val p = planScene(c, t, vs)

                stage("ခန်း ${i + 1}/$total render")
                ui.update(scenes.size, total, "ခန်း ${i + 1} render နေပါတယ်...")

                val ass = File(cache, "scene_${i}.ass")
                val scene = File(cache, "scene_${i}.mp4")
                temps.add(ass)
                temps.add(scene)
                writeAssScene(ass, translations[i]?.trim().orEmpty(), p.dur, width, height)

                val args = mutableListOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-ss", f3(seg.startMs / 1000.0), "-t", f3(c),
                    "-i", inputVideo.absolutePath
                )
                if (voice != null) {
                    args.addAll(listOf("-i", voice.absolutePath))
                } else {
                    args.addAll(listOf("-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono"))
                }
                val boost = if (gain > 1.001) ",volume=${f3(gain)},alimiter=limit=0.97" else ""
                val af = if (voice != null)
                    "atempo=${f3(p.aSpeed)},aformat=sample_rates=48000:channel_layouts=mono$boost,apad=whole_dur=${f3(p.dur)}"
                else
                    "aformat=sample_rates=48000:channel_layouts=mono"
                args.addAll(
                    listOf(
                        "-map", "0:v:0", "-map", "1:a:0",
                        "-vf", "setpts=(PTS-STARTPTS)/${f3(p.vSpeed)},fps=30," + subtitleFilter(ass, fontDir, minDim),
                        "-af", af,
                        "-t", f3(p.dur),
                        "-c:v", "mpeg4", "-q:v", "4", "-pix_fmt", "yuv420p", "-threads", "2",
                        "-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "1",
                        scene.absolutePath
                    )
                )
                runFfmpeg("ခန်း ${i + 1}/$total", args, scene)
                scenes.add(scene)
                ui.update(scenes.size, total, "ခန်း ${i + 1} ပြီးပါပြီ")
            }
            require(scenes.isNotEmpty()) { "Render လုပ်စရာ ခန်းမရှိပါ။" }

            if (cancelled) throw Cancelled()
            ui.update(scenes.size, total, "ခန်းတွေကို ပေါင်းနေပါတယ်...")
            listFile.writeText(
                scenes.joinToString("\n") { "file '" + it.absolutePath.replace("'", "'\\''") + "'" }
            )
            outputVideo.delete()
            runFfmpeg(
                "ပေါင်းနေ",
                listOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
                    "-c", "copy", "-movflags", "+faststart",
                    outputVideo.absolutePath
                ),
                outputVideo
            )

            stage("gallery ထဲ save နေ")
            ui.update(scenes.size, total, "Gallery ထဲ သိမ်းနေပါတယ်...")
            val uri = saveToGallery(context, outputVideo)
            Breadcrumb.clear()
            ui.dismiss()
            return uri
        } catch (t: Throwable) {
            val st = lastStage
            Breadcrumb.clear()
            ui.dismiss()
            if (t !is Cancelled) showError(context, st, t)
            throw t
        } finally {
            inputVideo.delete()
            listFile.delete()
            outputVideo.delete()
            fontFile.delete()
            temps.forEach { it.delete() }
        }
    }

    private fun durationOf(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            r.release()
        }
    }

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
        if (cancelled) throw Cancelled()
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

    private fun writeAssScene(target: File, caption: String, durSec: Double, width: Int, height: Int) {
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
            if (caption.isNotEmpty()) {
                val escaped = caption
                    .replace("\\", "\\\\")
                    .replace("{", "\\{")
                    .replace("}", "\\}")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replace("\n", "\\N")
                appendLine("Dialogue: 0,${assTime(0)},${assTime(((durSec + 0.5) * 1000).toLong())},Default,,0,0,0,,$escaped")
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
