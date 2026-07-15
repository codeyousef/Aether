package codes.yousef.aether.auth

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun interface IdentityClock {
    fun now(): Instant
}

fun interface IdentitySecureRandom {
    /** Returns exactly [size] cryptographically secure random bytes. */
    fun nextBytes(size: Int): ByteArray
}

@Serializable
enum class IdentityCryptoCapability {
    @SerialName("sha256") SHA256,
    @SerialName("hmac_sha256") HMAC_SHA256,
    @SerialName("p256_public_key_validate") P256_PUBLIC_KEY_VALIDATE,
    @SerialName("es256_verify") ES256_VERIFY,
    @SerialName("rsa_sha256_verify") RSA_SHA256_VERIFY
}

/** Canonical uncompressed P-256 point: 0x04 followed by 32-byte X and Y coordinates. */
class P256PublicKey(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == 65 && value[0] == 0x04.toByte()) { "P-256 public key must be a canonical 65-byte point" }
    }

    fun copyBytes(): ByteArray = value.copyOf()
    override fun toString(): String = "P256PublicKey(<redacted>)"
}

/** Canonical P-256 ECDSA signature represented as fixed-width 32-byte R followed by 32-byte S. */
class Es256Signature(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init { require(value.size == 64) { "ES256 signature must be 64 bytes" } }

    fun copyBytes(): ByteArray = value.copyOf()
    override fun toString(): String = "Es256Signature(<redacted>)"
}

/** DER SubjectPublicKeyInfo containing an RSA key of at least 2048 bits. */
class RsaPublicKey(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init { require(value.size in 256..8_192) { "RSA public key must be bounded DER SubjectPublicKeyInfo" } }

    fun copyBytes(): ByteArray = value.copyOf()
    override fun toString(): String = "RsaPublicKey(<redacted>)"
}

class RsaSha256Signature(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init { require(value.size in 256..1_024) { "RSA-SHA256 signature has an invalid size" } }

    fun copyBytes(): ByteArray = value.copyOf()
    override fun toString(): String = "RsaSha256Signature(<redacted>)"
}

/**
 * Platform cryptography boundary. Implementations must return identical results on JVM, wasmJs,
 * and wasmWasi; protocol parsing and DER normalization belong in common code.
 */
interface IdentityCrypto {
    val capabilities: Set<IdentityCryptoCapability>

    suspend fun sha256(input: ByteArray): ByteArray

    suspend fun hmacSha256(key: IdentitySecret, input: ByteArray): ByteArray

    /** Uses the platform crypto provider to reject infinity, off-curve, and invalid P-256 points. */
    suspend fun validateP256PublicKey(publicKey: P256PublicKey): Boolean

    suspend fun verifyEs256(
        publicKey: P256PublicKey,
        signedData: ByteArray,
        signature: Es256Signature
    ): Boolean

    suspend fun verifyRsaSha256(
        publicKey: RsaPublicKey,
        signedData: ByteArray,
        signature: RsaSha256Signature
    ): Boolean

    suspend fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean

    /** Provider-owned checks (for example OpenSSL 3 FIPS/provider startup checks). */
    suspend fun providerSelfTest(): Boolean = false
}

@Serializable
enum class IdentityHttpMethod {
    @SerialName("GET") GET,
    @SerialName("POST") POST,
    @SerialName("PUT") PUT,
    @SerialName("PATCH") PATCH,
    @SerialName("DELETE") DELETE
}

class IdentityHttpRequest(
    val method: IdentityHttpMethod,
    val url: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0),
    val maximumResponseBytes: Int = DEFAULT_MAXIMUM_IDENTITY_HTTP_RESPONSE_BYTES
) {
    val headers: Map<String, String> = headers.toMap()
    private val bodyValue: ByteArray = body.copyOf()

    init {
        requireSafeIdentityHttpUrl(url)
        require(this.headers.size <= MAXIMUM_IDENTITY_HTTP_HEADERS) { "Too many identity HTTP headers" }
        require(this.headers.all { (name, value) ->
            IDENTITY_HTTP_HEADER_NAME.matches(name) &&
                value.length <= MAXIMUM_IDENTITY_HTTP_HEADER_VALUE_CHARS &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }
        }) { "Identity HTTP headers must be bounded and free of control delimiters" }
        require(bodyValue.size <= MAXIMUM_IDENTITY_HTTP_REQUEST_BYTES) {
            "Identity HTTP request body exceeded its configured hard limit"
        }
        require(maximumResponseBytes in 1..MAXIMUM_IDENTITY_HTTP_RESPONSE_BYTES) {
            "Identity HTTP response limit must be between 1 byte and 16 MiB"
        }
    }

    fun bodyBytes(): ByteArray = bodyValue.copyOf()

    override fun toString(): String =
        "IdentityHttpRequest(method=$method, url=$url, headers=<redacted>, body=<redacted>)"
}

