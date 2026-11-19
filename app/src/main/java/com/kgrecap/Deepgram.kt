package com.kgrecap

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeepgramException(val code: Int, msg: String) : Exception(msg)

object Deepgram {
    private const val BASE = "https://api.deepgram.com/v1"

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

    fun transcribe(key: String, wav: ByteArray): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return once(key, wav)
            } catch (e: DeepgramException) {
                val retry = e.code == 429 || e.code >= 500 || e.code == -1
                if (!retry || attempt >= 3) throw e
                Thread.sleep(800L * attempt)
            }
        }
    }

    private fun once(key: String, wav: ByteArray): String {
        var c: HttpURLConnection? = null
        try {
            val url = URL("$BASE/listen?model=nova-3&language=multi&smart_format=true&punctuate=true")
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

    private fun parse(body: String): String {
        try {
            val alt = JSONObject(body).getJSONObject("results")
                .getJSONArray("channels").getJSONObject(0)
                .getJSONArray("alternatives").getJSONObject(0)
            return alt.optString("transcript", "").trim()
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
