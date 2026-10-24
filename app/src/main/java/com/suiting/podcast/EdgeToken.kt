/*
 * Edge request token algorithm adapted from rany2/edge-tts.
 * Copyright (c) 2025- rany <rany@riseup.net>. LGPL-3.0.
 * Upstream: src/edge_tts/drm.py, 707dc7724b63242624b9b62175556fa1fdcb7fad.
 * Original license is included in THIRD_PARTY_EDGE_LICENSE.txt.
 */
package com.suiting.podcast

import java.nio.ByteBuffer
import java.nio.ByteOrder

object EdgeToken {
    const val CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private fun words(hex: String) = hex.trim().split(Regex("\\s+")).map { it.toLong(16).toInt() }.toIntArray()
    private val initial = words("6A09E667 BB67AE85 3C6EF372 A54FF53A 510E527F 9B05688C 1F83D9AB 5BE0CD19")
    private val preState = words("FD9FBEA2 91243CE7 BE499094 CD2E1E0A 6C006F49 04BD35AB FEB0A7E4 03EFFBD2")
    private val round = words("""
        428A2F98 71374491 B5C0FBCF E9B5DBA5 3956C25B 59F111F1 923F82A4 AB1C5ED5
        D807AA98 12835B01 243185BE 550C7DC3 72BE5D74 80DEB1FE 9BDC06A7 C19BF174
        E49B69C1 EFBE4786 0FC19DC6 240CA1CC 2DE92C6F 4A7484AA 5CB0A9DC 76F988DA
        983E5152 A831C66D B00327C8 BF597FC7 C6E00BF3 D5A79147 06CA6351 14292967
        27B70A85 2E1B2138 4D2C6DFC 53380D13 650A7354 766A0ABB 81C2C92E 92722C85
        A2BFE8A1 A81A664B C24B8B70 C76C51A3 D192E819 D6990624 F40E3585 106AA070
        19A4C116 1E376C08 2748774C 34B0BCB5 391C0CB3 4ED8AA4A 5B9CCA4F 682E6FF3
        748F82EE 78A5636F 84C87814 8CC70208 90BEFFFA A4506CEB BEF9A3F7 C67178F2
    """)
    private val schedule = words("EB24BF9E 1558691F A0C09EE2 0CFA32BA A622EE8C D1DD9491 C0DD25A0 F9E70AB6 F0C31573 7058CE4E 7188E606 00E5AB33 D258B060 72766564")
    private val coefficients = words("E82E91B1 44DE24C6 3BEB65BC C89C5933 8D1EC211 314EF5D5 E92491B1 F0A5B9D5 06064CF3 DA79FA60 BED20560 89F9C9F8 35FA38C6 CB49E3C6")
    private val baseOffsets = words("B32700D0 88DFF51D 29514038 B328CBDF D405B4D5 26E55F3C 607801C6 82B2C1FB FA51A3DA 207CC464 3C3C74F3 D2D14B61 BB04A2C1 C5626167")
    private fun rol(x: Int) = Integer.rotateLeft(x, 16)
    private fun s0(x: Int) = Integer.rotateRight(x, 7) xor Integer.rotateRight(x, 18) xor (x ushr 3)
    private fun s1(x: Int) = Integer.rotateRight(x, 17) xor Integer.rotateRight(x, 19) xor (x ushr 10)
    private fun expand(w: IntArray, start: Int) { for (i in start..63) w[i] = s1(w[i-2]) + w[i-7] + s0(w[i-15]) + w[i-16] }
    private fun rounds(state: IntArray, w: IntArray, start: Int): IntArray {
        var a=state[0]; var b=state[1]; var c=state[2]; var d=state[3]
        var e=state[4]; var f=state[5]; var g=state[6]; var h=state[7]
        for (i in start..63) {
            val k = if (i == 14 && start == 14) 0xC9DC06A7.toInt() else round[i]
            val t1 = h + (Integer.rotateRight(e,6) xor Integer.rotateRight(e,11) xor Integer.rotateRight(e,25)) + (g xor (e and (f xor g))) + k + w[i]
            val t2 = (Integer.rotateRight(a,2) xor Integer.rotateRight(a,13) xor Integer.rotateRight(a,22)) + ((a and b) or (c and (a or b)))
            h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2
        }
        return intArrayOf(a,b,c,d,e,f,g,h)
    }
    private fun digest(data: ByteArray): ByteArray {
        val w = IntArray(64)
        w[14] = ((data[0].toInt() and 255) shl 16) or ((data[1].toInt() and 255) shl 8) or (data[2].toInt() and 255)
        w[15] = ByteBuffer.wrap(data,3,4).int
        for (i in 16..29) {
            var value = schedule[i-16]
            if (i-2 >= 14) value += s1(w[i-2]) xor (if (i == 16) 0x000B92C0 else 0)
            if (i-7 >= 14) value += w[i-7]
            if (i-15 >= 14) value += s0(w[i-15]) xor (if (i == 29) 0x059C0B80 else 0)
            w[i] = value
        }
        w[30] = s1(w[28]) + w[23] + s0(w[15]) + w[14] + 0xC9DC06A7.toInt() - round[14]
        expand(w,31)
        val first = rounds(preState,w,14)
        var state = IntArray(8) { initial[it] + first[it] }
        val length = 57 + data.size
        val padded = data.copyOfRange(7,data.size) + byteArrayOf(0x80.toByte()) + ByteArray(Math.floorMod(55-length,64)) + ByteBuffer.allocate(8).putLong(length.toLong()*8).array()
        for (offset in padded.indices step 64) {
            val words = IntArray(64)
            val buffer = ByteBuffer.wrap(padded,offset,64)
            for (i in 0..15) words[i] = buffer.int
            expand(words,16)
            val next = rounds(state,words,0)
            state = IntArray(8) { state[it] + next[it] }
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }
    fun generate(unixSeconds: Long): String {
        val ticks = ((unixSeconds + 11644473600L) / 300L) * 300L * 10_000_000L
        val data = "$ticks$CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = digest(data)
        val keys = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = keys.int or 1; val keyB = keys.int or 1
        val factors = intArrayOf(keyA * 0x82893933.toInt(), keyB * 0x82893933.toInt())
        val terms = coefficients.mapIndexed { i, v -> v * factors[i%2] }.toMutableList()
        val offsets = baseOffsets.toMutableList()
        val paddedSize = ((1 + data.size + 7) / 8) * 8
        val dataWords = ByteBuffer.wrap((byteArrayOf(0) + data).copyOf(paddedSize)).order(ByteOrder.LITTLE_ENDIAN)
        var index = 0
        while (dataWords.remaining() >= 4) {
            val word = dataWords.int
            var term = word * (if (index%2 == 0) keyA else keyB)
            var offset = rol(word) * (if (index%2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (index == 0) { term += 0xF2F90B1A.toInt()*factors[0]; offset += 0x0B1A0000 }
            terms += term; offsets += offset; index++
        }
        var swapAcc=0; var swapLast=0; var state=0; var acc=0
        for (i in terms.indices step 2) {
            val x=terms[i]; val y=terms[i+1]
            var q = rol(offsets[i]+x)*0x76F50F34
            swapAcc += rol(q)*0xC3EFEA97.toInt()+q
            q = rol(offsets[i+1]+y)*0x1881CE3C
            swapLast = rol(q)*0x35BD1EC9+q; swapAcc += swapLast
            state = state*keyA+x
            state = rol(state)*0xCF98B111.toInt(); state=rol(state)*0x87085B9F.toInt()
            state=rol(state)*0x12CEB96D; state=rol(state)*0x257E1D83
            acc += state
            var t = state*keyB+y
            t=rol(t)*0xA27416F5.toInt(); t=rol(t)*0xD38396FF.toInt()
            state=rol(rol(t)*0x7C932B89)*0xBFA49F69.toInt(); acc += state
        }
        fun ByteArray.hex() = joinToString("") { "%02X".format(it.toInt() and 255) }
        val cs = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(swapLast xor state).putInt(swapAcc xor acc).array()
        return "${cs.hex()}-${digest.hex()}"
    }
}
