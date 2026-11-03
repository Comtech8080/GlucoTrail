package com.aidexx.reader.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AidexCrypto {
    fun serialIv(serial: String): ByteArray {
        val mapped = mapSerial(serial)
        val transformed = ByteArray(mapped.size) { i ->
            (((mapped[i].toInt() and 0xFF) * 17 + 0x13) and 0xFF).toByte()
        }
        return md5(transformed)
    }

    fun keyRequest(serial: String): ByteArray {
        val mapped = mapSerial(serial)
        val transformed = ByteArray(mapped.size) { i ->
            (((mapped[i].toInt() and 0xFF) * 13 + 61) and 0xFF).toByte()
        }
        return md5(transformed)
    }

    fun aesCfbEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain)

    fun aesCfbDecrypt(key: ByteArray, iv: ByteArray, encrypted: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(encrypted)

    fun decodeSessionKey(key0: ByteArray, iv: ByteArray, encrypted17: ByteArray): ByteArray? {
        if (encrypted17.size != 17) return null
        val clear = aesCfbDecrypt(key0, iv, encrypted17)
        val key1 = clear.copyOfRange(0, 16)
        val expected = clear[16].toInt() and 0xFF
        return if (AidexCrc.crc8Maxim(key1) == expected) key1 else null
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        require(key.size == 16)
        require(iv.size == 16)
        return Cipher.getInstance("AES/CFB/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    private fun mapSerial(serial: String): ByteArray = ByteArray(serial.length) { index ->
        val c = serial[index]
        when (c) {
            in '0'..'9' -> (c.code - '0'.code).toByte()
            in 'A'..'Z' -> (c.code - 0x37).toByte()
            in 'a'..'z' -> (c.code - 0x57).toByte()
            else -> c.code.toByte()
        }
    }
}
