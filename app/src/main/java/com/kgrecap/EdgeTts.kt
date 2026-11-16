package com.kgrecap

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class VoicePreset(val label: String, val id: String, val pitchHz: Int)

class SkewException(msg: String) : Exception(msg)

object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM = "143.0.3650.75"
    private const val PAUSE_COMMA = 280
    private const val PAUSE_STOP = 550

    val presets = listOf(
        VoicePreset("Nilar • မိန်းမ", "my-MM-NilarNeural", 0),
        VoicePreset("Thiha • ယောက်ျား", "my-MM-ThihaNeural", 0),
        VoicePreset("Nilar • အသံမြင့်", "my-MM-NilarNeural", 12),
        VoicePreset("Nilar • အသံနိမ့်", "my-MM-NilarNeural", -10),
        VoicePreset("Thiha • အသံမြင့်", "my-MM-ThihaNeural", 10),
        VoicePreset("Thiha • အသံနိမ့်", "my-MM-ThihaNeural", -12)
    )

    @Volatile private var skewSec = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    class Pcm(val data: ShortArray, val rate: Int)
    private class Part(val text: String, val pauseMs: Int)

    // ---------- auth ----------

    // Sec-MS-GEC v2 token (Gec.kt မှာ)
    private fun gec(): String =
        Gec.v2(TOKEN, System.currentTimeMillis() / 1000 + skewSec)

    private fun stamp(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(System.currentTimeMillis() + skewSec * 1000)) +
            " GMT+0000 (Coordinated Universal Time)"
    }

    private fun fullVoice(v: String): String {
        val m = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(v) ?: return v
        val lang = m.groupValues[1]
        var region = m.groupValues[2]
        var name = m.groupValues[3]
        val dash = name.indexOf('-')
        if (dash != -1) {
            region = region + "-" + name.substring(0, dash)
            name = name.substring(dash + 1)
        }
        return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
    }

    private fun esc(s: String): String {
        val c = s.filter { it.code >= 32 }
        return c.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
    }

    // ---------- websocket ----------

    private fun synth(text: String, voice: String, pitchHz: Int, ratePct: Int): ByteArray {
        try {
            return synthOnce(text, voice, pitchHz, ratePct)
        } catch (e: SkewException) {
            return synthOnce(text, voice, pitchHz, ratePct)
        }
    }

    private fun synthOnce(text: String, voice: String, pitchHz: Int, ratePct: Int): ByteArray {
        val id = UUID.randomUUID().toString().replace("-", "")
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TOKEN&ConnectionId=$id" +
            "&Sec-MS-GEC=${gec()}&Sec-MS-GEC-Version=2-$CHROMIUM"
        val major = CHROMIUM.substringBefore('.')
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=$muid;")
            .build()

        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val err = arrayOfNulls<Exception>(1)

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = stamp()
                webSocket.send(
                    "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                        "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                )
                val rate = (if (ratePct >= 0) "+" else "") + ratePct + "%"
                val pitch = (if (pitchHz >= 0) "+" else "") + pitchHz + "Hz"
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
                    "xml:lang='en-US'><voice name='${fullVoice(voice)}'>" +
                    "<prosody pitch='$pitch' rate='$rate' volume='+0%'>${esc(text)}</prosody>" +
                    "</voice></speak>"
                webSocket.send(
                    "X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    done.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val hl = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (b.size < 2 + hl) return
                val head = String(b, 2, hl, Charsets.UTF_8)
                if (head.contains("Path:audio")) {
                    val n = b.size - 2 - hl
                    if (n > 0) audio.write(b, 2 + hl, n)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null && response.code == 403) {
                    val d = response.header("Date")
                    if (d != null) {
                        try {
                            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                            val server = f.parse(d)!!.time / 1000
                            skewSec = server - System.currentTimeMillis() / 1000
                        } catch (e: Exception) { }
                    }
                    err[0] = SkewException("Edge TTS 403 (Microsoft က ပိတ်/ပြောင်းထားနိုင်)")
                } else {
                    err[0] = Exception("Edge TTS: ${t.message ?: t.toString()} ${response?.code ?: ""}")
                }
                done.countDown()
            }
        })

        if (!done.await(40, TimeUnit.SECONDS)) {
            ws.cancel()
            throw Exception("Edge TTS timeout")
        }
        err[0]?.let { throw it }
        val out = audio.toByteArray()
        if (out.isEmpty()) throw Exception("Edge TTS: အသံ မရပါ")
        return out
    }

    // ---------- mp3 -> pcm ----------

    private fun decodeMp3(mp3: ByteArray, tmpDir: File): Pcm {
        val f = File(tmpDir, "edge_${System.nanoTime()}.mp3")
        f.writeBytes(mp3)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.absolutePath)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) throw Exception("mp3 decode မရပါ")
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var data = ShortArray(24000 * 10)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    var acc = 0
                    var c = 0
                    while (sb.hasRemaining()) {
                        acc += sb.get().toInt()
                        c++
                        if (c >= ch) {
                            if (n == data.size) data = data.copyOf(n * 2)
                            data[n++] = (acc / ch).toShort()
                            acc = 0
                            c = 0
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            return Pcm(data.copyOf(n), rate)
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            ex.release()
            f.delete()
        }
    }

    // အစ/အဆုံးက တိတ်ဆိတ်တဲ့အပိုင်း ဖြတ် (နားချိန်ကို ကိုယ်တိုင်ထိန်းဖို့)
    private fun trim(p: Pcm): ShortArray {
        val d = p.data
        val th = 350
        var a = 0
        while (a < d.size && Math.abs(d[a].toInt()) < th) a++
        var b = d.size - 1
        while (b > a && Math.abs(d[b].toInt()) < th) b--
        if (a >= b) return d
        val m = p.rate / 50
        return d.copyOfRange(maxOf(0, a - m), minOf(d.size, b + m))
    }

    private fun writeWav(f: File, pcm: ShortArray, rate: Int) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            val len = pcm.size * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray())
            h.putInt(36 + len)
            h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray())
            h.putInt(16)
            h.putShort(1)
            h.putShort(1)
            h.putInt(rate)
            h.putInt(rate * 2)
            h.putShort(2)
            h.putShort(16)
            h.put("data".toByteArray())
            h.putInt(len)
            raf.write(h.array())
            val body = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
            body.asShortBuffer().put(pcm)
            raf.write(body.array())
        }
    }

    // ၊ ။ မှာ ဖြတ်
    private fun splitParts(t: String): List<Part> {
        val out = ArrayList<Part>()
        val sb = StringBuilder()
        fun flush(p: Int) {
            val s = sb.toString().trim()
            sb.setLength(0)
            if (s.isNotEmpty()) out.add(Part(s, p))
        }
        for (ch in t) {
            when (ch) {
                '၊' -> flush(PAUSE_COMMA)
                '။', '!', '?', '！', '？', '。', '\n' -> flush(PAUSE_STOP)
                else -> sb.append(ch)
            }
        }
        flush(0)
        return out
    }

    // စာ → WAV ဖိုင်၊ ၊ ။ မှာ နားချိန်ထည့်
    fun speak(
        text: String, voice: String, pitchHz: Int, ratePct: Int,
        pauseScale: Double, out: File, tmpDir: File
    ) {
        val parts = splitParts(text)
        if (parts.isEmpty()) throw Exception("စာမရှိပါ")
        var rate = 0
        val all = ArrayList<ShortArray>()
        for ((i, p) in parts.withIndex()) {
            val pcm = decodeMp3(synth(p.text, voice, pitchHz, ratePct), tmpDir)
            if (rate == 0) rate = pcm.rate
            all.add(trim(pcm))
            if (i < parts.size - 1 && p.pauseMs > 0) {
                all.add(ShortArray((rate * p.pauseMs * pauseScale / 1000.0).toInt()))
            }
        }
        var total = 0
        for (a in all) total += a.size
        val merged = ShortArray(total)
        var pos = 0
        for (a in all) {
            System.arraycopy(a, 0, merged, pos, a.size)
            pos += a.size
        }
        writeWav(out, merged, rate)
    }
}
