package com.aidexx.reader.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {
    private val magic = "AIDEXXB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val PBKDF2_ITERATIONS = 180_000
    private const val KEY_BITS = 256

    fun encryptFile(
        input: File,
        output: File,
        passphrase: CharArray
    ) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key,
            GCMParameterSpec(128, iv)
        )

        DataOutputStream(
            BufferedOutputStream(
                FileOutputStream(output)
            )
        ).use { headerOut ->
            headerOut.write(magic)
            headerOut.write(salt)
            headerOut.write(iv)
            headerOut.flush()

            CipherOutputStream(headerOut, cipher).use { cipherOut ->
                BufferedInputStream(FileInputStream(input)).use { inputStream ->
                    inputStream.copyTo(cipherOut)
                }
            }
        }
    }

    fun decryptFile(
        input: File,
        output: File,
        passphrase: CharArray
    ) {
        DataInputStream(
            BufferedInputStream(
                FileInputStream(input)
            )
        ).use { headerIn ->
            val foundMagic = ByteArray(magic.size)
            headerIn.readFully(foundMagic)
            require(foundMagic.contentEquals(magic)) {
                "This is not an GlucoTrail backup file."
            }

            val salt = ByteArray(SALT_BYTES)
            val iv = ByteArray(IV_BYTES)
            headerIn.readFully(salt)
            headerIn.readFully(iv)

            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, iv)
            )

            CipherInputStream(headerIn, cipher).use { cipherIn ->
                BufferedOutputStream(FileOutputStream(output)).use { out ->
                    cipherIn.copyTo(out)
                }
            }
        }
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray
    ): SecretKeySpec {
        val spec = PBEKeySpec(
            passphrase,
            salt,
            PBKDF2_ITERATIONS,
            KEY_BITS
        )
        val secret = SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
        spec.clearPassword()
        return SecretKeySpec(secret, "AES")
    }
}
