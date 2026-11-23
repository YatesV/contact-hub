package com.yatesv.contacthub.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.yatesv.contacthub.data.HubLog
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

/**
 * HTTPS for the laptop link, so nobody else on the Wi-Fi can read your texts or the sign-in code.
 *
 * The key is made once and kept in the phone's secure hardware (Android Keystore) with a
 * self-signed certificate. Browsers warn about self-signed certificates the first time; the
 * fingerprint shown in Orbit lets you check it's really your phone.
 */
object WebTls {
    private const val ALIAS = "orbit-laptop-link"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey(ks: KeyStore) {
        if (ks.containsAlias(ALIAS)) return
        val from = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        val to = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }.time
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=Orbit laptop link"))
                .setCertificateSerialNumber(java.math.BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(from).setCertificateNotAfter(to)
                .build())
            generateKeyPair()
        }
    }

    /** TLS server sockets with the phone's key, or null if this phone can't do it (then the link stays HTTP). */
    fun serverSocketFactory(): SSLServerSocketFactory? = runCatching {
        val ks = keyStore()
        ensureKey(ks)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, null) }
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }.serverSocketFactory
    }.getOrElse { HubLog.w("WebLink", "HTTPS not available, using HTTP", it); null }

    /** SHA-256 of the certificate, as browsers show it ("AB:CD:…"), shortened for the screen. */
    fun fingerprint(): String = runCatching {
        val cert = keyStore().getCertificate(ALIAS) ?: return ""
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("")
}
