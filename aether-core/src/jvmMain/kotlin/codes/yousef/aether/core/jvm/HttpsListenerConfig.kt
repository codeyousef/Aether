package codes.yousef.aether.core.jvm

import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.PemKeyCertOptions
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Safe startup failure; provider exceptions and runtime file paths are deliberately omitted. */
class HttpsListenerException : IllegalStateException("HTTPS listener initialization failed")

/**
 * Opt-in server-authenticated TLS with runtime PEM files. The leaf certificate comes first and
 * the key must be unencrypted PKCS#8 RSA or EC. Material is bounded to 1 MiB per file and snapshotted
 * at start; rotation requires restarting the listener. Only TLS 1.2/1.3 are enabled. This does not
 * request or authenticate client certificates. Keep runtime paths and material out of source control.
 */
class HttpsListenerConfig(
    private val certificateChainPath: String,
    private val privateKeyPath: String,
    val minimumProtocol: HttpsMinimumProtocol = HttpsMinimumProtocol.TLS_1_2
) {
    override fun toString(): String = "HttpsListenerConfig(minimumProtocol=$minimumProtocol)"

    internal fun applyTo(options: HttpServerOptions) {
        try {
            val certificateBytes = readBounded(certificateChainPath)
            val keyBytes = readBounded(privateKeyPath)
            try {
                val certificates = CertificateFactory.getInstance("X.509")
                    .generateCertificates(certificateBytes.inputStream()).map { it as X509Certificate }
                require(certificates.isNotEmpty())
                certificates.forEach { it.checkValidity() }
                val leaf = certificates.first()
                val algorithm = leaf.publicKey.algorithm
                require(algorithm == "RSA" || algorithm == "EC")
                val pem = keyBytes.toString(Charsets.US_ASCII).trim()
                require(pem.startsWith("-----BEGIN PRIVATE KEY-----") && pem.endsWith("-----END PRIVATE KEY-----"))
                val encoded = Base64.getMimeDecoder().decode(pem.removePrefix("-----BEGIN PRIVATE KEY-----")
                    .removeSuffix("-----END PRIVATE KEY-----"))
                try {
                    val key = KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(encoded))
                    val signatureAlgorithm = if (algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA"
                    val probe = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    val signed = Signature.getInstance(signatureAlgorithm).run { initSign(key); update(probe); sign() }
                    require(Signature.getInstance(signatureAlgorithm).run {
                        initVerify(leaf.publicKey); update(probe); verify(signed)
                    })
                } finally { encoded.fill(0) }
                options.setSsl(true)
                    .setEnabledSecureTransportProtocols(if (minimumProtocol == HttpsMinimumProtocol.TLS_1_3)
                        setOf("TLSv1.3") else setOf("TLSv1.2", "TLSv1.3"))
                    .setKeyCertOptions(PemKeyCertOptions().setCertValue(Buffer.buffer(certificateBytes))
                        .setKeyValue(Buffer.buffer(keyBytes)))
            } finally { keyBytes.fill(0) }
        } catch (_: Exception) {
            throw HttpsListenerException()
        }
    }

    private fun readBounded(path: String): ByteArray = Files.newInputStream(Path.of(path)).use {
        val bytes = it.readNBytes(1_048_577)
        require(bytes.isNotEmpty() && bytes.size <= 1_048_576)
        bytes
    }
}

enum class HttpsMinimumProtocol { TLS_1_2, TLS_1_3 }
