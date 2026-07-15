package codes.yousef.aether.auth

import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DigestAlgorithm {
    @SerialName("sha256") SHA256,
    @SerialName("hmac_sha256") HMAC_SHA256
}

/**
 * Persisted digest of a credential secret. The encoded value is available to storage adapters,
 * but is deliberately redacted from string representations and structured errors.
 */
@Serializable
data class SecretDigest(
    val algorithm: DigestAlgorithm,
    val encoded: String,
    val keyVersion: String? = null
) {
    init {
        require(encoded.isNotBlank()) { "Secret digest must not be blank" }
        require(encoded.length <= 1024) { "Secret digest is too long" }
        require(encoded.none(Char::isWhitespace)) { "Secret digest must not contain whitespace" }
        require(keyVersion == null || keyVersion.isNotBlank()) { "Key version must not be blank" }
    }

    override fun toString(): String = "SecretDigest(algorithm=$algorithm, encoded=<redacted>, keyVersion=${keyVersion ?: "none"})"
}

/** Reference resolved by [IdentitySecretResolver]; it never contains key material. */
@Serializable
data class SecretReference(
    val provider: String,
    val name: String,
    val version: String,
    val environment: IdentityEnvironment
) {
    init {
        require(provider.isNotBlank()) { "Secret provider must not be blank" }
        require(name.isNotBlank()) { "Secret name must not be blank" }
        require(version.isNotBlank()) { "Secret version must not be blank" }
    }

    override fun toString(): String =
        "SecretReference(provider=$provider, name=<redacted>, version=$version, environment=${environment.wireName})"
}

/** Canonical unpadded-base64url COSE public-key bytes. */
@Serializable
@JvmInline
value class CosePublicKey(val encoded: String) {
    init {
        require(encoded.isNotBlank()) { "COSE public key must not be blank" }
        require(encoded.none(Char::isWhitespace)) { "COSE public key must not contain whitespace" }
    }

    override fun toString(): String = "<cose-public-key>"
}

/** Authenticator-issued WebAuthn credential identifier encoded as strict unpadded base64url. */
@Serializable
@JvmInline
value class WebAuthnCredentialId(val encoded: String) {
    init {
        require(encoded.isNotEmpty()) { "WebAuthn credential ID must not be empty" }
        require(Base64Url.decode(encoded, maximumBytes = 1_024).isNotEmpty()) {
            "WebAuthn credential ID must contain at least one byte"
        }
    }

    override fun toString(): String = "<webauthn-credential-id>"
}

/** Normalized email value which is redacted from logs by default. */
@Serializable
@JvmInline
value class EmailAddress(val value: String) {
    init {
        require(value.length in 3..320 && '@' in value) { "Invalid email address" }
        require(value.none(Char::isWhitespace)) { "Email address must not contain whitespace" }
    }

    override fun toString(): String = "<email>"
}

/** External provider subject, treated as personally identifying information. */
@Serializable
@JvmInline
value class ExternalSubject(val value: String) {
    init {
        require(value.isNotBlank()) { "External subject must not be blank" }
        require(value.length <= 1024) { "External subject is too long" }
    }

    override fun toString(): String = "<external-subject>"
}

/** One-way pseudonymous request attribute, such as a keyed client-IP digest. */
@Serializable
@JvmInline
value class PseudonymousValue(val value: String) {
    init {
        require(value.isNotBlank()) { "Pseudonymous value must not be blank" }
        require(value.length <= 512) { "Pseudonymous value is too long" }
    }
    override fun toString(): String = "<pseudonymous>"
}

/**
 * Runtime-only secret bytes. Callers receive a temporary copy which is cleared after use.
 * This type is intentionally neither serializable nor a data class.
 */
class IdentitySecret private constructor(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    init {
        require(value.isNotEmpty()) { "Secret must not be empty" }
    }

    suspend fun <T> useBytes(block: suspend (ByteArray) -> T): T {
        val copy = value.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    override fun toString(): String = "IdentitySecret(<redacted>)"

    companion object {
        fun fromBytes(bytes: ByteArray): IdentitySecret = IdentitySecret(bytes)

        fun fromUtf8(value: String): IdentitySecret = IdentitySecret(value.encodeToByteArray())
    }
}
