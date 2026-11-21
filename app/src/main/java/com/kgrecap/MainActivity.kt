package com.kgrecap

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10

class MainActivity : Activity() {
    private val pickCode = 1
    private val exportPermissionCode = 4
    private val videoHeightRatio = 0.36
    private val transcribeWorkers = 3

    private val videoOpts = doubleArrayOf(1.0, 1.1, 1.3, 1.5)
    private val voiceOpts = doubleArrayOf(1.0, 1.1, 1.2, 1.3)
    private val gainOpts = doubleArrayOf(1.0, 1.5, 2.0, 3.0, 4.0)
    private val silentOpts = doubleArrayOf(1.5, 2.0, 3.0, 4.0)
    private val pauseOpts = doubleArrayOf(0.6, 1.0, 1.5)
    private val pauseNames = arrayOf("နည်း", "ပုံမှန်", "များ")
    private val sylPerSec = 4.5
    private var videoSpeed = 1.0
    private var silentSpeed = 2.0
    private var voiceSpeed = 1.2
    private var voiceGain = 2.0
    private var pauseIdx = 1
    private var maleIdx = 1
    private var femaleIdx = 0
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
    private lateinit var btnVoiceM: Button
    private lateinit var btnVoiceF: Button
    private lateinit var btnSampleM: Button
    private lateinit var btnSampleF: Button
    private lateinit var btnVoiceId: Button
    private lateinit var btnPause: Button
    private lateinit var btnGain: Button
    private lateinit var btnSilent: Button
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
    // clip ထဲက စကားစ/စကားဆုံး စက္ကန့် (-1 = မသိ)၊ gender: 0=? 1=ယောက်ျား 2=မိန်းမ
    private var spStart: DoubleArray = DoubleArray(0)
    private var spEnd: DoubleArray = DoubleArray(0)
    private var gender: IntArray = IntArray(0)
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
        val prefs = getSharedPreferences("kgrecap", MODE_PRIVATE)
        voiceGain = prefs.getFloat("gain", 2.0f).toDouble()
        silentSpeed = prefs.getFloat("silent", 2.0f).toDouble()
        videoSpeed = prefs.getFloat("vspeed", 1.0f).toDouble()

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
        hint.text = "Speed/ယောက်ျား-မိန်းမ ကို Copy All မလုပ်ခင် သတ်မှတ်ပါ။ ပြောင်းရင် Copy All ပြန်လုပ်ပါ။"
        panel1 = vbox()
        panel1.addView(speedRow())
        panel1.addView(hint)
        panel1.addView(row(btnCopy, btnPaste))

        // ----- အသံ panel -----
        btnTts = button("") { onTts() }
        btnExport = button("Render / Save MP4") { onExport() }
        btnVoiceM = button("") { cycleVoice(true) }
        btnVoiceF = button("") { cycleVoice(false) }
        btnSampleM = button("▶ ♂ နမူနာ") { playSample(true) }
        btnSampleF = button("▶ ♀ နမူနာ") { playSample(false) }
        btnVoiceId = button("Voice ID ထည့်") { askCustomVoice() }
        btnPause = button("") { cyclePause() }
        btnGain = button("") { cycleGain() }
        btnSilent = button("") { cycleSilent() }
        panel2 = vbox()
        panel2.addView(row(btnVoiceM, btnSampleM))
        panel2.addView(row(btnVoiceF, btnSampleF))
        panel2.addView(row(btnPause, btnVoiceId))
        panel2.addView(speedRow())
        panel2.addView(row(btnGain, btnSilent))
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
        getSharedPreferences("kgrecap", MODE_PRIVATE).edit().putFloat("vspeed", videoSpeed.toFloat()).apply()
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

