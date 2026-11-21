package com.kgrecap

/**
 * စကားပြော အဆက်မပျက်သေးရင် မဖြတ်ဘူး၊ ရပ်သွားတာ သေချာမှ ဖြတ်မယ်။
 * အချိန်တွေက မူရင်း video timeline အတိုင်း (startMs/endMs)။
 */
object Splitter {
    private const val FRAME_MS = 50
    private const val MIN_F = 160          // 8s
    private const val SOFT_MAX_F = 300     // 15s
    private const val STRONG_LIMIT_F = 400 // 20s
    private const val HARD_MAX_F = 900     // 45s
    private const val STRONG = 30          // 1.5s သေချာရပ်
    private const val MEDIUM = 16          // 0.8s ဝါကျအဆုံး
    private const val WEAK = 8             // 0.4s
    private const val SKIP_F = 60          // 3s ကျော် တိတ်ရင် ဖယ်
    private const val PAD = 4              // 0.2s

    fun build(db: FloatArray, totalMs: Long): List<Segment> {
        val n = db.size
        if (n < 20) return emptyList()
        val sorted = db.sortedArray()
        val floor = sorted[(n * 0.2).toInt()]
        val ceil = sorted[minOf(n - 1, (n * 0.9).toInt())]
        val thr = floor + (ceil - floor) * 0.25f

        val gaps = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (db[i] <= thr) {
                var j = i
                while (j < n && db[j] <= thr) j++
                if (j - i >= WEAK) gaps.add(intArrayOf(i, j))
                i = j
            } else {
                i++
            }
        }

        var s = 0
        while (s < n && db[s] <= thr) s++
        if (s >= n) return emptyList()
        s = if (s <= SKIP_F) 0 else maxOf(0, s - PAD)

        val cuts = ArrayList<IntArray>()
        while (s < n) {
            val g = pick(db, gaps, s, n)
            if (g == null) {
                cuts.add(intArrayOf(s, n))
                break
            }
            val len = g[1] - g[0]
            val end: Int
            val next: Int
            if (g[1] >= n) {
                end = minOf(n, g[0] + PAD)
                next = n
            } else if (len > SKIP_F) {
                end = g[0] + PAD
                next = g[1] - PAD
            } else {
                val m = g[0] + len / 2
                end = m
                next = m
            }
            if (end > s) cuts.add(intArrayOf(s, end))
            s = if (next > s) next else n
        }

        val out = ArrayList<Segment>()
        for (c in cuts) {
            if (c[1] - c[0] < 8) continue
            val sMs = c[0].toLong() * FRAME_MS
            val eMs = if (c[1] >= n) totalMs else minOf(totalMs, c[1].toLong() * FRAME_MS)
            if (eMs - sMs < 300) continue
            out.add(Segment(out.size + 1, sMs, eMs))
        }
        return out
    }

    private fun pick(db: FloatArray, gaps: List<IntArray>, s: Int, n: Int): IntArray? {
        // 3s ကျော် တိတ်ဆိတ် = ခန်းပြောင်း၊ 8s မပြည့်သေးလည်း ဖြတ်
        for (g in gaps) {
            if (g[0] <= s + 4) continue
            if (g[0] >= s + MIN_F) break
            if (g[1] - g[0] > SKIP_F) return g
        }
        first(gaps, s, s + MIN_F, s + STRONG_LIMIT_F, STRONG)?.let { return it }
        longest(gaps, s, s + MIN_F, s + SOFT_MAX_F, MEDIUM)?.let { return it }
        first(gaps, s, s + STRONG_LIMIT_F, s + HARD_MAX_F, STRONG)?.let { return it }
        longest(gaps, s, s + SOFT_MAX_F, s + HARD_MAX_F, MEDIUM)?.let { return it }
        if (n - s <= HARD_MAX_F) return null
        longest(gaps, s, s + 600, s + HARD_MAX_F, WEAK)?.let { return it }
        // စကားမပြတ်ဘဲ 45s ကျော်ရင်သာ အတင်းဖြတ် (အသံအတိတ်ဆုံးနေရာ)
        var k = s + HARD_MAX_F
        for (x in (s + HARD_MAX_F - 60)..(s + HARD_MAX_F)) {
            if (x < n && db[x] < db[minOf(k, n - 1)]) k = x
        }
        k = minOf(k, n - 1)
        return intArrayOf(k, k + 1)
    }

    private fun first(gaps: List<IntArray>, s: Int, lo: Int, hi: Int, minLen: Int): IntArray? {
        for (g in gaps) {
            if (g[0] <= s + 4 || g[1] <= lo) continue
            if (g[0] > hi) break
            if (g[1] - g[0] >= minLen) return g
        }
        return null
    }

    private fun longest(gaps: List<IntArray>, s: Int, lo: Int, hi: Int, minLen: Int): IntArray? {
        var best: IntArray? = null
        for (g in gaps) {
            if (g[0] <= s + 4 || g[1] <= lo) continue
            if (g[0] > hi) break
            val len = g[1] - g[0]
            if (len >= minLen && (best == null || len > best[1] - best[0])) best = g
        }
        return best
    }
}
