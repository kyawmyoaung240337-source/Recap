package com.kgrecap

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Deepgram speech-to-text (nova-3, ဘာသာစကား အလိုအလျောက်သိ) */
object Deepgram {
    class ApiError(val code: Int, msg: String) : Exception(msg)
    class Result(val ok: Boolean, val message: String)

    private const val LISTEN =
        "https://api.deepgram.com/v1/listen?model=nova-3&language=multi&smart_format=true&punctuate=true"
    private const val AUTH = "https://api.deepgram.com/v1/auth/token"

    private fun readAll(c: HttpURLConnection, error: Boolean): String {
        val s = (if (error) c.errorStream else c.inputStream) ?: return ""
        val out = ByteArrayOutputStream()
        s.use { it.copyTo(out) }
        return out.toString("UTF-8")
    }

    private fun explain(code: Int, body: String): String = when (code) {
        401, 403 -> "Deepgram key မမှန်ပါ (သို့) ခွင့်ပြုချက်မရှိပါ"
        402 -> "Deepgram credit ကုန်နေပါပြီ"
        429 -> "Deepgram request များလွန်းနေပါတယ်၊ ခဏနေမှ ထပ်လုပ်ပါ"
        else -> "Deepgram error $code " + body.take(200)
    }

    /** Key အလုပ်လုပ်မလုပ် စစ်: GET /v1/auth/token */
    fun validate(key: String): Result {
        return try {
            val c = URL(AUTH).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("Authorization", "Token $key")
                val code = c.responseCode
                if (code == 200) {
                    Result(true, "OK")
                } else {
                    Result(false, explain(code, readAll(c, true)))
                }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            Result(false, "အင်တာနက် ချိတ်မရလို့ key ကို မစစ်နိုင်ပါ")
        } catch (e: Exception) {
            Result(false, e.message ?: e.toString())
        }
    }

    /** 16kHz mono 16-bit PCM ကို WAV ဖိုင်ပုံစံ ပြောင်း */
    fun wavOf(pcm: ByteArray, rate: Int = 16000): ByteArray {
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
        val out = ByteArray(44 + pcm.size)
        System.arraycopy(h.array(), 0, out, 0, 44)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    /** WAV တစ်ပိုင်းကို စာထုတ် (မူရင်းဘာသာစကား အတိုင်း) */
    fun transcribe(key: String, wav: ByteArray): String {
        var attempt = 0
        while (true) {
            val c = URL(LISTEN).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 20000
                c.readTimeout = 60000
                c.setRequestProperty("Authorization", "Token $key")
                c.setRequestProperty("Content-Type", "audio/wav")
                c.setFixedLengthStreamingMode(wav.size)
                c.outputStream.use { it.write(wav) }
                val code = c.responseCode
                if (code == 200) {
                    return parse(readAll(c, false))
                }
                val body = readAll(c, true)
                if ((code == 429 || code >= 500) && attempt < 3) {
                    attempt++
                    Thread.sleep(1500L * attempt)
                    continue
                }
                throw ApiError(code, explain(code, body))
            } catch (e: IOException) {
                if (attempt < 2) {
                    attempt++
                    Thread.sleep(1000L * attempt)
                    continue
                }
                throw Exception("အင်တာနက် ချိတ်မရပါ: " + (e.message ?: ""))
            } finally {
                c.disconnect()
            }
        }
    }

    private fun parse(body: String): String {
        val alt = JSONObject(body)
            .optJSONObject("results")
            ?.optJSONArray("channels")
            ?.optJSONObject(0)
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
        return alt?.optString("transcript", "") ?: ""
    }
}
