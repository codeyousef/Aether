package codes.yousef.aether.auth

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock

/** Production JVM clock backed by the platform wall clock. */
object JvmIdentityClock : IdentityClock {
    override fun now() = Clock.System.now()
}

/** Production JVM CSPRNG backed by [SecureRandom]. */
class JvmIdentitySecureRandom(
    private val delegate: SecureRandom = SecureRandom()
) : IdentitySecureRandom {
    override fun nextBytes(size: Int): ByteArray {
        require(size in 1..1_048_576) { "Random byte request must be between 1 byte and 1 MiB" }
        return ByteArray(size).also(delegate::nextBytes)
    }
}

/** JCA implementation of the identity cryptography boundary. */
class JvmIdentityCrypto : IdentityCrypto {
    override val capabilities: Set<IdentityCryptoCapability> = IdentityCryptoCapability.entries.toSet()

    override suspend fun providerSelfTest(): Boolean = runCatching {
        MessageDigest.getInstance("SHA-256")
        Mac.getInstance("HmacSHA256")
        Signature.getInstance("SHA256withECDSA")
        Signature.getInstance("SHA256withRSA")
        KeyFactory.getInstance("EC")
        KeyFactory.getInstance("RSA")
        true
    }.getOrDefault(false)

    override suspend fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    override suspend fun hmacSha256(key: IdentitySecret, input: ByteArray): ByteArray =
        key.useBytes { bytes ->
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(bytes, "HmacSHA256"))
            mac.doFinal(input)
        }

    override suspend fun validateP256PublicKey(publicKey: P256PublicKey): Boolean = runCatching {
        ecPublicKey(publicKey)
        true
    }.getOrDefault(false)

    override suspend fun verifyEs256(
        publicKey: P256PublicKey,
        signedData: ByteArray,
        signature: Es256Signature
    ): Boolean = runCatching {
        val key = ecPublicKey(publicKey)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(signedData)
            verify(rawEs256ToDer(signature.copyBytes()))
        }
    }.getOrDefault(false)

    override suspend fun verifyRsaSha256(
        publicKey: RsaPublicKey,
        signedData: ByteArray,
        signature: RsaSha256Signature
    ): Boolean = runCatching {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey.copyBytes()))
        val rsa = key as java.security.interfaces.RSAPublicKey
        require(rsa.modulus.bitLength() >= 2_048) { "RSA keys smaller than 2048 bits are rejected" }
        Signature.getInstance("SHA256withRSA").run {
            initVerify(rsa)
            update(signedData)
            verify(signature.copyBytes())
        }
    }.getOrDefault(false)

    override suspend fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
        MessageDigest.isEqual(left, right)
}

private fun ecPublicKey(publicKey: P256PublicKey): java.security.PublicKey {
    val keyBytes = publicKey.copyBytes()
    val parameters = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
    val x = BigInteger(1, keyBytes.copyOfRange(1, 33))
    val y = BigInteger(1, keyBytes.copyOfRange(33, 65))
    val prime = (parameters.curve.field as? java.security.spec.ECFieldFp)?.p
        ?: throw IllegalArgumentException("P-256 provider did not expose a prime field")
    require(x.signum() >= 0 && x < prime && y.signum() >= 0 && y < prime) {
        "P-256 point coordinates are outside the provider curve field"
    }
    val left = y.modPow(BigInteger.TWO, prime)
    val right = x.modPow(BigInteger.valueOf(3), prime)
        .add(parameters.curve.a.multiply(x))
        .add(parameters.curve.b)
        .mod(prime)
    require(left == right) { "P-256 point is not on the provider curve" }
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), parameters))
}

/** Minimal JVM HTTP capability. Redirects are not followed to avoid credential forwarding. */
class JvmIdentityHttpClient(
    private val delegate: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
) : IdentityHttpClient {
    override suspend fun providerSelfTest(): Boolean = runCatching {
        delegate.followRedirects() == HttpClient.Redirect.NEVER
    }.getOrDefault(false)

    override suspend fun execute(request: IdentityHttpRequest): IdentityHttpResponse {
        val body = request.bodyBytes()
        val builder = HttpRequest.newBuilder(URI.create(request.url))
        request.headers.forEach(builder::header)
        when (request.method) {
            IdentityHttpMethod.GET -> builder.GET()
            IdentityHttpMethod.DELETE -> builder.DELETE()
            IdentityHttpMethod.POST -> builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))
            IdentityHttpMethod.PUT -> builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body))
            IdentityHttpMethod.PATCH -> builder.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body))
        }
        val response = delegate.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val responseBody = response.body().use { input ->
            val output = ByteArrayOutputStream(minOf(request.maximumResponseBytes, 8_192))
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (total > request.maximumResponseBytes - read) {
                    throw IllegalStateException("Identity HTTP response exceeded its configured limit")
                }
                output.write(buffer, 0, read)
                total += read
            }
            output.toByteArray()
        }
        return IdentityHttpResponse(
            statusCode = response.statusCode(),
            headers = response.headers().map().mapValues { (_, values) -> values.joinToString(",") },
            body = responseBody
        )
    }
}

/** Creates the production JVM runtime with caller-owned secret resolution. */
fun jvmIdentityRuntime(
    secrets: IdentitySecretResolver,
    http: IdentityHttpClient = JvmIdentityHttpClient()
): IdentityRuntime = IdentityRuntime(
    clock = JvmIdentityClock,
    secureRandom = JvmIdentitySecureRandom(),
    crypto = JvmIdentityCrypto(),
    http = http,
    secrets = secrets
)

private fun rawEs256ToDer(raw: ByteArray): ByteArray {
    require(raw.size == 64) { "ES256 signature must contain 32-byte R and S values" }
    val r = unsignedDerInteger(raw.copyOfRange(0, 32))
    val s = unsignedDerInteger(raw.copyOfRange(32, 64))
    val sequenceLength = 2 + r.size + 2 + s.size
    require(sequenceLength < 128) { "Unexpected ES256 DER length" }
    return byteArrayOf(0x30, sequenceLength.toByte(), 0x02, r.size.toByte()) +
        r + byteArrayOf(0x02, s.size.toByte()) + s
}

private fun unsignedDerInteger(input: ByteArray): ByteArray {
    var first = 0
    while (first < input.lastIndex && input[first] == 0.toByte()) first++
    val magnitude = input.copyOfRange(first, input.size)
    return if (magnitude[0].toInt() and 0x80 != 0) byteArrayOf(0) + magnitude else magnitude
}
