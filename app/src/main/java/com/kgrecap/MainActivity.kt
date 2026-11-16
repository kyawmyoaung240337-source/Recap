package com.kgrecap

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10

data class Segment(val index: Int, val startMs: Long, val endMs: Long)

class MainActivity : Activity() {
    private val frameMs = 50
    private val maxMs = 15_000
    private val skipGapMs = 3_000
    private val pickCode = 1

    private val langs = listOf(
        "zh" to "Chinese", "en" to "English", "th" to "Thai",
        "my" to "Myanmar", "auto" to "Auto"
    )
    private val models = listOf(
        Triple("base", "ggml-base-q5_1.bin", "57MB"),
        Triple("small", "ggml-small-q5_1.bin", "181MB")
    )
    private var langIdx = 0
    private var workers = 2
    private var modelIdx = 0
    private var toEnglish = true

    private lateinit var info: TextView
    private lateinit var list: ListView
    private lateinit var video: VideoView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var btnLang: Button
    private lateinit var btnWorkers: Button
    private lateinit var btnModel: Button
    private lateinit var btnOut: Button
    private lateinit var btnRun: Button
    private lateinit var btnCopy: Button
    private lateinit var btnPaste: Button

    private val labels = ArrayList<String>()
    private var player: MediaPlayer? = null
    private var segments: List<Segment> = emptyList()
    private var texts: Array<String?> = arrayOfNulls<String>(0)
    private var trans: Array<String?> = arrayOfNulls<String>(0)
    private var pcmFile: File? = null
    private var prepared = false
    private var startAt = 0L
    private var stopAt = 0L
    @Volatile private var loadId = 0
    @Volatile private var running = false
    @Volatile private var stopReq = false
    private var downloading = false
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

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 12f
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg v: Button): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        v.forEach {
            l.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return l
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 48, 24, 24)

        val btnPick = button("Video ရွေးပါ") { pickVideo() }
        btnLang = button("") {
            langIdx = (langIdx + 1) % langs.size
            updateButtons()
        }
        btnWorkers = button("") {
            if (!running) {
                workers = workers % 3 + 1
                updateButtons()
            }
        }
        btnModel = button("") {
            if (!running && !downloading) {
                modelIdx = (modelIdx + 1) % models.size
                updateButtons()
            }
        }
        btnOut = button("") {
            if (!running) {
                toEnglish = !toEnglish
                if (texts.any { it != null }) {
                    clearTexts()
                    Toast.makeText(
                        this, "Output ပြောင်းလို့ စာတွေ ရှင်းပြီး၊ Transcribe ပြန်လုပ်ပါ",
                        Toast.LENGTH_LONG
                    ).show()
                }
                updateButtons()
            }
        }
        btnRun = button("") { onRun() }
        btnCopy = button("Copy All (+AI)") { copyAll() }
        btnPaste = button("Paste All") { pasteAll() }

        info = TextView(this)
        info.textSize = 14f
        info.setPadding(0, 12, 0, 12)
        info.text = "Video မရွေးရသေးပါ"

        video = VideoView(this)
        list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos in segments.indices) playSegment(segments[pos])
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in segments.indices) editSegment(pos)
            true
        }

        root.addView(btnPick)
        root.addView(info)
        root.addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 450))
        root.addView(row(btnLang, btnWorkers, btnModel))
        root.addView(row(btnOut, btnRun))
        root.addView(row(btnCopy, btnPaste))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        updateButtons()
    }

    private fun updateButtons() {
        btnLang.text = "Lang: ${langs[langIdx].second}"
        btnWorkers.text = "Workers: $workers"
        btnModel.text = "Model: ${models[modelIdx].first}"
        btnOut.text = if (toEnglish) "Output: English" else "Output: Original"
        updateRunButton()
    }

    private fun modelFile() = File(filesDir, models[modelIdx].second)

    private fun updateRunButton() {
        btnRun.text = when {
            downloading -> "Downloading..."
            running && stopReq -> "ရပ်နေပါတယ်..."
            running -> "ရပ်မယ် (Stop)"
            !modelFile().exists() -> "Model download (${models[modelIdx].third})"
            else -> "Transcribe စတင်မယ်"
        }
    }

    private fun onRun() {
        if (downloading) return
        if (running) {
            stopReq = true
            updateRunButton()
            return
        }
        if (!modelFile().exists()) {
            downloadModel()
            return
        }
        startTranscribe()
    }

    private fun downloadModel() {
        downloading = true
        updateRunButton()
        val name = models[modelIdx].second
        val target = modelFile()
        Thread {
            var err: String? = null
            try {
                val url = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$name")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                c.connect()
                if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                val part = File(filesDir, "$name.part")
                c.inputStream.use { ins ->
                    FileOutputStream(part).use { os ->
                        val buf = ByteArray(65536)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 500) {
                                last = now
                                val pct = if (total > 0) " ${done * 100 / total}%" else ""
                                val mb = done / 1_000_000
                                runOnUiThread { btnRun.text = "Downloading...$pct (${mb}MB)" }
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) throw RuntimeException("rename failed")
            } catch (e: Exception) {
                err = e.message ?: e.toString()
            }
            runOnUiThread {
                downloading = false
                updateRunButton()
                if (err != null) {
                    Toast.makeText(this@MainActivity, "Download error: $err", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startTranscribe() {
        val pcm = pcmFile
        if (pcm == null || segments.isEmpty()) {
            Toast.makeText(this, "Video ရွေးပြီး အသံစစ်တာ ပြီးမှ လုပ်ပါ", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        stopReq = false
        updateRunButton()
        val id = loadId
        val segs = segments
        val tx = texts
        val lang = langs[langIdx].first
        val translate = toEnglish
        val nWorkers = workers
        val threads = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() / nWorkers))
        val modelPath = modelFile().absolutePath
        val next = AtomicInteger(0)
        val finished = AtomicInteger(0)

        for (k in 0 until nWorkers) {
            Thread {
                var ptr = 0L
                try {
                    ptr = WhisperBridge.nativeInit(modelPath)
                    if (ptr == 0L) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Model load မရပါ", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        while (!stopReq && id == loadId) {
                            val i = next.getAndIncrement()
                            if (i >= segs.size) break
                            if (tx[i] != null) continue
                            val audio = readPcm(pcm, segs[i])
                            val text = if (audio.size < 1600) ""
                            else WhisperBridge.nativeTranscribe(ptr, audio, lang, threads, translate).trim()
                            runOnUiThread {
                                if (id == loadId) {
                                    tx[i] = text
                                    refreshLabel(i)
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    if (ptr != 0L) WhisperBridge.nativeFree(ptr)
                    if (finished.incrementAndGet() == nWorkers) {
                        runOnUiThread {
                            running = false
                            stopReq = false
                            updateRunButton()
                        }
                    }
                }
            }.start()
        }
    }

    private fun readPcm(f: File, s: Segment): FloatArray {
        RandomAccessFile(f, "r").use { raf ->
            val total = raf.length() / 2
            val a = minOf(total, s.startMs * 16)
            val b = minOf(total, s.endMs * 16)
            val n = (b - a).toInt()
            if (n <= 0) return FloatArray(0)
            val bytes = ByteArray(n * 2)
            raf.seek(a * 2)
            raf.readFully(bytes)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = sb.get(i) / 32768f
            return out
        }
    }

    private fun label(i: Int): String {
        val s = segments[i]
        val t = texts[i]
        val tr = trans[i]
        var r = "[%03d]  %s - %s  (%.1fs)".format(
            s.index, fmt(s.startMs), fmt(s.endMs), (s.endMs - s.startMs) / 1000.0
        )
        if (t != null) r += "\n" + (if (t.isBlank()) "(စကားမပါ)" else t)
        if (!tr.isNullOrBlank()) r += "\n➜ " + tr
        return r
    }

    private fun refreshLabel(i: Int) {
        if (i < labels.size) {
            labels[i] = label(i)
            adapter.notifyDataSetChanged()
        }
    }

    private fun refreshAll() {
        for (i in labels.indices) labels[i] = label(i)
        adapter.notifyDataSetChanged()
    }

    private fun clearTexts() {
        for (i in texts.indices) {
            texts[i] = null
            trans[i] = null
        }
        refreshAll()
    }

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private val promptEnglish = """[INSTRUCTION FOR AI TRANSLATOR]
Translate each numbered dialogue part below from English into natural spoken Myanmar (မြန်မာ).
These lines come from a Chinese drama. The English was produced by speech recognition and machine translation, so it may contain mistakes. Use the neighbouring parts to work out the real meaning.
Write the lines as natural character dialogue, not as a narrator.

Rules:
1. Keep every part separate. Never merge, split or move lines between parts.
2. Keep each [Part N • start - end] header line exactly as given.
3. Under each header write only the translated spoken line, in Myanmar Unicode script. Write names and foreign words in Myanmar script too.
4. No markdown, no speaker names, no notes, no explanations, no greetings.
5. Separate the parts with this exact line: ----------------------------------------
6. Return only the translated parts."""

    private val promptOriginal = """[INSTRUCTION FOR AI TRANSLATOR]
Translate each numbered dialogue part below into natural spoken Myanmar (မြန်မာ).
These are lines from a movie scene. Write them as natural character dialogue, not as a narrator. The transcript comes from speech recognition and may contain mistakes, so use the neighbouring parts to work out the real meaning.

Rules:
1. Keep every part separate. Never merge, split or move lines between parts.
2. Keep each [Part N • start - end] header line exactly as given.
3. Under each header write only the translated spoken line, in Myanmar Unicode script. Write names and foreign words in Myanmar script too.
4. No markdown, no speaker names, no notes, no explanations, no greetings.
5. Separate the parts with this exact line: ----------------------------------------
6. Return only the translated parts."""

    private fun copyAll() {
        val parts = ArrayList<String>()
        segments.forEachIndexed { i, s ->
            val t = texts[i]
            if (!t.isNullOrBlank()) {
                parts.add("[Part ${s.index} • ${mmss(s.startMs)} - ${mmss(s.endMs)}]\n$t")
            }
        }
        if (parts.isEmpty()) {
            Toast.makeText(this, "Copy ကူးစရာ စာမရှိသေးပါ", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = if (toEnglish) promptEnglish else promptOriginal
        val text = prompt + "\n\n[INPUT TRANSCRIPT PARTS TO TRANSLATE]\n" +
            parts.joinToString("\n----------------------------------------\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kgrecap", text))
        Toast.makeText(this, "Copied ${parts.size} parts", Toast.LENGTH_SHORT).show()
    }

    private val partRe = Regex("""\[Part\s*(\d+)[^\]]*]""")

    private fun pasteAll() {
        if (segments.isEmpty()) {
            Toast.makeText(this, "Video ရွေးပါ", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val raw = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(this).toString() else ""
        val ms = partRe.findAll(raw).toList()
        if (ms.isEmpty()) {
            Toast.makeText(this, "[Part 1 • ...] ပုံစံ မတွေ့ပါ။ Gemini ရလဒ်ကို Copy အရင်လုပ်ပါ", Toast.LENGTH_LONG).show()
            return
        }
        var ok = 0
        ms.forEachIndexed { k, m ->
            val no = m.groupValues[1].toInt()
            val end = if (k + 1 < ms.size) ms[k + 1].range.first else raw.length
            val body = raw.substring(m.range.last + 1, end)
                .replace(Regex("-{5,}"), "")
                .trim()
                .trim('*', ' ', '\n')
            val idx = segments.indexOfFirst { it.index == no }
            if (idx >= 0 && body.isNotEmpty()) {
                trans[idx] = body
                ok++
            }
        }
        refreshAll()
        val missing = segments.indices
            .filter { !texts[it].isNullOrBlank() && trans[it].isNullOrBlank() }
            .map { segments[it].index }
        var msg = "Paste ပြီး: $ok parts"
        if (missing.isNotEmpty()) msg += "\nမပါသေးတာ: " + missing.joinToString(", ")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun editSegment(i: Int) {
        val s = segments[i]
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(40, 20, 40, 0)
        val l1 = TextView(this)
        l1.text = "မူရင်းစာ (Whisper)"
        val e1 = EditText(this)
        e1.setText(texts[i] ?: "")
        val l2 = TextView(this)
        l2.text = "မြန်မာ ဘာသာပြန်"
        l2.setPadding(0, 24, 0, 0)
        val e2 = EditText(this)
        e2.setText(trans[i] ?: "")
        box.addView(l1)
        box.addView(e1)
        box.addView(l2)
        box.addView(e2)
        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this)
            .setTitle("Part ${s.index}  ${fmt(s.startMs)}")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                texts[i] = e1.text.toString().trim()
                val t = e2.text.toString().trim()
                trans[i] = if (t.isEmpty()) null else t
                refreshLabel(i)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        if (running) stopReq = true
        prepared = false
        player = null
        segments = emptyList()
        texts = arrayOfNulls<String>(0)
        trans = arrayOfNulls<String>(0)
        pcmFile = null
        labels.clear()
        adapter.notifyDataSetChanged()
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("audio_")) it.delete() }
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
        val pcm = File(cacheDir, "audio_$id.pcm")
        Thread {
            var db = FloatArray(0)
            try {
                db = decodeAudio(uri, pcm)
            } catch (e: Exception) { }
            val smart = buildSegments(db, totalMs)
            val usedSmart = smart.isNotEmpty()
            val result = if (usedSmart) smart else fixedSegments(totalMs)
            val mode = if (usedSmart) "စကားပြော pause အလိုက်" else "၁၃ စက္ကန့်စီ (အသံ မစစ်နိုင်)"
            val hasPcm = db.isNotEmpty()
            runOnUiThread {
                if (id == loadId) {
                    pcmFile = if (hasPcm) pcm else null
                    showSegments(result, head, mode)
                }
            }
        }.start()
    }

    private fun showSegments(segs: List<Segment>, head: String, mode: String) {
        segments = segs
        texts = arrayOfNulls<String>(segs.size)
        trans = arrayOfNulls<String>(segs.size)
        info.text = "$head  |  Segments: ${segs.size}\n$mode\n(စာကို ကြာကြာနှိပ်ရင် ပြင်လို့ရပါတယ်)"
        labels.clear()
        for (i in segs.indices) labels.add(label(i))
        adapter.notifyDataSetChanged()
    }

    private fun fixedSegments(totalMs: Long): List<Segment> {
        val step = 13_000L
        return (0L until totalMs step step).mapIndexed { i, s ->
            Segment(i + 1, s, minOf(s + step, totalMs))
        }
    }

    private fun decodeAudio(uri: Uri, outFile: File): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(this, uri, null)
        var track = -1
        var af: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                af = f
                break
            }
        }
        if (track < 0 || af == null) {
            ex.release()
            return FloatArray(0)
        }
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(af.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(af, null, null, 0)
        codec.start()

        var ch = af.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var ratio = af.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 16000.0
        var phase = 0.0
        var accSum = 0.0
        var accCnt = 0
        var chSum = 0
        var chIdx = 0
        var frameSum = 0.0
        var frameN = 0
        val db = ArrayList<Float>()
        val os = FileOutputStream(outFile)
        val obuf = ByteArray(1 shl 16)
        var opos = 0

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
                    chSum += sb.get().toInt()
                    chIdx++
                    if (chIdx >= ch) {
                        val m = chSum / ch
                        chSum = 0
                        chIdx = 0
                        accSum += m
                        accCnt++
                        phase += 1.0
                        if (phase >= ratio) {
                            phase -= ratio
                            val v = (accSum / accCnt).toInt().coerceIn(-32768, 32767)
                            accSum = 0.0
                            accCnt = 0
                            obuf[opos++] = v.toByte()
                            obuf[opos++] = (v shr 8).toByte()
                            if (opos >= obuf.size) {
                                os.write(obuf, 0, opos)
                                opos = 0
                            }
                            val f = v / 32768.0
                            frameSum += f * f
                            frameN++
                            if (frameN >= 800) {
                                db.add((10.0 * log10(maxOf(frameSum / frameN, 1e-10))).toFloat())
                                frameSum = 0.0
                                frameN = 0
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if ((bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = codec.outputFormat
                ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                ratio = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 16000.0
            }
        }
        if (opos > 0) os.write(obuf, 0, opos)
        os.close()
        codec.stop()
        codec.release()
        ex.release()
        return db.toFloatArray()
    }

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
        stopReq = true
        super.onDestroy()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
