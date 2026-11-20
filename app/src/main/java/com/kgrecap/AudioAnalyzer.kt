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

data class Segment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val speechStartMs: Long = startMs,
    val speechEndMs: Long = endMs
)

object AudioAnalyzer {
    private const val FRAME_MS = 50
    private const val MAX_MS = 15_000
    private const val SKIP_GAP_MS = 3_000

    fun fixedSegments(totalMs: Long): List<Segment> {
        val step = 13_000L
        return (0L until totalMs step step).mapIndexed { i, s ->
            Segment(i + 1, s, minOf(s + step, totalMs))
        }
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

    fun buildSegments(db: FloatArray, totalMs: Long): List<Segment> {
        val n = db.size
        if (n < 20) return emptyList()
        val sorted = db.sortedArray()
        val floor = sorted[(n * 0.2).toInt()]
        val ceil = sorted[minOf(n - 1, (n * 0.9).toInt())]
        val thr = floor + (ceil - floor) * 0.25f
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
