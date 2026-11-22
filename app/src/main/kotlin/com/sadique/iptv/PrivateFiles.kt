package com.sadique.iptv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Migrate a file in place only after verifying the encrypted payload. */
internal object PrivateFiles {
    private val prefix = byteArrayOf(0, 73, 80, 1)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey("iptv-private-v1", null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("iptv-private-v1",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }

    private fun decrypt(bytes: ByteArray): ByteArray {
        if (bytes.size < 32) throw IOException("Incomplete saved file.")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
            doFinal(bytes, 16, bytes.size - 16)
        }
    }

    @Synchronized fun read(file: File): String {
        val bytes = AtomicFile(file).readFully()
        if (!bytes.take(4).toByteArray().contentEquals(prefix)) {
            return bytes.toString(Charsets.UTF_8).also { write(file, it) }
        }
        try {
            return decrypt(bytes).toString(Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            throw IOException("Saved file could not be unlocked.", error)
        }
    }

    @Synchronized fun write(file: File, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val encrypted = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val payload = prefix + cipher.iv + cipher.doFinal(bytes)
            if (!decrypt(payload).contentEquals(bytes)) throw IOException("Storage check failed.")
            payload
        } catch (error: GeneralSecurityException) {
            throw IOException("Secure storage unavailable.", error)
        }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(encrypted)
            atomic.finishWrite(output)
        } catch (error: IOException) {
            atomic.failWrite(output)
            throw error
        }
    }
}
