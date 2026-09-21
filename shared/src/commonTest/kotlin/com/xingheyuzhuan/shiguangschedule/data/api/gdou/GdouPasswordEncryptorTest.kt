package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GdouPasswordEncryptorTest {
    @Test
    fun blankSaltReturnsPlainPassword() {
        assertEquals("secret", GdouPasswordEncryptor.encrypt("secret", ""))
        assertEquals("secret", GdouPasswordEncryptor.encrypt("secret", "   "))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun encryptsToDecodableBase64Block() {
        val cipher = GdouPasswordEncryptor.encrypt("password", "VZiVkGRaRIWMt7ea", Random(1))
        val bytes = Base64.decode(cipher)
        assertEquals(0, bytes.size % 16)
        assertTrue(bytes.size >= 80)
        val again = GdouPasswordEncryptor.encrypt("password", "VZiVkGRaRIWMt7ea", Random(2))
        assertTrue(cipher != again)
    }

    @Test
    fun randomStringUsesAllowedCharset() {
        val value = GdouPasswordEncryptor.randomString(32, Random(0))
        assertEquals(32, value.length)
        assertTrue(value.all { it in GdouPasswordEncryptor.CHARSET })
    }
}
