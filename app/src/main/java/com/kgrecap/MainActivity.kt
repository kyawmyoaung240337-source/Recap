package com.kgrecap

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10

data class Segment(val index: Int, val startMs: Long, val endMs: Long)

class MainActivity : Activity() {
    private val frameMs = 50
    private val maxMs = 15_000
    private val skipGapMs = 3_000
    private val pickCode = 1
    private val exportPermissionCode = 4
    private val videoHeightRatio = 0.36
    private val transcribeWorkers = 3

    private val videoOpts = doubleArrayOf(1.1, 1.3, 1.5)
    private val voiceOpts = doubleArrayOf(1.0, 1.1, 1.2, 1.3)
    private val gainOpts = doubleArrayOf(1.0, 1.5, 2.0, 3.0, 4.0)
    private val pauseOpts = doubleArrayOf(0.6, 1.0, 1.5)
    private val pauseNames = arrayOf("နည်း", "ပုံမှန်", "များ")
    private val sylPerSec = 4.5
    private var videoSpeed = 1.3
    private var voiceSpeed = 1.2
    private var voiceGain = 2.0
    private var pauseIdx = 1
    private var voiceIdx = 0
    private var customVoice = ""

    private lateinit var list: ListView
    private lateinit var video: VideoView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var btnKey: Button
    private lateinit var keyStatus: TextView
    private lateinit var btnRun: Button
    private lateinit var btnCopy: Button
    private lateinit var btnPaste: Button
    private lateinit var btnTts: Button
    private lateinit var btnVoice: Button
    private lateinit var btnVoiceId: Button
    private lateinit var btnPause: Button
    private lateinit var btnSample: Button
    private lateinit var btnGain: Button
    private lateinit var btnExport: Button
    private lateinit var sec0: Button
    private lateinit var sec1: Button
    private lateinit var sec2: Button
    private lateinit var panel0: LinearLayout
    private lateinit var panel1: LinearLayout
    private lateinit var panel2: LinearLayout
    private val videoSpBtns = ArrayList<Button>()
    private val voiceSpBtns = ArrayList<Button>()
    private var openPanel = -1

