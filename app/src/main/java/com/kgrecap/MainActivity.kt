package com.kgrecap

import android.app.Activity
import android.content.Intent
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

data class Segment(val index: Int, val startMs: Long, val endMs: Long)

class MainActivity : Activity() {
    private val segmentMs = 13_000L
    private val pickCode = 1
    private lateinit var info: TextView
    private lateinit var list: ListView
    private lateinit var video: VideoView
    private var segments: List<Segment> = emptyList()
    private var prepared = false
    private var startAt = 0L
    private var stopAt = 0L
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
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            segments = (0L until dur step segmentMs).mapIndexed { i, s ->
                Segment(i + 1, s, minOf(s + segmentMs, dur))
            }
            info.text = "${fmt(dur)}  |  ${w}x${h}  |  Segments: ${segments.size}\nSegment ကိုနှိပ်ရင် preview ကြည့်လို့ရပါတယ်"
            list.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                segments.map { "[%03d]  %s - %s".format(it.index, fmt(it.startMs), fmt(it.endMs)) }
            )
            video.setOnPreparedListener {
                prepared = true
                video.seekTo(1)
            }
            video.setVideoURI(uri)
        } catch (e: Exception) {
            info.text = "Error: ${e.message}"
        } finally {
            r.release()
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
