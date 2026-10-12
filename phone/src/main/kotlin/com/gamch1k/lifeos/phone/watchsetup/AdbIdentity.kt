package com.gamch1k.lifeos.phone.watchsetup

import android.content.Context
import com.gamch1k.lifeos.core.secrets.KeystoreCipher
import com.gamch1k.lifeos.core.secrets.SecretCipher
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/** The phone's ADB key and its self-signed certificate, which the watch trusts once paired. */
class AdbKey(val privateKey: PrivateKey, val certificate: X509Certificate)

/**
 * Keeps the phone's ADB identity: an RSA-2048 key pair (what adb itself
 * uses) and a self-signed certificate for the TLS of Wireless debugging. The
 * private key is stored only encrypted, with an AES-256-GCM key in the
 * Android Keystore ([KeystoreCipher]); the certificate is public. The same
 * key is used every time, so a watch that still trusts this phone connects
 * without a new pairing (a rescue). Backups are off, so it never leaves the
 * phone; if the Keystore key is lost, a new identity is made and the watch
 * needs a new pairing.
 */
class AdbIdentity(context: Context, private val cipher: SecretCipher = KeystoreCipher("lifeos-adb-key")) {
    private val dir = File(context.applicationContext.filesDir, "adb")
    private val keyFile = File(dir, "key.sealed")
    private val certificateFile = File(dir, "certificate.der")

    @Synchronized
    fun load(): AdbKey {
        read()?.let { return it }
        return create()
    }

    /** Whether a key exists already (an earlier pairing may still work). */
    val exists: Boolean get() = keyFile.isFile && certificateFile.isFile

    private fun read(): AdbKey? {
        if (!exists) return null
        return try {
            val pkcs8 = Base64.getDecoder().decode(cipher.decrypt(keyFile.readText()))
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val certificate = certificateFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) } as X509Certificate
            AdbKey(key, certificate)
        } catch (_: Exception) {
            // The Keystore key is gone or the files are damaged: a new identity (and a new pairing).
            null
        }
    }

    private fun create(): AdbKey {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
        val pair = generator.generateKeyPair()
        val name = X500Name("CN=Life OS phone")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(63, SecureRandom()),
            Date(now - DAY_MILLIS),
            // adbd checks only that the key was paired, not the validity; 30 years keeps it simple.
            Date(now + 30L * 365 * DAY_MILLIS),
            name,
            pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        dir.mkdirs()
        writeAtomically(certificateFile, certificate.encoded)
        writeAtomically(keyFile, cipher.encrypt(Base64.getEncoder().encodeToString(pair.private.encoded)).toByteArray(Charsets.UTF_8))
        return AdbKey(pair.private, certificate)
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IllegalStateException("could not store ${file.name}")
        }
    }

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