    private fun cycleSilent() {
        val i = silentOpts.indexOfFirst { Math.abs(it - silentSpeed) < 0.001 }
        silentSpeed = silentOpts[(i + 1) % silentOpts.size]
        getSharedPreferences("kgrecap", MODE_PRIVATE).edit().putFloat("silent", silentSpeed.toFloat()).apply()
        updateVoiceButtons()
        refreshAll()
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

    private fun choices(): List<VoicePreset> =
        if (customVoice.isNotBlank()) EdgeTts.presets + VoicePreset("Custom: $customVoice", customVoice, 0)
        else EdgeTts.presets

    private fun choiceFor(male: Boolean): VoicePreset {
        val c = choices()
        val idx = (if (male) maleIdx else femaleIdx).coerceIn(0, c.size - 1)
        return c[idx]
    }

    // အပိုင်းတစ်ခုချင်းရဲ့ gender အရ အသံရွေး (မသိရင် မိန်းမအသံ)
    private fun voiceOfPart(i: Int): VoicePreset = choiceFor(i < gender.size && gender[i] == 1)

    private fun cycleVoice(male: Boolean) {
        if (ttsRunning) {
            Toast.makeText(this, "TTS ရပ်ပြီးမှ ပြောင်းပါ", Toast.LENGTH_SHORT).show()
            return
        }
        val n = choices().size
        if (male) maleIdx = (maleIdx + 1) % n else femaleIdx = (femaleIdx + 1) % n
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
                maleIdx = maleIdx.coerceIn(0, choices().size - 1)
                femaleIdx = femaleIdx.coerceIn(0, choices().size - 1)
                updateVoiceButtons()
                invalidateTts()
                if (customVoice.isNotEmpty()) {
                    Toast.makeText(this, "♂/♀ Voice ခလုတ်ကို နှိပ်ပြီး Custom ကို ရွေးပါ", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun voiceRatePct(): Int = Math.round((voiceSpeed - 1.0) * 100).toInt()

    private fun updateVoiceButtons() {
        btnVoiceM.text = "♂ " + choiceFor(true).label
        btnVoiceF.text = "♀ " + choiceFor(false).label
        btnPause.text = "နားချိန်: " + pauseNames[pauseIdx]
        btnGain.text = String.format(Locale.US, "Volume: %.1fx", voiceGain)
        btnSilent.text = String.format(Locale.US, "စကားမပါ speed: %.1fx", silentSpeed)
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
                        else -> {
                val blank = texts.count { it != null && it.isBlank() }
                if (blank > 0) "စာထုတ်ပြီး $d/$n ✓\nစကားမပါ $blank ခန်း ပြန်စစ်မယ်"
                else "စာထုတ်ပြီး $d/$n ✓"
            }
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
        val sps = spStart
        val spe = spEnd
        val gs = gender
        val n = transcribeWorkers
        val next = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val errRef = AtomicReference<DeepgramException?>(null)

        for (k in 0 until n) {
            Thread {
                while (!stopReq && id == loadId && errRef.get() == null) {
                    val i = next.getAndIncrement()
                    if (i >= segs.size) break
                                        if (tx[i] != null && tx[i]?.isBlank() == false) continue
                    try {
                        val bytes = readPcmBytes(pcm, segs[i])
                        var text = ""
                        var st = -1.0
                        var en = -1.0
                        var g = 0
                        if (bytes.size >= 3200) {
                            val r = Deepgram.transcribe(key, Deepgram.wav(bytes))
                            text = r.text
                            st = r.start
                            en = r.end
                            if (text.isNotBlank()) g = Pitch.guessGender(bytes, st, en)
                        }
                        runOnUiThread {
                            if (id == loadId) {
                                tx[i] = text
                                sps[i] = st
                                spe[i] = en
                                if (gs[i] == 0) gs[i] = g
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

    // စကားမစခင်ပိုင်း (အမြန်ပြမယ့်အပိုင်း)
    private fun preSec(i: Int): Double =
        if (i < spStart.size && !texts[i].isNullOrBlank()) preRollSec(clipSec(i), spStart[i]) else 0.0

    // Gemini ကို ပေးမယ့် အချိန်ကန့်သတ်ချက် (စကားစတဲ့နေရာကနေ video speed အရ ပြောခွင့်ရတဲ့ အချိန်)
    private fun slotSec(i: Int) = (clipSec(i) - preSec(i)) / videoSpeed

    private fun planOf(i: Int): ScenePlan =
        planScene(clipSec(i) - preSec(i), ttsMs[i] / 1000.0, videoSpeed)

    private fun plan(i: Int): String {
        val body = clipSec(i) - preSec(i)
        val t = ttsMs[i] / 1000.0
        val p = planOf(i)
        val total = preSec(i) / silentSpeed + p.dur
        val flag = if (p.vSpeed < 0.749) "  ⚠ စာရှည်နေသေး (တိုပါ)" else ""
        return String.format(
            Locale.US, "🔊 %.1fs | video %.1fs→%.1fs (%.2fx) | အသံ %.2fx | ခန်းစုစုပေါင်း %.1fs%s",
            t, body, p.dur, p.vSpeed, p.aSpeed, total, flag
        )
    }

    private fun label(i: Int): String {
        val s = segments[i]
        val t = texts[i]
        val tr = trans[i]
        val gm = if (t != null && t.isBlank()) "" else when (gender[i]) {
            1 -> " ♂"
            2 -> " ♀"
            else -> if (!t.isNullOrBlank()) " ?" else ""
        }
        var r = "[%03d]%s  %s - %s  (%.1fs)".format(
            s.index, gm, fmt(s.startMs), fmt(s.endMs), clipSec(i)
        )
        if (t != null) {
            if (t.isBlank()) {
                val c = clipSec(i)
                r += String.format(Locale.US, "\n(စကားမပါ) ⏩ %.1fs→%.1fs (%.1fx)", c, c / silentSpeed, silentSpeed)
            } else {
                r += "\n" + t
            }
        }
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
2. Keep each header line exactly as given, including the times, the limit and the speaker, for example [Part 3 • 00:20 - 00:25 • 3.8s • max 21 syllables • speaker: male].
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
13. Do not add words such as ရှင်, ရှင့်, ခင်ဗျာ, ဗျာ, ကွယ်, နော် (politeness endings). Write neutral plain spoken sentences.
14. Do not add any word that is not part of the original meaning.

Speaker rules (very important):
15. Each header ends with "speaker: male", "speaker: female" or "speaker: unknown".
16. Write each line the way that speaker really talks. A male speaker must use male first-person words (ကျွန်တော်, ကျနော်, ငါ, ကိုယ်) and must NEVER use female ones (ကျွန်မ, ကျမ). A female speaker uses female first-person words (ကျွန်မ, ကျမ, ငါ, ကိုယ်) and NEVER male ones. For unknown, avoid gendered first-person words where possible.
17. Choose polite or casual speech from the relationship and mood of the scene, but never add the endings forbidden in rule 13.

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
                val who = when (gender[i]) {
                    1 -> "male"
                    2 -> "female"
                    else -> "unknown"
                }
                val head = String.format(
                    Locale.US, "[Part %d • %s - %s • %.1fs • max %d syllables • speaker: %s]",
                    s.index, mmss(s.startMs), mmss(s.endMs), e, maxSyl, who
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
        val lg = TextView(this)
        lg.text = "ပြောသူ (ယောက်ျား / မိန်းမ)"
        lg.setPadding(0, 24, 0, 0)
        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.HORIZONTAL
        val rb0 = RadioButton(this)
        rb0.text = "?"
        rb0.id = View.generateViewId()
        val rb1 = RadioButton(this)
        rb1.text = "♂ ယောက်ျား"
        rb1.id = View.generateViewId()
        val rb2 = RadioButton(this)
        rb2.text = "♀ မိန်းမ"
        rb2.id = View.generateViewId()
        rg.addView(rb0)
        rg.addView(rb1)
        rg.addView(rb2)
        rg.check(
            when (gender[i]) {
                1 -> rb1.id
                2 -> rb2.id
                else -> rb0.id
            }
        )
        box.addView(l1)
        box.addView(e1)
        box.addView(l2)
        box.addView(e2)
        box.addView(lg)
        box.addView(rg)
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
                val ng = when (rg.checkedRadioButtonId) {
                    rb1.id -> 1
                    rb2.id -> 2
                    else -> 0
                }
                if (ng != gender[i]) {
                    gender[i] = ng
                    ttsMs[i] = 0L
                    ttsFile(i).delete()
                    Toast.makeText(
                        this, "ပြောသူ ပြောင်းလို့ Copy All ပြန်လုပ်ပြီး Gemini မှာ ပြန်ခိုင်းပါ၊ ပြီးရင် TTS ပြန်ထုတ်ပါ",
                        Toast.LENGTH_LONG
                    ).show()
                }
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

    private fun playSample(male: Boolean) {
        if (sampling) return
        val vp = choiceFor(male)
        if (vp.id.isBlank()) {
            Toast.makeText(this, "Voice ID မရှိပါ", Toast.LENGTH_SHORT).show()
            return
        }
        sampling = true
        val b = if (male) btnSampleM else btnSampleF
        b.text = "ထုတ်နေ..."
        val rate = voiceRatePct()
        val ps = pauseOpts[pauseIdx]
        Thread {
            var err: String? = null
            val f = File(cacheDir, "sample.wav")
            try {
                EdgeTts.speak(
                    "မင်္ဂလာပါ၊ ဒီအသံကို ကြားရပါသလား။ ဇာတ်လမ်းထဲက စကားပြောကို၊ အခုလို ဖတ်ပေးပါမယ်။",
                    vp.id, vp.pitchHz, rate, ps, f, cacheDir
                )
            } catch (e: Exception) {
                err = e.message ?: e.toString()
            }
            runOnUiThread {
                sampling = false
                btnSampleM.text = "▶ ♂ နမူနာ"
                btnSampleF.text = "▶ ♀ နမူနာ"
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
        val vIds = Array(segments.size) { i -> voiceOfPart(i).id }
        val vPitch = IntArray(segments.size) { i -> voiceOfPart(i).pitchHz }
        if (todo.any { vIds[it].isBlank() }) {
            Toast.makeText(this, "Voice ID မရှိပါ", Toast.LENGTH_SHORT).show()
            return
        }
        ttsRunning = true
        ttsStop = false
        updateTtsButton()
        val id = loadId
        val tr = trans
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
                        EdgeTts.speak(text, vIds[i], vPitch[i], rate, ps, f, cacheDir)
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

        // စကားစ/စကားဆုံး အချိန်တွေကို segment ထဲ ထည့်ပြီး ပို့
        val segSnapshot = segments.mapIndexed { i, s ->
            val st = spStart[i]
            val en = spEnd[i]
            if (!texts[i].isNullOrBlank() && st >= 0.0 && en > st)
                s.copy(
                    speechStartMs = s.startMs + (st * 1000).toLong(),
                    speechEndMs = s.startMs + (en * 1000).toLong()
                )
            else s
        }
        val textSnapshot = trans.copyOf()
        val fileSnapshot = segments.indices.map { i ->
            if (textSnapshot[i].isNullOrBlank()) null else ttsFile(i)
        }
        val vsSnapshot = videoSpeed
        val silentSnapshot = silentSpeed
        val gainSnapshot = voiceGain
        exporting = true
        updateProgress()
        Thread {
            try {
                val savedUri = VideoExporter.export(
                    this@MainActivity, source, segSnapshot, textSnapshot, fileSnapshot,
                    vsSnapshot, silentSnapshot, gainSnapshot
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
        spStart = DoubleArray(0)
        spEnd = DoubleArray(0)
        gender = IntArray(0)
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
                db = AudioAnalyzer.decode(this, uri, pcm)
            } catch (e: Exception) { }

                        var result: List<Segment> = emptyList()
            var mode = ""
            if (db.isNotEmpty()) {
                try {
                    val f0 = Pitch.frameF0(pcm, db, AudioAnalyzer.threshold(db))
                    val byGender = AudioAnalyzer.buildByGender(db, f0, totalMs)
                    if (byGender.isNotEmpty()) {
                        result = byGender
                        mode = "ယောက်ျား/မိန်းမ အလိုက်"
                    }
                } catch (e: Throwable) { }
            }
            if (result.isEmpty()) {
                val smart = AudioAnalyzer.buildSegments(db, totalMs)
                if (smart.isNotEmpty()) {
                    result = smart
                    mode = "pause အလိုက်"
                } else {
                    result = AudioAnalyzer.fixedSegments(totalMs)
                    mode = "၁၃ စက္ကန့်စီ"
                }
            }
            val hasPcm = db.isNotEmpty()
            val finalResult = result
            val finalMode = mode
            runOnUiThread {
                if (id == loadId) {
                    pcmFile = if (hasPcm) pcm else null
                    showSegments(finalResult, head, finalMode)
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
        spStart = DoubleArray(segs.size) { -1.0 }
        spEnd = DoubleArray(segs.size) { -1.0 }
        gender = IntArray(segs.size) { segs[it].gender }
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
