package com.kgrecap

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** အသံ pitch (F0) ကို တိုင်းပြီး ယောက်ျား/မိန်းမ ခန့်မှန်း */
object Pitch {
    private const val RATE = 16000
    private const val WIN = 640
    private const val HOP = 1280
    private const val MIN_LAG = 40
    private const val MAX_LAG = 228

    /** pcm = 16kHz mono PCM16LE bytes။ ပြန်တန်ဖိုး 0 = မသိ၊ 1 = ယောက်ျား၊ 2 = မိန်းမ */
    fun guessGender(pcm: ByteArray, startSec: Double, endSec: Double): Int {
        var a = 0
        var b = pcm.size - pcm.size % 2
        if (startSec >= 0.0 && endSec > startSec) {
            val x = ((startSec * RATE).toInt() * 2).coerceIn(0, b)
            val y = ((endSec * RATE).toInt() * 2).coerceIn(x, b)
            if (y - x >= RATE * 2 * 3 / 10) {
                a = x
                b = y
            }
        }
        val f0 = medianF0(pcm, a, b)
        return when {
            f0 <= 0f -> 0
            f0 < 165f -> 1
            else -> 2
        }
    }

    private fun medianF0(pcm: ByteArray, from: Int, to: Int): Float {
        val n = (to - from) / 2
        if (n < WIN + MAX_LAG + 2) return 0f
        val sb = ByteBuffer.wrap(pcm, from, to - from).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val x = FloatArray(n)
        for (i in 0 until n) x[i] = sb.get(i) / 32768f
        val r = DoubleArray(MAX_LAG + 2)
        val f0s = ArrayList<Float>()
        var pos = 0
        while (pos + WIN + MAX_LAG < n) {
            var e0 = 0.0
            for (i in 0 until WIN) e0 += (x[pos + i] * x[pos + i]).toDouble()
            if (e0 / WIN > 1e-4) {
                var best = 0.0
                for (lag in (MIN_LAG - 1)..(MAX_LAG + 1)) {
                    var s = 0.0
                    var e1 = 0.0
                    for (i in 0 until WIN) {
                        val y = x[pos + i + lag]
                        s += (x[pos + i] * y).toDouble()
                        e1 += (y * y).toDouble()
                    }
                    val v = s / sqrt(e0 * e1 + 1e-9)
                    r[lag] = v
                    if (lag in MIN_LAG..MAX_LAG && v > best) best = v
                }
                if (best > 0.55) {
                    var pick = 0
                    for (lag in MIN_LAG..MAX_LAG) {
                        if (r[lag] >= 0.9 * best && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                            pick = lag
                            break
                        }
                    }
                    if (pick > 0) f0s.add(RATE.toFloat() / pick)
                }
            }
            pos += HOP
        }
        if (f0s.size < 4) return 0f
        f0s.sort()
        return f0s[f0s.size / 2]
    }
}
