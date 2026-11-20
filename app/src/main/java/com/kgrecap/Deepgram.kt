package com.kgrecap

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class DeepgramException(val code: Int, msg: String) : Exception(msg)

/** start/end = clip ထဲက စကားစ/စကားဆုံး စက္ကန့် (စကားမပါရင် -1) */
class Transcript(val text: String, val start: Double, val end: Double)

object Deepgram {
    private const val BASE = "https://api.deepgram.com/v1"
    @Volatile private var dominant: String? = null

    /** null = key အလုပ်လုပ်တယ်၊ တခြားဆို error စာ */
    fun validate(key: String): String? {
        val k = key.trim()
        if (k.isEmpty()) return "Key ထည့်ပါ"
        var c: HttpURLConnection? = null
        return try {
            c = URL("$BASE/auth/token").openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Authorization", "Token $k")
            val code = c.responseCode
            when {
                code in 200..299 -> null
                code == 401 || code == 403 -> "Key မမှန်ပါ (HTTP $code)"
                else -> "စစ်လို့မရပါ (HTTP $code)"
            }
        } catch (e: Exception) {
            "အင်တာနက် ချိတ်ဆက်မရပါ: ${e.message}"
        } finally {
            c?.disconnect()
        }
    }

    /** 16kHz mono PCM16 (little-endian) bytes → WAV bytes */
    fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + pcm.size)
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
        h.putInt(pcm.size)
        return h.array() + pcm
    }

    fun transcribe(key: String, wav: ByteArray): Transcript {
        val first = call(key, wav, "model=nova-3&language=multi")
        if (first.first.text.isNotBlank()) {
            val l = first.second
            if (!l.isNullOrBlank() && dominant == null) dominant = l
            return first.first
        }
        // စာမထွက် → အသံတကယ်ရှိရင် ဘာသာစကားတစ်မျိုးချင်း ထပ်စမ်း
        if (rms(wav) < 0.004) return first.first
        val tries = ArrayList<String>()
        dominant?.let { tries.add("model=nova-3&language=$it") }
        tries.add("model=nova-3-general&detect_language=true")
        for (q in tries) {
            try {
                val r = call(key, wav, q)
                if (r.first.text.isNotBlank()) {
                    val l = r.second
                    if (!l.isNullOrBlank()) dominant = l
                    return r.first
                }
            } catch (e: DeepgramException) {
                if (e.code == 400 || e.code == 422) continue
                throw e
            }
        }
        return first.first
    }

    private fun rms(wav: ByteArray): Double {
        var s = 0.0
        var c = 0
        var i = 44
        while (i + 1 < wav.size) {
            val v = ((wav[i + 1].toInt() shl 8) or (wav[i].toInt() and 0xFF)) / 32768.0
            s += v * v
            c++
            i += 2
        }
        return if (c == 0) 0.0 else sqrt(s / c)
    }

    private fun call(key: String, wav: ByteArray, query: String): Pair<Transcript, String?> {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return once(key, wav, query)
            } catch (e: DeepgramException) {
                val retry = e.code == 429 || e.code >= 500 || e.code == -1
                if (!retry || attempt >= 3) throw e
                Thread.sleep(800L * attempt)
            }
        }
    }

    private fun once(key: String, wav: ByteArray, query: String): Pair<Transcript, String?> {
        var c: HttpURLConnection? = null
        try {
            val url = URL("$BASE/listen?$query&smart_format=true&punctuate=true")
            c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("Authorization", "Token ${key.trim()}")
            c.setRequestProperty("Content-Type", "audio/wav")
            c.setFixedLengthStreamingMode(wav.size)
            c.outputStream.use { it.write(wav) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw DeepgramException(code, errorText(code, body))
            return parse(body)
        } catch (e: DeepgramException) {
            throw e
        } catch (e: Exception) {
            throw DeepgramException(-1, "အင်တာနက် ချိတ်ဆက်မရပါ: ${e.message}")
        } finally {
            c?.disconnect()
        }
    }

    private fun parse(body: String): Pair<Transcript, String?> {
        try {
            val ch = JSONObject(body).getJSONObject("results")
                .getJSONArray("channels").getJSONObject(0)
            val alt = ch.getJSONArray("alternatives").getJSONObject(0)
            val text = alt.optString("transcript", "").trim()
            val words = alt.optJSONArray("words")
            var st = -1.0
            var en = -1.0
            if (text.isNotEmpty() && words != null && words.length() > 0) {
                st = words.getJSONObject(0).optDouble("start", -1.0)
                en = words.getJSONObject(words.length() - 1).optDouble("end", -1.0)
            }
            var lang: String? = null
            val ls = alt.optJSONArray("languages")
            if (ls != null && ls.length() > 0) lang = ls.optString(0, "")
            if (lang.isNullOrBlank()) lang = ch.optString("detected_language", "")
            val l = lang?.takeIf { it.isNotBlank() }
            return Pair(Transcript(text, st, en), l)
        } catch (e: Exception) {
            throw DeepgramException(-2, "Deepgram ပြန်စာ မှန်ကန်မှုမရှိပါ: ${body.take(120)}")
        }
    }

    private fun errorText(code: Int, body: String): String = when (code) {
        401, 403 -> "Key မမှန်/ခွင့်မရှိပါ (HTTP $code)"
        402 -> "Deepgram balance/credit မလောက်ပါ (HTTP 402)"
        429 -> "တောင်းဆိုမှု များလွန်းနေပါတယ် (HTTP 429)"
        else -> "HTTP $code: " + body.take(200)
    }
}
