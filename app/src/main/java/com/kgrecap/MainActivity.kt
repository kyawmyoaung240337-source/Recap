package com.kgrecap

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
    private val skipGapMs = 3_000
    private val pickCode = 1

    private lateinit var info: TextView
    private lateinit var list: ListView
    private lateinit var video: VideoView
    private var player: MediaPlayer? = null
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
                handler.postDelayed(this, 50)
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
        info.text = "Whisper: " + try { WhisperBridge.systemInfo().take(80) } catch (e: Throwable) { "load error: " + e.message }
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
        player = null
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

        video.setOnPreparedListener { mp ->
            player = mp
            prepared = true
            mp.seekTo(1L, MediaPlayer.SEEK_CLOSEST)
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
        val bi = MediaCodec.BufferInfo()
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
            val oi = codec.dequeueOutputBuffer(bi, 10_000)
            if (oi >= 0) {
                val buf = codec.getOutputBuffer(oi)!!
                buf.position(bi.offset)
                buf.limit(bi.offset + bi.size)
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
                if ((bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
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

    // pause ရှည်ရာမှာ ဖြတ်: ၁၅s ထဲက "နောက်ဆုံး pause ရှည်" ကိုရွေး၊ မရှိရင် pause တို၊ မရှိရင် အသံအတိတ်ဆုံး
    private fun buildSegments(db: FloatArray, totalMs: Long): List<Segment> {
        val n = db.size
        if (n < 20) return emptyList()
        val sorted = db.sortedArray()
        val floor = sorted[(n * 0.2).toInt()]
        val ceil = sorted[minOf(n - 1, (n * 0.9).toInt())]
        val thr = floor + (ceil - floor) * 0.25f
        val maxF = maxMs / frameMs
        val minGap = 350 / frameMs
        val strongGap = 700 / frameMs
        val skipF = skipGapMs / frameMs
        val minLen = 40

        val gaps = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (db[i] <= thr) {
                var j = i
                while (j < n && db[j] <= thr) j++
                if (j - i >= minGap) gaps.add(intArrayOf(i, j))
                i = j
            } else {
                i++
            }
        }

        var s = 0
        while (s < n && db[s] <= thr) s++
        if (s >= n) return emptyList()
        s = if (s <= skipF) 0 else maxOf(0, s - 4)

        val cuts = ArrayList<IntArray>()
        while (s < n) {
            if (n - s <= maxF) {
                cuts.add(intArrayOf(s, n))
                break
            }
            val limit = s + maxF
            var best: IntArray? = null
            for (g in gaps) {
                if (g[0] <= s + minLen || g[0] >= limit) continue
                if (g[1] - g[0] >= strongGap) best = g
            }
            if (best == null) {
                for (g in gaps) {
                    if (g[0] <= s + minLen || g[0] >= limit) continue
                    best = g
                }
            }
            val end: Int
            val next: Int
            if (best != null) {
                val len = best[1] - best[0]
                if (len > skipF) {
                    end = best[0] + 4
                    next = best[1] - 4
                } else {
                    end = best[0] + len / 2
                    next = end
                }
            } else {
                var c = limit
                for (k in (limit - 60)..limit) {
                    if (k > s + minLen && db[k] < db[c]) c = k
                }
                end = c
                next = c
            }
            cuts.add(intArrayOf(s, end))
            s = next
        }

        return cuts.mapIndexed { idx, c ->
            val last = idx == cuts.lastIndex
            Segment(
                idx + 1,
                c[0].toLong() * frameMs,
                if (last) totalMs else minOf(totalMs, c[1].toLong() * frameMs)
            )
        }
    }

    private fun playSegment(s: Segment) {
        val mp = player ?: return
        if (!prepared) return
        handler.removeCallbacks(ticker)
        startAt = s.startMs
        stopAt = s.endMs
        video.pause()
        mp.setOnSeekCompleteListener {
            mp.setOnSeekCompleteListener(null)
            video.start()
            handler.postDelayed(ticker, 100)
        }
        mp.seekTo(s.startMs, MediaPlayer.SEEK_CLOSEST)
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
