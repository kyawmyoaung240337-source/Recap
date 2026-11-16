package com.kgrecap

import java.nio.ByteBuffer

object Gec {
    private const val M = 0xFFFFFFFFL

    private val SHA256_INITIAL = longArrayOf(
        0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL,
        0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L
    )
    private val SHA256_STATE = longArrayOf(
        0xfd9fbea2L, 0x91243ce7L, 0xbe499094L, 0xcd2e1e0aL,
        0x6c006f49L, 0x04bd35abL, 0xfeb0a7e4L, 0x03effbd2L
    )
    private val SHA256_SCHEDULE = longArrayOf(
        0xeb24bf9eL, 0x1558691fL, 0xa0c09ee2L, 0x0cfa32baL, 0xa622ee8cL,
        0xd1dd9491L, 0xc0dd25a0L, 0xf9e70ab6L, 0xf0c31573L, 0x7058ce4eL,
        0x7188e606L, 0x00e5ab33L, 0xd258b060L, 0x72766564L
    )
    private val K = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L, 0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L, 0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L, 0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L
    )
    private const val SHA256_ADJ = 0xc9dc06a7L
    private val K_BLOCK: LongArray = K.copyOf().also { it[14] = SHA256_ADJ }
    private const val SIGMA0_ADJ = 0x059c0b80L
    private const val SIGMA1_ADJ = 0x000b92c0L

    private val CS_C = longArrayOf(
        0xe82e91b1L, 0x44de24c6L, 0x3beb65bcL, 0xc89c5933L, 0x8d1ec211L, 0x314ef5d5L, 0xe92491b1L,
        0xf0a5b9d5L, 0x06064cf3L, 0xda79fa60L, 0xbed20560L, 0x89f9c9f8L, 0x35fa38c6L, 0xcb49e3c6L
    )
    private val CS_O = longArrayOf(
        0xb32700d0L, 0x88dff51dL, 0x29514038L, 0xb328cbdfL, 0xd405b4d5L, 0x26e55f3cL, 0x607801c6L,
        0x82b2c1fbL, 0xfa51a3daL, 0x207cc464L, 0x3c3c74f3L, 0xd2d14b61L, 0xbb04a2c1L, 0xc5626167L
    )
    private const val CS_FACTOR = 0x82893933L
    private const val CS_ADJ0 = 0xf2f90b1aL
    private const val CS_ADJ1 = 0x0b1a0000L

    private fun rol16(v: Long): Long {
        val x = v and M
        return ((x shl 16) or (x ushr 16)) and M
    }

    private fun rotr(v: Long, n: Int): Long = ((v ushr n) or (v shl (32 - n))) and M

    private fun sigma0(v: Long): Long =
        ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and M

    private fun sigma1(v: Long): Long =
        ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and M

    private fun bswap(v: Long): Long =
        ((v and 0xffL) shl 24) or ((v and 0xff00L) shl 8) or ((v ushr 8) and 0xff00L) or (v ushr 24)

    private fun beWord(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun beInt(b: ByteArray, from: Int, to: Int): Long {
        var r = 0L
        for (i in from until to) r = (r shl 8) or (b[i].toLong() and 0xFF)
        return r
    }

    private fun rounds(state: LongArray, w: LongArray, first: Int): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (first == 14) K_BLOCK else K
        for (i in first until 64) {
            val t1 = h + (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) +
                (g xor (e and (f xor g))) + k[i] + w[i]
            val t2 = (rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) +
                ((a and b) or (c and (a or b)))
            h = g; g = f; f = e
            e = (d + t1) and M
            d = c; c = b; b = a
            a = (t1 + t2) and M
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expand(w: LongArray, first: Int) {
        for (i in first until 64) {
            w[i] = (sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]) and M
        }
    }

    private fun sha256Init(data: ByteArray): LongArray {
        val w = LongArray(64)
        w[14] = beInt(data, 0, 3)
        w[15] = beInt(data, 3, 7)
        for (i in 16 until 30) {
            var v = SHA256_SCHEDULE[i - 16]
            if (i - 2 >= 14) {
                v += if (i == 16) (sigma1(w[i - 2]) xor SIGMA1_ADJ) else sigma1(w[i - 2])
            }
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) {
                v += if (i == 29) (sigma0(w[i - 15]) xor SIGMA0_ADJ) else sigma0(w[i - 15])
            }
            w[i] = v and M
        }
        w[30] = (sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + SHA256_ADJ - K[14]) and M
        expand(w, 31)
        val working = rounds(SHA256_STATE, w, 14)
        return LongArray(8) { (SHA256_INITIAL[it] + working[it]) and M }
    }

    private fun sha256Digest(data: ByteArray): LongArray {
        val length = 57 + data.size
        val padLen = (((55 - length) % 64) + 64) % 64
        val rest = data.copyOfRange(7, data.size)
        val padded = ByteArray(rest.size + 1 + padLen + 8)
        System.arraycopy(rest, 0, padded, 0, rest.size)
        padded[rest.size] = 0x80.toByte()
        ByteBuffer.wrap(padded).putLong(padded.size - 8, length.toLong() * 8L)
        var state = sha256Init(data.copyOfRange(0, 7))
        var o = 0
        while (o < padded.size) {
            val w = LongArray(64)
            for (j in 0 until 16) w[j] = beWord(padded, o + j * 4)
            expand(w, 16)
            val working = rounds(state, w, 0)
            state = LongArray(8) { (state[it] + working[it]) and M }
            o += 64
        }
        return state
    }

    private fun wordsLe4Even(data: ByteArray): LongArray {
        val n = (data.size + 3) / 4
        val cnt = if (n % 2 == 1) n + 1 else n
        val out = LongArray(cnt)
        for (i in 0 until n) {
            var v = 0L
            for (j in 0 until 4) {
                val idx = i * 4 + j
                val b = if (idx < data.size) data[idx].toLong() and 0xFF else 0L
                v = v or (b shl (8 * j))
            }
            out[i] = v
        }
        return out
    }

    private fun cs64Digest(data: ByteArray, state: LongArray): LongArray {
        val keyA = bswap(state[0]) or 1L
        val keyB = bswap(state[1]) or 1L
        val factors = longArrayOf((keyA * CS_FACTOR) and M, (keyB * CS_FACTOR) and M)

        val prefixed = ByteArray(data.size + 1)
        System.arraycopy(data, 0, prefixed, 1, data.size)
        val words = wordsLe4Even(prefixed)

        val total = CS_C.size + words.size
        val terms = LongArray(total)
        val offsets = LongArray(total)
        for (i in CS_C.indices) {
            terms[i] = (CS_C[i] * factors[i % 2]) and M
            offsets[i] = CS_O[i]
        }
        for ((i, word) in words.withIndex()) {
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (i % 2 == 0) 0xef0569fbL else 0xc31713dbL)
            if (i == 0) {
                term += CS_ADJ0 * factors[0]
                offset += CS_ADJ1
            }
            terms[CS_C.size + i] = term and M
            offsets[CS_C.size + i] = offset and M
        }

        var swapAcc = 0L
        var swapLast = 0L
        var st = 0L
        var acc = 0L
        var i = 0
        while (i < total) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and M)
            var q = (t * 0x76f50f34L) and M
            swapAcc += (rol16(q) * 0xc3efea97L + q) and M
            t = rol16((offsets[i + 1] + y) and M)
            q = (t * 0x1881ce3cL) and M
            swapLast = (rol16(q) * 0x35bd1ec9L + q) and M
            swapAcc = (swapAcc + swapLast) and M
            st = (st * keyA + x) and M
            st = (rol16(st) * 0xcf98b111L) and M
            st = (rol16(st) * 0x87085b9fL) and M
            st = (rol16(st) * 0x12ceb96dL) and M
            st = (rol16(st) * 0x257e1d83L) and M
            acc += st
            t = (st * keyB + y) and M
            t = (rol16(t) * 0xa27416f5L) and M
            t = (rol16(t) * 0xd38396ffL) and M
            st = (rol16((rol16(t) * 0x7c932b89L) and M) * 0xbfa49f69L) and M
            acc = (acc + st) and M
            i += 2
        }
        return longArrayOf(swapLast xor st, swapAcc xor acc)
    }

    private fun hex8(v: Long): String = String.format("%08X", v and M)

    fun v2(token: String, unixSeconds: Long): String {
        var t = unixSeconds + 11644473600L
        t -= t % 300
        val data = ((t * 10_000_000L).toString() + token).toByteArray(Charsets.US_ASCII)
        val state = sha256Digest(data)
        val c = cs64Digest(data, state)
        val sb = StringBuilder()
        sb.append(hex8(bswap(c[0]))).append(hex8(bswap(c[1]))).append('-')
        for (s in state) sb.append(hex8(s))
        return sb.toString()
    }
}
