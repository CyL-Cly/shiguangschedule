package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * 对齐金智 CAS `encrypt.js` 的 `encryptPassword(pwd, pwdEncryptSalt)`：
 * 明文 = 随机 64 字符 + 密码；AES-CBC / PKCS7；key = salt；IV = 随机 16 字符（不随密文发送）。
 */
object GdouPasswordEncryptor {
    const val CHARSET = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    fun randomString(length: Int, random: Random = Random.Default): String {
        if (length <= 0) return ""
        val chars = CHARSET
        return buildString(length) {
            repeat(length) { append(chars[random.nextInt(chars.length)]) }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encrypt(password: String, salt: String, random: Random = Random.Default): String {
        if (salt.isBlank()) return password
        val key = salt.trim().encodeToByteArray()
        val iv = randomString(16, random).encodeToByteArray()
        val plain = (randomString(64, random) + password).encodeToByteArray()
        val cipher = AesCbcPkcs7.encrypt(plain, key, iv)
        return Base64.encode(cipher)
    }
}

/**
 * 纯 Kotlin AES-128-CBC + PKCS7，避免依赖平台 Cipher（iOS 无 SecureCrypto 实现）。
 */
internal object AesCbcPkcs7 {
    fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        require(iv.size == 16) { "IV must be 16 bytes" }
        val padded = pkcs7Pad(plain, 16)
        val aes = Aes128(key)
        val out = ByteArray(padded.size)
        val block = ByteArray(16)
        var prev = iv.copyOf()
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                block[i] = (padded[offset + i].toInt() xor prev[i].toInt()).toByte()
            }
            val encrypted = aes.encryptBlock(block)
            encrypted.copyInto(out, offset)
            prev = encrypted
            offset += 16
        }
        return out
    }

    private fun pkcs7Pad(data: ByteArray, blockSize: Int): ByteArray {
        val pad = blockSize - (data.size % blockSize)
        return data + ByteArray(pad) { pad.toByte() }
    }
}

internal class Aes128(key: ByteArray) {
    private val roundKeys: Array<IntArray> = expandKey(key)

    fun encryptBlock(input: ByteArray): ByteArray {
        val state = Array(4) { IntArray(4) }
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                state[r][c] = input[c * 4 + r].toInt() and 0xFF
            }
        }
        addRoundKey(state, 0)
        for (round in 1 until 10) {
            subBytes(state)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, round)
        }
        subBytes(state)
        shiftRows(state)
        addRoundKey(state, 10)
        val out = ByteArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                out[c * 4 + r] = state[r][c].toByte()
            }
        }
        return out
    }

    private fun addRoundKey(state: Array<IntArray>, round: Int) {
        val rk = roundKeys[round]
        for (c in 0 until 4) {
            val word = rk[c]
            state[0][c] = state[0][c] xor ((word ushr 24) and 0xFF)
            state[1][c] = state[1][c] xor ((word ushr 16) and 0xFF)
            state[2][c] = state[2][c] xor ((word ushr 8) and 0xFF)
            state[3][c] = state[3][c] xor (word and 0xFF)
        }
    }

    private fun subBytes(state: Array<IntArray>) {
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                state[r][c] = SBOX[state[r][c]]
            }
        }
    }

    private fun shiftRows(state: Array<IntArray>) {
        val t1 = intArrayOf(state[1][0], state[1][1], state[1][2], state[1][3])
        state[1][0] = t1[1]; state[1][1] = t1[2]; state[1][2] = t1[3]; state[1][3] = t1[0]
        val t2 = intArrayOf(state[2][0], state[2][1], state[2][2], state[2][3])
        state[2][0] = t2[2]; state[2][1] = t2[3]; state[2][2] = t2[0]; state[2][3] = t2[1]
        val t3 = intArrayOf(state[3][0], state[3][1], state[3][2], state[3][3])
        state[3][0] = t3[3]; state[3][1] = t3[0]; state[3][2] = t3[1]; state[3][3] = t3[2]
    }

    private fun mixColumns(state: Array<IntArray>) {
        for (c in 0 until 4) {
            val a0 = state[0][c]
            val a1 = state[1][c]
            val a2 = state[2][c]
            val a3 = state[3][c]
            state[0][c] = xtime(a0) xor a1 xor xtime(a1) xor a2 xor a3
            state[1][c] = a0 xor xtime(a1) xor a2 xor xtime(a2) xor a3
            state[2][c] = a0 xor a1 xor xtime(a2) xor a3 xor xtime(a3)
            state[3][c] = a0 xor xtime(a0) xor a1 xor a2 xor xtime(a3)
        }
    }

    private fun xtime(x: Int): Int {
        val shifted = (x shl 1) and 0xFF
        return if (x and 0x80 != 0) shifted xor 0x1B else shifted
    }

    private fun expandKey(key: ByteArray): Array<IntArray> {
        val w = IntArray(44)
        for (i in 0 until 4) {
            w[i] = ((key[4 * i].toInt() and 0xFF) shl 24) or
                ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((key[4 * i + 2].toInt() and 0xFF) shl 8) or
                (key[4 * i + 3].toInt() and 0xFF)
        }
        for (i in 4 until 44) {
            var temp = w[i - 1]
            if (i % 4 == 0) {
                temp = subWord(rotWord(temp)) xor (RCON[i / 4] shl 24)
            }
            w[i] = w[i - 4] xor temp
        }
        return Array(11) { round ->
            intArrayOf(w[round * 4], w[round * 4 + 1], w[round * 4 + 2], w[round * 4 + 3])
        }
    }

    private fun rotWord(word: Int): Int = (word shl 8) or (word ushr 24)

    private fun subWord(word: Int): Int {
        return (SBOX[(word ushr 24) and 0xFF] shl 24) or
            (SBOX[(word ushr 16) and 0xFF] shl 16) or
            (SBOX[(word ushr 8) and 0xFF] shl 8) or
            SBOX[word and 0xFF]
    }

    companion object {
        private val RCON = intArrayOf(0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36)
        private val SBOX = intArrayOf(
            0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
            0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
            0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
            0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
            0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
            0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
            0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
            0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
            0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
            0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
            0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
            0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
            0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
            0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
            0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
            0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
        )
    }
}
