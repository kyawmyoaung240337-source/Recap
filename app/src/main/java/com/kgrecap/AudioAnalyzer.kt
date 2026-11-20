package com.kgrecap

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.log10

/** gender: 0 = မသိ/စကားမပါ၊ 1 = ယောက်ျား၊ 2 = မိန်းမ */
data class Segment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val speechStartMs: Long = startMs,
    val speechEndMs: Long = endMs,
    val gender: Int = 0
)

object AudioAnalyzer {
    private const val FRAME_MS = 50
    private const val MAX_MS = 15_000
    private const val SKIP_GAP_MS = 3_000

    private const val MIN_RUN = 10        // 0.5s အောက် gender အပိုင်းတို ဖယ်
    private const val BRIDGE = 50         // 2.5s အတွင်း အသံတူ နားတာကို မဖြတ်
    private const val MAX_SPEECH = 1200   // အပိုင်းတစ်ခု အများဆုံး 60s
    private const val END_PAD = 5         // စကားအဆုံး 0.25s ထပ်ယူ
    private const val START_PAD = 3       // စကားအစ 0.15s ကြိုယူ
    private const val MIN_NS = 20         // စကားမပါ ခန်း အနည်းဆုံး 1s
    private const val SKIP_FRAMES = 60    // တိတ်ဆိတ် 3s ကျော်ရင် ဖယ်
    private const val NS_CHUNK = 300      // စကားမပါ ခန်း အများဆုံး 15s
    private const val SMOOTH = 8
    private const val SPLIT_HZ = 165f

    fun fixedSegments(totalMs: Long): List<Segment> {
        val step = 13_000L
        return (0L until totalMs step step).mapIndexed { i, s ->
            Segment(i + 1, s, minOf(s + step, totalMs))
        }
    }

    /** အသံပြည့်/တိတ် ခွဲတဲ့ threshold (dB) */
    fun threshold(db: FloatArray): Float {
        val n = db.size
        if (n == 0) return 0f
        val sorted = db.sortedArray()
        val floor = sorted[(n * 0.2).toInt()]
        val ceil = sorted[minOf(n - 1, (n * 0.9).toInt())]
        return floor + (ceil - floor) * 0.25f
    }

    /** အသံကို decode → 16kHz mono PCM ဖိုင်ထုတ် + 50ms frame တစ်ခုချင်းရဲ့ volume (dB) ပြန်ပေး */
    fun decode(context: Context, uri: Uri, outFile: File): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
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

    private class Run(var g: Int, var s: Int, var e: Int)

        /** [a,b) ထဲက အသံကျွန်း (≥0.25s) တွေကို ရှာ၊ 0.6s အတွင်း ဆက်နေရင် ပေါင်း */
    private fun islands(db: FloatArray, thr: Float, a: Int, b: Int): List<IntArray> {
        val raw = ArrayList<IntArray>()
        var k = a
        while (k < b) {
            if (db[k] > thr) {
                var j = k
                while (j < b && db[j] > thr) j++
                if (j - k >= 5) raw.add(intArrayOf(k, j))
                k = j
            } else {
                k++
            }
        }
        val out = ArrayList<IntArray>()
        for (r in raw) {
            val last = out.lastOrNull()
            if (last != null && r[0] - last[1] <= 12) last[1] = r[1] else out.add(intArrayOf(r[0], r[1]))
        }
        return out
    }

    private fun hasIsland(db: FloatArray, thr: Float, a: Int, b: Int): Boolean =
        islands(db, thr, a, b).isNotEmpty()

    /** တိုတဲ့ gap (3s အောက်) ဆို gap တစ်ခုလုံး၊ ရှည်ရင် အသံကျွန်းတွေကိုပဲ (±0.4s) */
    private fun soundRanges(db: FloatArray, thr: Float, a: Int, b: Int): List<IntArray> {
        if (b - a < SKIP_FRAMES) return listOf(intArrayOf(a, b))
        val out = ArrayList<IntArray>()
        for (r in islands(db, thr, a, b)) {
            val s = maxOf(a, r[0] - 8)
            val e = minOf(b, r[1] + 8)
            val last = out.lastOrNull()
            if (last != null && s <= last[1]) last[1] = e else out.add(intArrayOf(s, e))
        }
        return out
    }

    private fun addChunks(out: MutableList<Run>, s: Int, e: Int) {
        val len = e - s
        if (len < 4) return
        val parts = (len + NS_CHUNK - 1) / NS_CHUNK
        for (p in 0 until parts) out.add(Run(0, s + len * p / parts, s + len * (p + 1) / parts))
    }