    private val labels = ArrayList<String>()
    private var player: MediaPlayer? = null
    private var segments: List<Segment> = emptyList()
    private var texts: Array<String?> = arrayOfNulls<String>(0)
    private var trans: Array<String?> = arrayOfNulls<String>(0)
    private var ttsMs: LongArray = LongArray(0)
    private var pcmFile: File? = null
    private var prepared = false
    private var analyzing = false
    private var startAt = 0L
    private var stopAt = 0L
    @Volatile private var loadId = 0
    @Volatile private var running = false
    @Volatile private var stopReq = false
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var ttsRunning = false
    @Volatile private var ttsStop = false
    private var sampling = false
    private var ttsPlayer: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var sourceVideoUri: Uri? = null
    @Volatile private var exporting = false

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

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.visibility = View.GONE
        return l
    }

    private fun speedRow(): LinearLayout {
        val bv = button("") { cycleVideoSpeed() }
        val ba = button("") { cycleVoiceSpeed() }
        videoSpBtns.add(bv)
        voiceSpBtns.add(ba)
        return row(bv, ba)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val dm = resources.displayMetrics
        val videoH = (dm.heightPixels * videoHeightRatio).toInt()
        voiceGain = getSharedPreferences("kgrecap", MODE_PRIVATE).getFloat("gain", 2.0f).toDouble()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        video = VideoView(this)
        val box = FrameLayout(this)
        box.setBackgroundColor(Color.BLACK)
        box.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        // 🔑 ခလုတ်: ဗီဒီယိုရဲ့ ညာအပေါ်ထောင့်
        btnKey = Button(this)
        btnKey.text = "🔑"
        btnKey.textSize = 16f
        btnKey.isAllCaps = false
        btnKey.minWidth = 0
        btnKey.minimumWidth = 0
        btnKey.setPadding(28, 8, 28, 8)
        btnKey.setOnClickListener {
            Keys.showDialog(this) {
                updateKeyButton()
                updateProgress()
            }
        }
        val klp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        klp.setMargins(0, 16, 16, 0)
        box.addView(btnKey, klp)
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, videoH))

        val btnPick = button("Video ရွေးပါ") { pickVideo() }

        // ----- စာထုတ် panel -----
        keyStatus = TextView(this)
        keyStatus.textSize = 12f
        keyStatus.setPadding(8, 8, 8, 8)
        btnRun = button("") { onRun() }
        panel0 = vbox()
        panel0.addView(keyStatus)
        panel0.addView(btnRun)

        // ----- ဘာသာပြန် panel -----
        btnCopy = button("Copy All (+AI)") { copyAll() }
        btnPaste = button("") { pasteAll() }
        val hint = TextView(this)
        hint.textSize = 11f
        hint.text = "Speed ကို Copy All မလုပ်ခင် ရွေးပါ။ ပြောင်းရင် Copy All ပြန်လုပ်ပါ။"
        panel1 = vbox()
        panel1.addView(speedRow())
        panel1.addView(hint)
        panel1.addView(row(btnCopy, btnPaste))

        // ----- အသံ panel -----
        btnTts = button("") { onTts() }
        btnExport = button("Render / Save MP4") { onExport() }
        btnVoice = button("") { cycleVoice() }
        btnVoiceId = button("Voice ID ထည့်") { askCustomVoice() }
        btnPause = button("") { cyclePause() }
        btnSample = button("▶ နမူနာ") { playSample() }
        btnGain = button("") { cycleGain() }
        panel2 = vbox()
        panel2.addView(row(btnVoice, btnVoiceId))
        panel2.addView(row(btnPause, btnSample))
        panel2.addView(speedRow())
        panel2.addView(row(btnGain))
        panel2.addView(btnTts)
        panel2.addView(btnExport)

        sec0 = button("") { togglePanel(0) }
        sec1 = button("") { togglePanel(1) }
        sec2 = button("") { togglePanel(2) }

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(16, 12, 16, 8)
        header.layoutParams = AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        header.addView(btnPick)
        header.addView(row(sec0, sec1, sec2))
        header.addView(panel0)
        header.addView(panel1)
        header.addView(panel2)

        list = ListView(this)
        list.addHeaderView(header, null, false)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val p = pos - list.headerViewsCount
            if (p in segments.indices) playSegment(segments[p])
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val p = pos - list.headerViewsCount
            if (p in segments.indices) editSegment(p)
            true
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        updateButtons()
        applyPanels()
    }

    // ---------------- Panel ----------------

    private fun togglePanel(k: Int) {
        openPanel = if (openPanel == k) -1 else k
        applyPanels()
    }

    private fun applyPanels() {
        panel0.visibility = if (openPanel == 0) View.VISIBLE else View.GONE
        panel1.visibility = if (openPanel == 1) View.VISIBLE else View.GONE
        panel2.visibility = if (openPanel == 2) View.VISIBLE else View.GONE
        updateSections()
    }

    private fun updateSections() {
        val n = texts.size
        val sp = nSpeech()
        val tr = nTransAll()
        sec0.text = (if (openPanel == 0) "▾ " else "") + "စာထုတ်" + (if (n > 0) " ${nDone()}/$n" else "")
        sec1.text = (if (openPanel == 1) "▾ " else "") + "ဘာသာပြန်" + (if (sp > 0) " ${nTrans()}/$sp" else "")
        sec2.text = (if (openPanel == 2) "▾ " else "") + "အသံ" + (if (tr > 0) " ${nTts()}/$tr" else "")
    }

    // ---------------- Speed / Voice / Volume ----------------

    private fun cycleVideoSpeed() {
        val i = videoOpts.indexOfFirst { Math.abs(it - videoSpeed) < 0.001 }
        videoSpeed = videoOpts[(i + 1) % videoOpts.size]
        updateSpeedButtons()
        refreshAll()
    }

    private fun cycleVoiceSpeed() {
        if (ttsRunning) {
            Toast.makeText(this, "TTS ရပ်ပြီးမှ ပြောင်းပါ", Toast.LENGTH_SHORT).show()
            return
        }
        val i = voiceOpts.indexOfFirst { Math.abs(it - voiceSpeed) < 0.001 }
        voiceSpeed = voiceOpts[(i + 1) % voiceOpts.size]
        updateSpeedButtons()
        invalidateTts()
    }

    private fun cycleGain() {
        val i = gainOpts.indexOfFirst { Math.abs(it - voiceGain) < 0.001 }
        voiceGain = gainOpts[(i + 1) % gainOpts.size]
        getSharedPreferences("kgrecap", MODE_PRIVATE).edit().putFloat("gain", voiceGain.toFloat()).apply()
        updateVoiceButtons()
        Toast.makeText(this, "Volume ပြောင်းရင် TTS ပြန်ထုတ်စရာ မလိုပါ။ ▶ နမူနာနဲ့ Render မှာ ကျပါမယ်", Toast.LENGTH_SHORT).show()
    }

    private fun cyclePause() {
        if (ttsRunning) {
            Toast.makeText(this, "TTS ရပ်ပြီးမှ ပြောင်းပါ", Toast.LENGTH_SHORT).show()
            return
        }
        pauseIdx = (pauseIdx + 1) % pauseOpts.size
        updateVoiceButtons()
        invalidateTts()
    }

    private fun cycleVoice() {
        if (ttsRunning) {
            Toast.makeText(this, "TTS ရပ်ပြီးမှ ပြောင်းပါ", Toast.LENGTH_SHORT).show()
            return
        }
        val total = EdgeTts.presets.size + (if (customVoice.isNotBlank()) 1 else 0)
        voiceIdx = (voiceIdx + 1) % total
        updateVoiceButtons()
        invalidateTts()
    }

    private fun askCustomVoice() {
        if (ttsRunning) return
        val e = EditText(this)
        e.setText(customVoice)
        e.hint = "ဥပမာ my-MM-ThihaNeural"
        e.setPadding(48, 32, 48, 16)
        AlertDialog.Builder(this)
            .setTitle("Edge voice ID")
            .setView(e)
            .setPositiveButton("Save") { _, _ ->
                customVoice = e.text.toString().trim()
                voiceIdx = if (customVoice.isNotEmpty()) EdgeTts.presets.size else 0
                updateVoiceButtons()
                invalidateTts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun voiceId(): String =
        if (voiceIdx < EdgeTts.presets.size) EdgeTts.presets[voiceIdx].id else customVoice

    private fun voicePitch(): Int =
        if (voiceIdx < EdgeTts.presets.size) EdgeTts.presets[voiceIdx].pitchHz else 0

    private fun voiceRatePct(): Int = Math.round((voiceSpeed - 1.0) * 100).toInt()

    private fun updateVoiceButtons() {
        btnVoice.text = "Voice: " +
            (if (voiceIdx < EdgeTts.presets.size) EdgeTts.presets[voiceIdx].label else "Custom")
        btnPause.text = "နားချိန်: " + pauseNames[pauseIdx]
        btnGain.text = String.format(Locale.US, "Volume: %.1fx", voiceGain)
    }

    private fun invalidateTts() {
        val any = ttsMs.any { it > 0L }
        for (i in ttsMs.indices) ttsMs[i] = 0L
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("tts_")) it.delete() }
        refreshAll()
        if (any) {
            Toast.makeText(this, "အသံ setting ပြောင်းလို့ TTS ပြန်ထုတ်ရပါမယ်", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSpeedButtons() {
        videoSpBtns.forEach { it.text = String.format(Locale.US, "Video speed: %.1fx", videoSpeed) }
        voiceSpBtns.forEach { it.text = String.format(Locale.US, "Voice speed: %.1fx", voiceSpeed) }
    }

    // ---------------- Progress ----------------

    private fun nDone() = texts.count { it != null }
    private fun nSpeech() = texts.count { !it.isNullOrBlank() }
    private fun nTrans() = texts.indices.count { !texts[it].isNullOrBlank() && !trans[it].isNullOrBlank() }
    private fun nTransAll() = trans.count { !it.isNullOrBlank() }
    private fun nTts() = ttsMs.count { it > 0L }

    private fun updateButtons() {
        updateSpeedButtons()
        updateVoiceButtons()
        updateKeyButton()
        updateProgress()
    }

    private fun updateKeyButton() {
        if (!::btnKey.isInitialized || !::keyStatus.isInitialized) return
        val key = Keys.deepgram(this)
        val has = key.isNotBlank()
        btnKey.setBackgroundColor(if (has) 0xCC2E7D32.toInt() else 0xCCC62828.toInt())
        btnKey.setTextColor(Color.WHITE)
        keyStatus.text = if (has) "Deepgram key: ✓ ${Keys.mask(key)}"
        else "Deepgram key မရှိသေးပါ — ညာအပေါ်ထောင့်က 🔑 ကိုနှိပ်ပါ"
    }

    private fun updateProgress() {
        updateRunButton()
        updatePasteButton()
        updateTtsButton()
        updateExportButton()
        updateSections()
    }

    private fun updateExportButton() {
        if (!::btnExport.isInitialized) return
        val ready = segments.isNotEmpty() && trans.indices.all { i ->
            texts[i] != null && (texts[i].isNullOrBlank() ||
                (!trans[i].isNullOrBlank() && ttsMs[i] > 0L && ttsFile(i).isFile))
        }
        btnExport.text = when {
            exporting -> "Video render လုပ်နေပါတယ်..."
            ready -> "Render / Save MP4 ✓"
            else -> "Render / Save MP4\nTranslation + TTS ပြီးမှ"
        }
        btnExport.isEnabled = !exporting
    }

    private fun updateRunButton() {
        if (!::btnRun.isInitialized) return
        val n = texts.size
        val d = nDone()
        btnRun.text = when {
            analyzing -> "အသံစစ်နေပါတယ်..."
            running && stopReq -> "ရပ်နေပါတယ်... $d/$n"
            running -> "စာထုတ်နေ $d/$n (ကျန် ${n - d}) • ရပ်မယ်"
            Keys.deepgram(this).isBlank() -> "Transcribe (🔑 key ထည့်ပါ)"
            n == 0 -> "Transcribe (Video ရွေးပါ)"
            d == 0 -> "Transcribe စတင်မယ်"
            d < n -> "ဆက်လုပ်မယ် $d/$n (ကျန် ${n - d})"
            else -> "စာထုတ်ပြီး $d/$n ✓"
        }
    }

    private fun updatePasteButton() {
        val t = nTrans()
        val sp = nSpeech()
        btnPaste.text = if (t == 0) "Paste All"
        else "Paste All\nဘာသာပြန် $t/$sp" + (if (t >= sp) " ✓" else "")
    }

    private fun updateTtsButton() {
        val t = nTts()
        val tr = nTransAll()
        btnTts.text = when {
            ttsRunning && ttsStop -> "ရပ်နေပါတယ်..."
            ttsRunning -> "အသံထုတ်နေ $t/$tr • ရပ်မယ်"
            t == 0 -> "TTS ထုတ်မယ်"
            else -> "TTS ထုတ်မယ်\nအသံ $t/$tr" + (if (t >= tr) " ✓" else "")
        }
    }

    // ---------------- Transcribe (Deepgram) ----------------

    private fun onRun() {
        if (analyzing) return
        if (running) {
            stopReq = true
            updateRunButton()
            return
        }
        val key = Keys.deepgram(this)
        if (key.isBlank()) {
            Toast.makeText(this, "Deepgram API key ထည့်ပါ (ညာအပေါ်ထောင့်က 🔑)", Toast.LENGTH_LONG).show()
            Keys.showDialog(this) {
                updateKeyButton()
                updateProgress()
            }
            return
        }
        startTranscribe(key)
    }

    private fun startTranscribe(key: String) {
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
        val n = transcribeWorkers
        val next = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val errRef = AtomicReference<DeepgramException?>(null)

        for (k in 0 until n) {
            Thread {
                while (!stopReq && id == loadId && errRef.get() == null) {
                    val i = next.getAndIncrement()
                    if (i >= segs.size) break
                    if (tx[i] != null) continue
                    try {
                        val bytes = readPcmBytes(pcm, segs[i])
                        val text = if (bytes.size < 3200) ""
                        else Deepgram.transcribe(key, Deepgram.wav(bytes))
                        runOnUiThread {
                            if (id == loadId) {
                                tx[i] = text
                                refreshLabel(i)
                            }
                        }
                    } catch (e: DeepgramException) {
                        errRef.compareAndSet(null, e)
                    } catch (e: Throwable) {
                        errRef.compareAndSet(null, DeepgramException(-3, e.message ?: e.toString()))
                    }
                }
                if (finished.incrementAndGet() == n) {
                    runOnUiThread {
                        running = false
                        stopReq = false
                        updateRunButton()
                        val e = errRef.get()
                        if (e != null) {
                            Toast.makeText(this@MainActivity, "Transcribe error: ${e.message}", Toast.LENGTH_LONG).show()
                            if (e.code == 401 || e.code == 403) {
                                Keys.showDialog(this@MainActivity) {
                                    updateKeyButton()
                                    updateProgress()
                                }
                            }
                        }
                    }
                }
            }.start()
        }
    }

    private fun readPcmBytes(f: File, s: Segment): ByteArray {
        RandomAccessFile(f, "r").use { raf ->
            val total = raf.length() / 2
            val a = minOf(total, s.startMs * 16)
            val b = minOf(total, s.endMs * 16)
            val n = (b - a).toInt()
            if (n <= 0) return ByteArray(0)
            val bytes = ByteArray(n * 2)
            raf.seek(a * 2)
            raf.readFully(bytes)
            return bytes
        }
    }

    // ---------------- စာရင်း label / plan ----------------

    private fun clipSec(i: Int) = (segments[i].endMs - segments[i].startMs) / 1000.0

    // Gemini ကို ပေးမယ့် အချိန်ကန့်သတ်ချက် (video speed အရ ပြောခွင့်ရတဲ့ အချိန်)
    private fun slotSec(i: Int) = clipSec(i) / videoSpeed

    private fun planOf(i: Int): ScenePlan = planScene(clipSec(i), ttsMs[i] / 1000.0, videoSpeed)

    private fun plan(i: Int): String {
        val c = clipSec(i)
        val t = ttsMs[i] / 1000.0
        val p = planOf(i)
        val flag = if (p.vSpeed < 0.749) "  ⚠ စာရှည်နေသေး (တိုပါ)" else ""
        return String.format(
            Locale.US, "🔊 %.1fs | video %.1fs→%.1fs (%.2fx) | အသံ %.2fx%s",
            t, c, p.dur, p.vSpeed, p.aSpeed, flag
        )
    }

    private fun label(i: Int): String {
        val s = segments[i]
        val t = texts[i]
        val tr = trans[i]
        var r = "[%03d]  %s - %s  (%.1fs)".format(
            s.index, fmt(s.startMs), fmt(s.endMs), clipSec(i)
        )
        if (t != null) r += "\n" + (if (t.isBlank()) "(စကားမပါ)" else t)
        if (!tr.isNullOrBlank()) r += "\n➜ " + tr
        if (i < ttsMs.size && ttsMs[i] > 0L) r += "\n" + plan(i)
        return r
    }

    private fun refreshLabel(i: Int) {
        if (i < labels.size) {
            labels[i] = label(i)
            adapter.notifyDataSetChanged()
        }
        updateProgress()
    }

    private fun refreshAll() {
        for (i in labels.indices) labels[i] = label(i)
        adapter.notifyDataSetChanged()
        updateProgress()
    }

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    // ---------------- Copy / Paste ----------------

    private fun buildPrompt(): String {
        return """[INSTRUCTION FOR AI TRANSLATOR]
Translate each numbered dialogue part below from the original spoken language (speech recognition output, so it may contain mistakes) into natural spoken Myanmar (မြန်မာ).
These lines come from a drama scene. Write them as natural character dialogue, not as a narrator. Use the neighbouring parts to work out the real meaning when the text looks wrong.
The lines will be read aloud by a text-to-speech voice-over, so timing and clean text matter.

Rules:
1. Keep every part separate. Never merge, split or move lines between parts.
2. Keep each header line exactly as given, including the times and the limit, for example [Part 3 • 00:20 - 00:25 • 3.8s • max 21 syllables].
3. Under each header write only the translated spoken line, in Myanmar Unicode script. Write names and foreign words in Myanmar script too.
4. No markdown, no speaker names, no notes, no explanations, no greetings.
5. Separate the parts with this exact line: ----------------------------------------
6. Return only the translated parts.

Timing rules (very important):
7. The number of seconds in each header is the time the spoken line may take. The syllable limit is given too.
8. The Myanmar line must be speakable within that time. Stay at or under the syllable limit.
9. If the full translation is too long, shorten it. Keep the same meaning. Drop filler words and repeated phrases, but never change the facts, names or the emotion.
10. A line shorter than the limit is fine.

Text-to-speech rules (everything you write will be spoken aloud):
11. Plain words only. No parentheses, brackets, ellipsis (...), emoji, symbols or stage directions.
12. Use the Myanmar punctuation marks ၊ and ။ properly. The voice-over pauses at them. Put ၊ at every natural short pause inside a sentence (about every 6 to 12 syllables) and end every sentence with ။. Never write a long run-on line without them.
13. Do not add words such as ရှင်, ရှင့်, ခင်ဗျာ, ဗျာ, ကွယ်, နော် (gender or politeness endings). Write neutral plain spoken sentences.
14. Do not add any word that is not part of the original meaning.

[ဇာတ်ကောင်စကားပြော စည်းကမ်းများ — မဖြစ်မနေ လိုက်နာရန်]
၁။ သဘာဝကျကျ စကားပြောဟန် ဖြစ်ရမည်: မှတ်တမ်းရုပ်ရှင် ဇာတ်ကြောင်းပြော (Narrator) သို့မဟုတ် အကျဉ်းချုပ်ပြောသလိုမျိုး မဟုတ်ဘဲ၊ ဇာတ်ကောင်တွေ အမှန်တကယ် ပြောဆိုနေကြသကဲ့သို့ သဘာဝကျပြီး ဒရမ်မာဆန်သော စကားပြောဟန် (Dramatic Character Dialogue) ဖြစ်ရပါမယ်။
၂။ အချိတ်အဆက် မိရမည်: အပိုင်းတစ်ပိုင်းနဲ့ တစ်ပိုင်း (Turns) အကြား ခံစားချက်၊ စကားအပြန်အလှန် အချိတ်အဆက်နဲ့ စီးဆင်းမှု မပြတ်တောက်ဘဲ သဘာဝကျရပါမယ်။
၃။ လုံးဝ ရောနှောခြင်း မပြုရ: မြန်မာစာ သက်သက်သာ ဖြစ်ရမည်။ အင်္ဂလိပ်စာလုံး လုံးဝ မပါရပါ။ အင်္ဂလိပ် စကားလုံးများကို မြန်မာအသံထွက်ဖြင့် မြန်မာစာလုံးပေါင်းဖြင့်သာ ရေးသားရပါမယ်။
၄။ သန့်စင်သော ဇာတ်ညွှန်း စာသား ဖြစ်ရမည် (Clean Output):
- Markdown ခေါင်းစဉ်များ (##)၊ စာသားအထူ (**)၊ စာသားစောင်း (*) လုံးဝ မပါရပါ။
- Man:၊ Woman: ကဲ့သို့သော ပြောသူအမည် တဂ်များ မပါရပါ။
- အမူအရာပြ ကွင်းစကွင်းပိတ်များ ((angrily) ကဲ့သို့သော စာသားများ) မပါရပါ။
- နှုတ်ခွန်းဆက်စကား သို့မဟုတ် အပိုစကားများ လုံးဝ မပါရပါ။"""
    }

    private fun copyAll() {
        val parts = ArrayList<String>()
        segments.forEachIndexed { i, s ->
            val t = texts[i]
            if (!t.isNullOrBlank()) {
                val e = slotSec(i)
                val maxSyl = maxOf(3, Math.round(e * sylPerSec * voiceSpeed).toInt())
                val head = String.format(
                    Locale.US, "[Part %d • %s - %s • %.1fs • max %d syllables]",
                    s.index, mmss(s.startMs), mmss(s.endMs), e, maxSyl
                )
                parts.add("$head\n$t")
            }
        }
        if (parts.isEmpty()) {
            Toast.makeText(this, "Copy ကူးစရာ စာမရှိသေးပါ", Toast.LENGTH_SHORT).show()
            return
        }
        val text = buildPrompt() + "\n\n[INPUT TRANSCRIPT PARTS TO TRANSLATE]\n" +
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
            Toast.makeText(
                this, "[Part 1 • ...] ပုံစံ မတွေ့ပါ။ Gemini ရလဒ်ကို Copy အရင်လုပ်ပါ",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        ms.forEachIndexed { k, m ->
            val no = m.groupValues[1].toInt()
            val end = if (k + 1 < ms.size) ms[k + 1].range.first else raw.length
            val body = raw.substring(m.range.last + 1, end)
                .replace(Regex("-{5,}"), "")
                .trim()
                .trim('*', ' ', '\n')
            val idx = segments.indexOfFirst { it.index == no }
            if (idx >= 0 && body.isNotEmpty()) {
                if (body != trans[idx]) ttsMs[idx] = 0L
                trans[idx] = body
            }
        }
        refreshAll()
        val missing = segments.indices
            .filter { !texts[it].isNullOrBlank() && trans[it].isNullOrBlank() }
            .map { segments[it].index }
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "မပါသေးတာ: " + missing.joinToString(", "), Toast.LENGTH_LONG).show()
        }
    }

    private fun editSegment(i: Int) {
        val s = segments[i]
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(40, 20, 40, 0)
        val l1 = TextView(this)
        l1.text = "မူရင်းစာ (Deepgram)"
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
                val newTr = if (t.isEmpty()) null else t
                if (newTr != trans[i]) ttsMs[i] = 0L
                trans[i] = newTr
                refreshLabel(i)
            }
            .setNeutralButton("▶ အသံ") { _, _ -> playTts(i) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- TTS (Edge) ----------------

    private fun ttsFile(i: Int) = File(cacheDir, "tts_${segments[i].index}.wav")

    private fun releasePlayer() {
        try { enhancer?.release() } catch (e: Throwable) { }
        enhancer = null
        try { ttsPlayer?.release() } catch (e: Throwable) { }
        ttsPlayer = null
    }

    private fun playFile(f: File, speed: Double) {
        if (!f.exists()) return
        releasePlayer()
        val p = MediaPlayer()
        try {
            p.setDataSource(f.absolutePath)
            p.prepare()
            // Volume ကို မြှင့် (MediaPlayer.setVolume က 1.0 ထက် မကျော်နိုင်လို့)
            if (voiceGain > 1.001) {
                try {
                    val e = LoudnessEnhancer(p.audioSessionId)
                    e.setTargetGain((2000.0 * log10(voiceGain)).toInt())
                    e.enabled = true
                    enhancer = e
                } catch (x: Throwable) { }
            }
            p.setOnCompletionListener { releasePlayer() }
            if (speed > 1.001) {
                try {
                    p.playbackParams = PlaybackParams().setSpeed(speed.toFloat()).setPitch(1.0f)
                } catch (e: Exception) { }
            }
            if (!p.isPlaying) p.start()
            ttsPlayer = p
        } catch (e: Exception) {
            p.release()
        }
    }

    private fun playTts(i: Int) {
        if (i >= ttsMs.size || ttsMs[i] <= 0L) {
            Toast.makeText(this, "အသံ မထုတ်ရသေးပါ", Toast.LENGTH_SHORT).show()
            return
        }
        playFile(ttsFile(i), planOf(i).aSpeed)
    }

    private fun playSample() {
        if (sampling) return
        val voice = voiceId()
        if (voice.isBlank()) {
            Toast.makeText(this, "Voice ID မရှိပါ", Toast.LENGTH_SHORT).show()
            return
        }
        sampling = true
        btnSample.text = "ထုတ်နေ..."
        val pitch = voicePitch()
        val rate = voiceRatePct()
        val ps = pauseOpts[pauseIdx]
        Thread {
            var err: String? = null
            val f = File(cacheDir, "sample.wav")
            try {
                EdgeTts.speak(
                    "မင်္ဂလာပါ၊ ဒီအသံကို ကြားရပါသလား။ ဇာတ်လမ်းထဲက စကားပြောကို၊ အခုလို ဖတ်ပေးပါမယ်။",
                    voice, pitch, rate, ps, f, cacheDir
                )
            } catch (e: Exception) {
                err = e.message ?: e.toString()
            }
            runOnUiThread {
                sampling = false
                btnSample.text = "▶ နမူနာ"
                if (err != null) {
                    Toast.makeText(this@MainActivity, "TTS error: $err", Toast.LENGTH_LONG).show()
                } else {
                    playFile(f, 1.0)
                }
            }
        }.start()
    }

    private fun onTts() {
        if (ttsRunning) {
            ttsStop = true
            updateTtsButton()
            return
        }
        if (segments.isEmpty()) return
        val voice = voiceId()
        if (voice.isBlank()) {
            Toast.makeText(this, "Voice ID မရှိပါ", Toast.LENGTH_SHORT).show()
            return
        }
        val all = segments.indices.filter { !trans[it].isNullOrBlank() }
        if (all.isEmpty()) {
            Toast.makeText(this, "မြန်မာ ဘာသာပြန် မရှိသေးပါ (Paste All အရင်လုပ်ပါ)", Toast.LENGTH_LONG).show()
            return
        }
        val todo = all.filter { ttsMs[it] <= 0L }
        if (todo.isEmpty()) {
            Toast.makeText(
                this, "အသံ အကုန်ပြီးပြီ။ ပြန်ထုတ်ချင်ရင် Voice/Speed/နားချိန် ပြောင်းပါ",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        ttsRunning = true
        ttsStop = false
        updateTtsButton()
        val id = loadId
        val tr = trans
        val pitch = voicePitch()
        val rate = voiceRatePct()
        val ps = pauseOpts[pauseIdx]
        val next = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val errRef = AtomicReference<String?>(null)
        val nThreads = 3

        for (k in 0 until nThreads) {
            Thread {
                while (!ttsStop && id == loadId && errRef.get() == null) {
                    val j = next.getAndIncrement()
                    if (j >= todo.size) break
                    val i = todo[j]
                    val text = cleanForTts(tr[i] ?: "")
                    if (text.isEmpty()) continue
                    try {
                        val f = ttsFile(i)
                        EdgeTts.speak(text, voice, pitch, rate, ps, f, cacheDir)
                        val d = durationOf(f)
                        if (d <= 0L) throw RuntimeException("အသံဖိုင် မမှန်ပါ")
                        runOnUiThread {
                            if (id == loadId) {
                                ttsMs[i] = d
                                refreshLabel(i)
                            }
                        }
                    } catch (e: Exception) {
                        errRef.compareAndSet(null, e.message ?: e.toString())
                    }
                }
                if (finished.incrementAndGet() == nThreads) {
                    runOnUiThread {
                        ttsRunning = false
                        ttsStop = false
                        updateTtsButton()
                        val e = errRef.get()
                        if (e != null) {
                            Toast.makeText(this@MainActivity, "TTS error: $e", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.start()
        }
    }

    // ---------------- Render ----------------

    private fun onExport() {
        if (exporting) return
        val source = sourceVideoUri
        if (source == null || segments.isEmpty()) {
            Toast.makeText(this, "Video အရင်ရွေးပါ", Toast.LENGTH_SHORT).show()
            return
        }
        if (texts.any { it == null }) {
            Toast.makeText(this, "စာထုတ်ခြင်း (Transcribe) အရင် အကုန်ပြီးအောင်လုပ်ပါ", Toast.LENGTH_LONG).show()
            return
        }
        val missing = segments.indices.filter { i ->
            !texts[i].isNullOrBlank() &&
                (trans[i].isNullOrBlank() || ttsMs[i] <= 0L || !ttsFile(i).isFile)
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(
                this,
                "Translation နဲ့ TTS မပြီးသေးတဲ့အပိုင်း: ${missing.map { segments[it].index }.joinToString(", ")}",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (trans.none { !it.isNullOrBlank() }) {
            Toast.makeText(this, "မြန်မာဘာသာပြန်မရှိပါ", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), exportPermissionCode
            )
            return
        }

        val segSnapshot = segments.toList()
        val textSnapshot = trans.copyOf()
        val fileSnapshot = segments.indices.map { i ->
            if (textSnapshot[i].isNullOrBlank()) null else ttsFile(i)
        }
        val vsSnapshot = videoSpeed
        val gainSnapshot = voiceGain
        exporting = true
        updateProgress()
        Thread {
            try {
                val savedUri = VideoExporter.export(
                    this@MainActivity, source, segSnapshot, textSnapshot, fileSnapshot,
                    vsSnapshot, gainSnapshot
                )
                runOnUiThread {
                    exporting = false
                    updateProgress()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Export ပြီးပါပြီ")
                        .setMessage("Video ကို Movies/Recap ထဲမှာ သိမ်းပြီးပါပြီ။")
                        .setPositiveButton("ဖွင့်ကြည့်မယ်") { _, _ -> openExport(savedUri) }
                        .setNeutralButton("မျှဝေမယ်") { _, _ -> shareExport(savedUri) }
                        .setNegativeButton("ပြီးပြီ", null)
                        .show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    exporting = false
                    updateProgress()
                    val message = e.message?.takeLast(900) ?: "မသိသော export error"
                    Toast.makeText(this@MainActivity, "Export မအောင်မြင်ပါ: $message", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openExport(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Gallery ထဲက Movies/Recap folder မှာကြည့်ပါ", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareExport(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Video မျှဝေပါ"))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == exportPermissionCode) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) onExport()
            else Toast.makeText(this, "Video ကို Gallery ထဲသိမ်းဖို့ storage permission လိုပါတယ်", Toast.LENGTH_LONG).show()
        }
    }

    // ကွင်းစာ၊ ..., symbol တွေကို ဖယ် (၊ ။ ကိုတော့ ထားမယ်)
    private fun cleanForTts(t: String): String {
        var s = t.replace(Regex("[(（\\[【][^)）\\]】]*[)）\\]】]"), " ")
        s = s.replace("...", "။ ").replace("…", "။ ")
        s = s.replace(Regex("[*#_~`<>]"), " ")
        return s.replace(Regex("[ \\t]+"), " ").trim()
    }

    private fun durationOf(f: File): Long {
        val mr = MediaMetadataRetriever()
        return try {
            mr.setDataSource(f.absolutePath)
            mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            mr.release()
        }
    }

    // ---------------- Video ----------------

    private fun pickVideo() {
        if (exporting) {
            Toast.makeText(this, "Render ပြီးမှ video အသစ်ရွေးပါ", Toast.LENGTH_SHORT).show()
            return
        }
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
        sourceVideoUri = uri
        handler.removeCallbacks(ticker)
        if (running) stopReq = true
        if (ttsRunning) ttsStop = true
        prepared = false
        player = null
        segments = emptyList()
        texts = arrayOfNulls<String>(0)
        trans = arrayOfNulls<String>(0)
        ttsMs = LongArray(0)
        pcmFile = null
        labels.clear()
        adapter.notifyDataSetChanged()
        cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("audio_") || it.name.startsWith("tts_")) it.delete()
        }
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
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            updateProgress()
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
        analyzing = true
        updateProgress()

        val totalMs = dur
        val head = "${fmt(dur)} | ${w}x${h}"
        val pcm = File(cacheDir, "audio_$id.pcm")
        Thread {
            var db = FloatArray(0)
            try {
                db = decodeAudio(uri, pcm)
            } catch (e: Exception) { }
            val smart = buildSegments(db, totalMs)
            val usedSmart = smart.isNotEmpty()
            val result = if (usedSmart) smart else fixedSegments(totalMs)
            val mode = if (usedSmart) "pause အလိုက်" else "၁၃ စက္ကန့်စီ"
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
        analyzing = false
        segments = segs
        texts = arrayOfNulls<String>(segs.size)
        trans = arrayOfNulls<String>(segs.size)
        ttsMs = LongArray(segs.size)
        labels.clear()
        for (i in segs.indices) labels.add(label(i))
        adapter.notifyDataSetChanged()
        openPanel = 0
        applyPanels()
        updateProgress()
        Toast.makeText(
            this, "$head | Segments: ${segs.size} | $mode\n(စာကို ကြာကြာနှိပ်ရင် ပြင်လို့ရ)",
            Toast.LENGTH_LONG
        ).show()
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
        ttsStop = true
        releasePlayer()
        super.onDestroy()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
