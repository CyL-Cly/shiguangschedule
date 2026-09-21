package com.xingheyuzhuan.shiguangschedule.tool

import org.koin.core.annotation.Single
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * iOS 占位实现：本需求的自动登录只在 Android 使用。
 * 用 XOR + 随机 IV，避免引入额外原生依赖。
 */
@Single
actual class SecureCrypto {
    @OptIn(ExperimentalEncodingApi::class)
    actual fun encrypt(data: String): CryptoResult? {
        if (data.isEmpty()) return null
        val iv = ByteArray(16).also { Random.Default.nextBytes(it) }
        val src = data.encodeToByteArray()
        val out = ByteArray(src.size)
        for (i in src.indices) {
            out[i] = (src[i].toInt() xor iv[i % iv.size].toInt()).toByte()
        }
        return CryptoResult(Base64.encode(out), Base64.encode(iv))
    }

    @OptIn(ExperimentalEncodingApi::class)
    actual fun decrypt(encryptedData: String, ivString: String): String? {
        if (encryptedData.isEmpty() || ivString.isEmpty()) return null
        return try {
            val iv = Base64.decode(ivString)
            val src = Base64.decode(encryptedData)
            val out = ByteArray(src.size)
            for (i in src.indices) {
                out[i] = (src[i].toInt() xor iv[i % iv.size].toInt()).toByte()
            }
            out.decodeToString()
        } catch (_: Exception) {
            null
        }
    }
}