    /**
     * ယောက်ျား/မိန်းမ အလိုက်ဖြတ်။ f0 = Pitch.frameF0 ရလဒ်။
     * သတ်မှတ်လို့မရရင် (voice နည်းလွန်း) empty ပြန် → pause အလိုက်နည်းကို သုံး။
     */
    fun buildByGender(db: FloatArray, f0: FloatArray, totalMs: Long): List<Segment> {
        val n = db.size
        if (n < 40 || f0.size != n) return emptyList()
        val thr = threshold(db)

        // 1) frame တစ်ခုချင်း gender label (±0.4s အတွင်း pitch အလယ်တန်ဖိုး)
        val label = IntArray(n)
        val tmp = FloatArray(2 * SMOOTH + 1)
        for (t in 0 until n) {
            if (db[t] <= thr) continue
            var c = 0
            for (k in maxOf(0, t - SMOOTH)..minOf(n - 1, t + SMOOTH)) {
                if (f0[k] > 0f) tmp[c++] = f0[k]
            }
            if (c < 3) continue
            java.util.Arrays.sort(tmp, 0, c)
            label[t] = if (tmp[c / 2] < SPLIT_HZ) 1 else 2
        }

        // 2) label တူတဲ့ ဆက်တိုက် run တွေ → တိုလွန်းတာ ဖယ် → gender တူ၊ နားချိန်တိုရင် ပေါင်း
        val runs = ArrayList<Run>()
        var t = 0
        while (t < n) {
            if (label[t] == 0) {
                t++
                continue
            }
            val g = label[t]
            var u = t
            while (u < n && label[u] == g) u++
            runs.add(Run(g, t, u))
            t = u
        }
        val spans = ArrayList<Run>()
        for (r in runs) {
            if (r.e - r.s < MIN_RUN) continue
            val last = spans.lastOrNull()
            if (last != null && last.g == r.g && r.s - last.e <= BRIDGE) last.e = r.e
            else spans.add(Run(r.g, r.s, r.e))
        }
        if (spans.isEmpty()) return emptyList()

        // 3) 60s ထက်ရှည်ရင် အသံအတိတ်ဆုံးနေရာမှာ ဖြတ်
        val capped = ArrayList<Run>()
        for (sp in spans) {
            var s = sp.s
            while (sp.e - s > MAX_SPEECH) {
                var cut = s + MAX_SPEECH
                val lo = maxOf(s + MAX_SPEECH - 200, s + 40)
                for (k in lo..cut) {
                    if (db[k] < db[cut]) cut = k
                }
                capped.add(Run(sp.g, s, cut))
                s = cut
            }
            capped.add(Run(sp.g, s, sp.e))
        }

        // 4) စကားပြောပိုင်းတွေကြားက gap ကို စီမံ
        val pieces = ArrayList<Run>()
        var lastSpeech: Run? = null
        var gapStart = 0
        for (i in 0..capped.size) {
            val next = capped.getOrNull(i)
            val gapEnd = next?.s ?: n
            val len = gapEnd - gapStart
            val prev = lastSpeech
            var nextStart = gapEnd
            if (len > 0) {
                val padE = if (prev != null) minOf(END_PAD, len / 2) else 0
                val padS = if (next != null) minOf(START_PAD, len - padE) else 0
                if (prev != null) prev.e += padE
                val a = gapStart + padE
                val b = gapEnd - padS
                nextStart = b
                val rem = b - a
                if (rem > 0) {
                    var sound = 0
                    for (k in a until b) {
                        if (db[k] > thr) sound++
                    }
                                        if (rem >= MIN_NS && hasIsland(db, thr, a, b)) {
                        // အသံရှိပေမဲ့ စကားမပါ → သီးသန့်ခန်း (၁၅ စက္ကန့်ထက် မရှည်စေ)
                                                for (r in soundRanges(db, thr, a, b)) addChunks(pieces, r[0], r[1])
                    } else if (rem >= SKIP_FRAMES) {
                        // တိတ်ဆိတ်ရှည် → ဖယ်
                    } else {
                        // တိုတဲ့ တိတ်ဆိတ်/အသံ → အနီးက စကားပြောပိုင်းနဲ့ ပေါင်း
                        if (prev != null && next != null) {
                            val m = a + rem / 2
                            prev.e = m
                            nextStart = m
                        } else if (prev != null) {
                            prev.e = b
                        } else if (next != null) {
                            nextStart = a
                        }
                    }
                }
            }
            if (next != null) {
                val piece = Run(next.g, nextStart, next.e)
                pieces.add(piece)
                lastSpeech = piece
                gapStart = next.e
            }
        }

        val out = ArrayList<Segment>()
        for (p in pieces) {
            if (p.e - p.s < 4) continue
            val sMs = p.s.toLong() * FRAME_MS
            val eMs = if (p.e >= n) totalMs else minOf(totalMs, p.e.toLong() * FRAME_MS)
            if (eMs - sMs < 150) continue
            out.add(Segment(out.size + 1, sMs, eMs, sMs, eMs, p.g))
        }
        return out
    }

    /** gender မသိတဲ့ အခြေအနေအတွက် အရင်နည်း (pause အလိုက်၊ ၁၅ စက္ကန့်အတွင်း) */
    fun buildSegments(db: FloatArray, totalMs: Long): List<Segment> {
        val n = db.size
        if (n < 20) return emptyList()
        val thr = threshold(db)
        val maxF = MAX_MS / FRAME_MS
        val minGap = 350 / FRAME_MS
        val strongGap = 700 / FRAME_MS
        val skipF = SKIP_GAP_MS / FRAME_MS
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
                c[0].toLong() * FRAME_MS,
                if (last) totalMs else minOf(totalMs, c[1].toLong() * FRAME_MS)
            )
        }
    }
}
