package com.kgrecap

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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

/** c = စကားပြောပိုင်း မူရင်းစက္ကန့်၊ t = အသံ စက္ကန့်၊ vs = video speed */
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

/** ခန်းစကနေ စကားမစသေးတဲ့ အပိုင်း (စက္ကန့်)။ 0.3 စက္ကန့်အောက်ဆို မခွဲဘူး */
fun preRollSec(c: Double, speechStartSec: Double): Double {
    if (speechStartSec < 0.0) return 0.0
    val pre = speechStartSec - 0.1
    if (pre < 0.3) return 0.0
    return minOf(pre, c - 0.5).coerceAtLeast(0.0)
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
    private fun f4(v: Float) = String.format(Locale.US, "%.4f", v)

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
        silentSpeed: Double,
        gain: Double,
        overlay: Overlay
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
        val ss = silentSpeed.coerceIn(1.0, 6.0)
        val total = segments.size
        val ui = RenderDialog(context as? Activity)

        val cache = File(context.cacheDir, "recap_export").apply { mkdirs() }
        cache.listFiles()?.forEach { if (it.isFile) it.delete() }
        val inputVideo = File(cache, "source.mp4")
        val listFile = File(cache, "scenes.txt")
        val outputVideo = File(cache, "rendered.mp4")
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

            val (fw, fh) = frameSize(inputVideo)
            val minDim = minOf(fw, fh)

            val scenes = ArrayList<File>()
            for (i in 0 until total) {
                if (cancelled) throw Cancelled()
                val seg = segments[i]
                val c = (seg.endMs - seg.startMs) / 1000.0
                if (c <= 0.05) continue
                val voice = voiceOf(i)
                val t = if (voice != null) durationOf(voice) / 1000.0 else 0.0

                // စကားမစခင်ပိုင်း (pre) ကို အမြန်ပြ၊ အသံက စကားစမှ စ
                val pre = if (voice != null) preRollSec(c, (seg.speechStartMs - seg.startMs) / 1000.0) else 0.0
                val body = c - pre
                val p = if (voice == null) ScenePlan(c / ss, ss, 1.0) else planScene(body, t, vs)
                val preDur = pre / ss
                val dur = preDur + p.dur

                stage("ခန်း ${i + 1}/$total render")
                ui.update(scenes.size, total, "ခန်း ${i + 1} render နေပါတယ်...")

                val scene = File(cache, "scene_${i}.mp4")
                temps.add(scene)

                // စာတန်းကို Android စာလုံးဆွဲစနစ်နဲ့ PNG ဆွဲ
                val capText = translations[i]?.trim().orEmpty()
                var capFile: File? = null
                if (overlay.caption.show && capText.isNotEmpty()) {
                    val bmp = CaptionRenderer.render(context, capText, overlay.caption, fw, fh)
                    if (bmp != null) {
                        val f = File(cache, "cap_${i}.png")
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bmp.recycle()
                        temps.add(f)
                        capFile = f
                    }
                }

                // ----- video graph -----
                val vParts = ArrayList<String>()
                if (pre > 0.0) {
                    vParts.add("[0:v]split=2[va][vb]")
                    vParts.add("[va]trim=start=0:end=${f3(pre)},setpts=(PTS-STARTPTS)/${f3(ss)}[p0]")
                    vParts.add("[vb]trim=start=${f3(pre)},setpts=(PTS-STARTPTS)/${f3(p.vSpeed)}[p1]")
                    vParts.add("[p0][p1]concat=n=2:v=1:a=0,fps=30[v0]")
                } else {
                    vParts.add("[0:v]setpts=(PTS-STARTPTS)/${f3(p.vSpeed)},fps=30[v0]")
                }
                var cur = "v0"
                var k = 0
                for (b in overlay.blurs) {
                    if (!b.on) continue
                    val x = b.x.coerceIn(0f, 0.95f)
                    val y = b.y.coerceIn(0f, 0.95f)
                    val w = b.w.coerceIn(0.05f, 1f - x)
                    val h = b.h.coerceIn(0.03f, 1f - y)
                    val sigma = (3.0 + b.density.coerceIn(0, 100) / 100.0 * 47.0) * (minDim / 720.0)
                    vParts.add("[$cur]split=2[ba$k][bb$k]")
                    vParts.add(
                        "[bb$k]crop=w=trunc(iw*${f4(w)}/2)*2:h=trunc(ih*${f4(h)}/2)*2:" +
                            "x=trunc(iw*${f4(x)}/2)*2:y=trunc(ih*${f4(y)}/2)*2,gblur=sigma=${f3(sigma)}:steps=2[bl$k]"
                    )
                    vParts.add("[ba$k][bl$k]overlay=x=trunc(W*${f4(x)}/2)*2:y=trunc(H*${f4(y)}/2)*2[vb$k]")
                    cur = "vb$k"
                    k++
                }
                if (capFile != null) {
                    vParts.add(
                        "[$cur][2:v]overlay=x='max(0,min(W-w,W*${f4(overlay.caption.cx)}-w/2))':" +
                            "y='max(0,min(H-h,H*${f4(overlay.caption.cy)}-h/2))':format=auto:shortest=1[vout]"
                    )
                    cur = "vout"
                }

                // ----- audio graph -----
                val boost = if (gain > 1.001) ",volume=${f3(gain)},alimiter=limit=0.97" else ""
                val delayMs = (preDur * 1000.0).toInt()
                val delay = if (delayMs > 0) ",adelay=delays=$delayMs:all=1" else ""
                val agraph = if (voice != null)
                    "[1:a]atempo=${f3(p.aSpeed)},aformat=sample_rates=48000:channel_layouts=mono$boost$delay,apad=whole_dur=${f3(dur)}[a]"
                else
                    "[1:a]aformat=sample_rates=48000:channel_layouts=mono[a]"

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
                if (capFile != null) {
                    args.addAll(listOf("-loop", "1", "-i", capFile.absolutePath))
                }
                args.addAll(
                    listOf(
                        "-filter_complex", (vParts + agraph).joinToString(";"),
                        "-map", "[$cur]", "-map", "[a]",
                        "-t", f3(dur),
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

    private fun runFfmpeg(st: String, args: List<String>, out: File) {
        stage(st)
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        if (cancelled) throw Cancelled()
        if (!ReturnCode.isSuccess(session.returnCode) || !out.isFile || out.length() < 1024L) {
            val details = session.output.orEmpty().takeLast(1800)
            throw IllegalStateException("[$st] " + details.ifBlank { "FFmpeg မအောင်မြင်ပါ" })
        }
    }

    /** ဗီဒီယိုပုံရဲ့ အကျယ်/အမြင့် (လှည့်ထားတာကိုပါ ထည့်တွက်) */
    private fun frameSize(f: File): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } finally {
            r.release()
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