const val DEFAULT_MAXIMUM_IDENTITY_HTTP_RESPONSE_BYTES: Int = 4 * 1_024 * 1_024
const val MAXIMUM_IDENTITY_HTTP_RESPONSE_BYTES: Int = 16 * 1_024 * 1_024
const val MAXIMUM_IDENTITY_HTTP_REQUEST_BYTES: Int = 16 * 1_024 * 1_024
private const val MAXIMUM_IDENTITY_HTTP_HEADERS: Int = 128
private const val MAXIMUM_IDENTITY_HTTP_HEADER_VALUE_CHARS: Int = 8_192
private val IDENTITY_HTTP_HEADER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,127}")

class IdentityHttpResponse(
    val statusCode: Int,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0)
) {
    val headers: Map<String, String> = headers.toMap()
    private val bodyValue: ByteArray = body.copyOf()

    init { require(statusCode in 100..599) { "Invalid HTTP status code" } }

    fun bodyBytes(): ByteArray = bodyValue.copyOf()

    override fun toString(): String =
        "IdentityHttpResponse(statusCode=$statusCode, headers=<redacted>, body=<redacted>)"
}

fun interface IdentityHttpClient {
    suspend fun execute(request: IdentityHttpRequest): IdentityHttpResponse

    /**
     * Performs a provider-local capability check without contacting an application endpoint.
     * The fail-closed default prevents an unverified injected client from serving production.
     */
    suspend fun providerSelfTest(): Boolean = false
}

fun interface IdentitySecretResolver {
    suspend fun resolve(reference: SecretReference): IdentitySecret
}

/** Runtime capabilities required by the storage-neutral identity service. */
class IdentityRuntime(
    val clock: IdentityClock,
    val secureRandom: IdentitySecureRandom,
    val crypto: IdentityCrypto,
    val http: IdentityHttpClient,
    val secrets: IdentitySecretResolver
) {
    init {
        val required = setOf(
            IdentityCryptoCapability.SHA256,
            IdentityCryptoCapability.HMAC_SHA256,
            IdentityCryptoCapability.P256_PUBLIC_KEY_VALIDATE,
            IdentityCryptoCapability.ES256_VERIFY,
            IdentityCryptoCapability.RSA_SHA256_VERIFY
        )
        require(crypto.capabilities.containsAll(required)) {
            "Identity crypto provider is missing required capabilities: ${required - crypto.capabilities}"
        }
    }

    override fun toString(): String =
        "IdentityRuntime(cryptoCapabilities=${crypto.capabilities}, providers=<redacted>)"
}

private fun requireSafeIdentityHttpUrl(url: String) {
    require(url.length in 1..8_192 && url == url.trim()) {
        "Identity HTTP URL must be bounded and must not contain surrounding whitespace"
    }
    require(url.none { it == '\r' || it == '\n' || it == '\u0000' }) {
        "Identity HTTP URL must not contain control delimiters"
    }
    val separator = url.indexOf("://")
    require(separator > 0) { "Identity HTTP URL must include a scheme" }
    val scheme = url.substring(0, separator).lowercase()
    require(scheme == "https" || scheme == "http") { "Identity HTTP URL must use HTTP or HTTPS" }

    val remainder = url.substring(separator + 3)
    val authority = remainder.substringBefore('/').substringBefore('?').substringBefore('#')
    require(authority.isNotBlank() && '@' !in authority) { "Identity HTTP URL must have an authority without user info" }
    val host = if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        require(end > 1) { "Invalid bracketed HTTP host" }
        val suffix = authority.substring(end + 1)
        require(suffix.isEmpty() || (suffix.startsWith(':') && suffix.drop(1).toIntOrNull() in 1..65535)) {
            "Invalid HTTP port"
        }
        authority.substring(1, end).lowercase()
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            require(authority.substring(colon + 1).toIntOrNull() in 1..65535) { "Invalid HTTP port" }
            authority.substring(0, colon).lowercase()
        } else {
            authority.lowercase()
        }
    }
    require(host.isNotBlank()) { "Identity HTTP URL host must not be blank" }
    if (scheme == "http") {
        require(host == "localhost" || host == "127.0.0.1" || host == "::1") {
            "Plain HTTP is allowed only for exact loopback hosts"
        }
    }
}
