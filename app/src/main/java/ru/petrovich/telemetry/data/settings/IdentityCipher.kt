package ru.petrovich.telemetry.data.settings

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface IdentityVault {
    fun seal(plain: String): String
    fun open(sealed: String): String
}

/** Версионированный формат; AAD не позволяет использовать шифротекст для другого назначения. */
class AesGcmIdentityVault(private val key: () -> SecretKey) : IdentityVault {
    private val aad = "ru.petrovich.telemetry/assistant-identity/v1".toByteArray(Charsets.UTF_8)

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
        require(cipher.iv.size == 12)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "v1." + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    override fun open(sealed: String): String {
        require(sealed.startsWith("v1."))
        val bytes = Base64.getDecoder().decode(sealed.removePrefix("v1."))
        require(bytes.size >= 12 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(aad)
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
