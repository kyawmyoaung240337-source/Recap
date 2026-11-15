package com.kgrecap

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.VideoView
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

data class Segment(val index: Int, val startMs: Long, val endMs: Long)

class MainActivity : Activity() {
    private val frameMs = 50
    private val maxMs = 15_000
    private val minPauseMs = 400
    private val skipGapMs = 3_000
    private val pickCode = 1

    private lateinit var info: TextView
    private lateinit var list: ListView
    private lateinit var video: VideoView
    private var segments: List<Segment> = emptyList()
    private var prepared = false
    private var startAt = 0L
    private var stopAt = 0L
    private var loadId = 0
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val pos = video.currentPosition
            if (pos >= startAt && pos >= stopAt) {
                video.pause()
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        val btn = Button(this).apply {
            text = "Video ရွေးပါ"
            setOnClickListener { pickVideo() }
        }
        info = TextView(this).apply {
            textSize = 15f
            setPadding(0, 16, 0, 16)
            text = "Video မရွေးရသေးပါ"
        }
        video = VideoView(this)
        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos in segments.indices) playSegment(segments[pos])
        }
        root.addView(btn)
        root.addView(info)
        root.addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 700))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(i, pickCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) { }
        loadVideo(uri)
    }

    private fun loadVideo(uri: Uri) {
        handler.removeCallbacks(ticker)
        prepared = false
        segments = emptyList()
        list.adapter = null
        val id = ++loadId

        var dur = 0L
        var w: String? = null
        var h: String? = null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        } catch (e: Exception) {
            info.text = "Error: ${e.message}"
            return
        } finally {
            r.release()
        }

        video.setOnPreparedListener {
            prepared = true
            video.seekTo(1)
        }
        video.setVideoURI(uri)
        info.text = "${fmt(dur)}  |  ${w}x${h}\nအသံကို စစ်နေပါတယ်..."

        val totalMs = dur
        val head = "${fmt(dur)}  |  ${w}x${h}"
        Thread {
            val db = try { analyzeAudio(uri) } catch (e: Exception) { FloatArray(0) }
            val smart = buildSegments(db, totalMs)
            val usedSmart = smart.isNotEmpty()
            val result = if (usedSmart) smart else fixedSegments(totalMs)
            val mode = if (usedSmart) "စကားပြော pause အလိုက်" else "၁၃ စက္ကန့်စီ (အသံ မစစ်နိုင်)"
            runOnUiThread {
                if (id == loadId) showSegments(result, head, mode)
            }
        }.start()
    }

    private fun showSegments(segs: List<Segment>, head: String, mode: String) {
        segments = segs
        info.text = "$head  |  Segments: ${segs.size}\n$mode\nSegment ကိုနှိပ်ရင် preview ကြည့်လို့ရပါတယ်"
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            segs.map {
                "[%03d]  %s - %s  (%.1fs)".format(
                    it.index, fmt(it.startMs), fmt(it.endMs), (it.endMs - it.startMs) / 1000.0
                )
            }
        )
    }

    private fun fixedSegments(totalMs: Long): List<Segment> {
        val step = 13_000L
        return (0L until totalMs step step).mapIndexed { i, s ->
            Segment(i + 1, s, minOf(s + step, totalMs))
        }
    }

    // အသံကို decode လုပ်ပြီး 50ms frame တစ်ခုချင်းရဲ့ volume (dB) ကို တွက်
    private fun analyzeAudio(uri: Uri): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(this, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                fmt = f
                break
            }
        }
        if (track < 0 || fmt == null) {
            ex.release()
            return FloatArray(0)
        }
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var perFrame = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) *
            fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) * frameMs / 1000
        val out = ArrayList<Float>()
        var sum = 0.0
        var n = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi >= 0) {
                val buf = codec.getOutputBuffer(oi)!!
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (sb.hasRemaining()) {
                    val v = sb.get().toDouble()
                    sum += v * v
                    n++
                    if (n >= perFrame) {
                        val rms = sqrt(sum / n)
                        out.add((20.0 * log10(maxOf(rms, 1.0) / 32768.0)).toFloat())
                        sum = 0.0
                        n = 0
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = codec.outputFormat
                perFrame = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) *
                    nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) * frameMs / 1000
            }
        }
        codec.stop()
        codec.release()
        ex.release()
        return out.toFloatArray()
    }

    private fun buildSegments(db: FloatArray, totalMs: Long): List<Segment> {
        val n = db.size
        if (n < 20) return emptyList()
        val sorted = db.sortedArray()
        val floor = sorted[(n * 0.2).toInt()]
        val ceil = sorted[minOf(n - 1, (n * 0.9).toInt())]
        val thr = floor + (ceil - floor) * 0.25f
        val minPause = minPauseMs / frameMs
        val maxF = maxMs / frameMs
        val skipF = skipGapMs / frameMs

        // 1) စကားပြောနေတဲ့ အပိုင်းတွေ
        val regions = ArrayList<IntArray>()
        var start = -1
        var last = -1
        for (i in 0 until n) {
            if (db[i] > thr) {
                if (start < 0) start = i
                last = i
            } else if (start >= 0 && i - last >= minPause) {
                regions.add(intArrayOf(start, last + 1))
                start = -1
            }
        }
        if (start >= 0) regions.add(intArrayOf(start, last + 1))

        // 2) ၁၅ စက္ကန့်ထက် ရှည်ရင် အသံအတိတ်ဆုံးနေရာမှာ ဖြတ်
        val pieces = ArrayList<IntArray>()
        for (r in regions) {
            if (r[1] - r[0] < 3) continue
            var s = r[0]
            while (r[1] - s > maxF) {
                var cut = s + maxF
                for (k in (s + maxF - 60)..(s + maxF)) {
                    if (k > s && db[k] < db[cut]) cut = k
                }
                pieces.add(intArrayOf(s, cut))
                s = cut
            }
            pieces.add(intArrayOf(s, r[1]))
        }

        // 3) ၁၅ စက္ကန့်မကျော်အောင် ပေါင်း၊ ၃ စက္ကန့်ကျော် gap ရှိရင် ခွဲ
        val merged = ArrayList<IntArray>()
        var cur: IntArray? = null
        for (p in pieces) {
            val c = cur
            if (c == null) {
                cur = intArrayOf(p[0], p[1])
            } else if (p[0] - c[1] > skipF || p[1] - c[0] > maxF) {
                merged.add(c)
                cur = intArrayOf(p[0], p[1])
            } else {
                c[1] = p[1]
            }
        }
        cur?.let { merged.add(it) }
        if (merged.isEmpty()) return emptyList()

        // 4) gap သေးရင် segment တွေကို ဆက်ထား
        for (k in 0 until merged.size - 1) {
            if (merged[k + 1][0] - merged[k][1] <= skipF) merged[k][1] = merged[k + 1][0]
        }
        if (merged.first()[0] <= skipF) merged.first()[0] = 0
        val totalF = (totalMs / frameMs).toInt()
        if (totalF - merged.last()[1] <= skipF) merged.last()[1] = totalF

        return merged.mapIndexed { i, m ->
            Segment(i + 1, m[0].toLong() * frameMs, minOf(totalMs, m[1].toLong() * frameMs))
        }
    }

    private fun playSegment(s: Segment) {
        if (!prepared) return
        handler.removeCallbacks(ticker)
        startAt = s.startMs
        stopAt = s.endMs
        video.seekTo(s.startMs.toInt())
        video.start()
        handler.postDelayed(ticker, 300)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
