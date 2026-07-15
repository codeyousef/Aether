package codes.yousef.aether.auth.saml

import codes.yousef.aether.auth.AuthenticationAssurance
import codes.yousef.aether.auth.AuditRequestMetadata
import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.ExternalIdentityId
import codes.yousef.aether.auth.ExternalSubject
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.IdentityHttpMethod
import codes.yousef.aether.auth.IdentityHttpRequest
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.P256PublicKey
import codes.yousef.aether.auth.RsaPublicKey
import codes.yousef.aether.auth.SessionAuthenticationMethod
import codes.yousef.aether.auth.UserId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

private val PROVIDER_ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,62}")
private val KEY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,254}")

/** Which SAML object must carry a valid signature. */
@Serializable
enum class SamlSignaturePolicy {
    @SerialName("assertion_or_response") ASSERTION_OR_RESPONSE,
    @SerialName("assertion") ASSERTION,
    @SerialName("response") RESPONSE,
    @SerialName("both") BOTH
}

@Serializable
enum class SamlSignatureAlgorithm(val uri: String) {
    @SerialName("rsa_sha256")
    RSA_SHA256("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"),

    @SerialName("ecdsa_sha256")
    ECDSA_SHA256("http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256")
}

/** A metadata key already decoded to the platform-neutral identity crypto boundary. */
sealed interface SamlVerificationKey {
    val keyId: String
    val validFrom: Instant?
    val validUntil: Instant?

    class Rsa(
        override val keyId: String,
        val publicKey: RsaPublicKey,
        override val validFrom: Instant? = null,
        override val validUntil: Instant? = null
    ) : SamlVerificationKey {
        init { validateKeyWindow(keyId, validFrom, validUntil) }
        override fun toString(): String = "SamlVerificationKey.Rsa(keyId=$keyId, publicKey=<redacted>)"
    }

    class Es256(
        override val keyId: String,
        val publicKey: P256PublicKey,
        override val validFrom: Instant? = null,
        override val validUntil: Instant? = null
    ) : SamlVerificationKey {
        init { validateKeyWindow(keyId, validFrom, validUntil) }
        override fun toString(): String = "SamlVerificationKey.Es256(keyId=$keyId, publicKey=<redacted>)"
    }
}

private fun validateKeyWindow(keyId: String, validFrom: Instant?, validUntil: Instant?) {
    require(KEY_ID_PATTERN.matches(keyId)) { "Invalid SAML metadata key ID" }
    require(validFrom == null || validUntil == null || validUntil > validFrom) {
        "SAML metadata key validity window is empty"
    }
}

/**
 * Immutable, verified IdP metadata snapshot. A resolver may return overlapping old/new keys during
 * rotation. Response KeyInfo is only a hint; it can never introduce a verification key.
 */
class SamlProviderMetadata(
    val entityId: String,
    val redirectSsoUrl: String,
    verificationKeys: List<SamlVerificationKey>,
    val version: String,
    val validUntil: Instant,
    val wantAuthnRequestsSigned: Boolean = false
) {
    val verificationKeys: List<SamlVerificationKey> = verificationKeys.toList()

    init {
        require(entityId.isNotBlank() && entityId.length <= 2_048) { "Invalid SAML metadata entity ID" }
        require(version.isNotBlank() && version.length <= 255) { "Invalid SAML metadata version" }
        require(this.verificationKeys.isNotEmpty() && this.verificationKeys.size <= 32) {
            "SAML metadata must contain 1..32 signing keys"
        }
        require(this.verificationKeys.map { it.keyId }.toSet().size == this.verificationKeys.size) {
            "SAML metadata key IDs must be unique"
        }
        requireSafeHttpsOrLoopbackUrl(redirectSsoUrl)
    }

    override fun toString(): String =
        "SamlProviderMetadata(entityId=$entityId, redirectSsoUrl=<redacted>, verificationKeys=<redacted>, " +
            "version=$version, validUntil=$validUntil, wantAuthnRequestsSigned=$wantAuthnRequestsSigned)"
}

/** Resolve a fresh/cached metadata snapshot. Implementations own fetching, pinning, and rotation. */
fun interface SamlMetadataResolver {
    suspend fun resolve(): SamlProviderMetadata
}

class StaticSamlMetadataResolver(private val metadata: SamlProviderMetadata) : SamlMetadataResolver {
    override suspend fun resolve(): SamlProviderMetadata = metadata
}

/** Optional secret-owning signer for IdPs that require signed HTTP-Redirect AuthnRequests. */
interface SamlRedirectSigner {
    val algorithm: SamlSignatureAlgorithm
    val keyId: String
    suspend fun sign(queryBytes: ByteArray): ByteArray
}

/** Immutable configuration for one tenant-scoped SAML identity provider. */
class SamlProviderConfig(
    val tenantId: OrganizationId,
    val providerId: String,
    val spEntityId: String,
    val idpEntityId: String,
    val assertionConsumerServiceUrl: String,
    val enabled: Boolean = true,
    val jitProvisioningEnabled: Boolean = false,
    val signaturePolicy: SamlSignaturePolicy = SamlSignaturePolicy.ASSERTION_OR_RESPONSE,
    allowedNameIdFormats: Set<String> = setOf(
        "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent",
        "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified"
    ),
    val requestLifetime: Duration = 5.minutes,
    val clockSkew: Duration = 30.seconds,
    val maximumAssertionLifetime: Duration = 10.minutes,
    val replayReceiptLifetime: Duration = 1.hours,
    val maximumEncodedResponseBytes: Int = 2_097_152,
    val maximumXmlBytes: Int = 1_048_576,
    val maximumXmlDepth: Int = 25,
    val maximumElements: Int = 2_048,
    val maximumAttributesPerElement: Int = 30,
    val maximumTextCharacters: Int = 262_144
) {
    val allowedNameIdFormats: Set<String> = allowedNameIdFormats.toSet()

    init {
        require(PROVIDER_ID_PATTERN.matches(providerId)) { "Invalid SAML provider ID" }
        require(spEntityId.isNotBlank() && spEntityId.length <= 2_048) { "Invalid SAML SP entity ID" }
        require(idpEntityId.isNotBlank() && idpEntityId.length <= 2_048) { "Invalid SAML IdP entity ID" }
        requireSafeHttpsOrLoopbackUrl(assertionConsumerServiceUrl)
        require(this.allowedNameIdFormats.isNotEmpty() && this.allowedNameIdFormats.size <= 16 &&
            this.allowedNameIdFormats.all { it.isNotBlank() && it.length <= 512 }) {
            "Invalid SAML NameID format allowlist"
        }
        require(requestLifetime.isPositive() && requestLifetime <= 15.minutes) {
            "SAML request lifetime must be in 1ns..15m"
        }
        require(!clockSkew.isNegative() && clockSkew <= 5.minutes) { "SAML clock skew must be in 0s..5m" }
        require(maximumAssertionLifetime.isPositive() && maximumAssertionLifetime <= 24.hours)
        require(replayReceiptLifetime.isPositive() && replayReceiptLifetime <= 24.hours)
        require(maximumEncodedResponseBytes in 4_096..4_194_304)
        require(maximumXmlBytes in 4_096..2_097_152)
        // These hard ceilings intentionally match the May 2026 OpenSAML parser-hardening
        // defaults. They are maximums, not merely defaults: a tenant configuration must not be
        // able to weaken the authority-wide unauthenticated XML resource limits.
        require(maximumXmlDepth in 8..25)
        require(maximumElements in 64..10_000)
        require(maximumAttributesPerElement in 8..30)
        require(maximumTextCharacters in 4_096..1_048_576)
    }

    override fun toString(): String =
        "SamlProviderConfig(tenantId=$tenantId, providerId=$providerId, spEntityId=$spEntityId, " +
            "idpEntityId=$idpEntityId, assertionConsumerServiceUrl=<redacted>, enabled=$enabled, " +
            "jitProvisioningEnabled=$jitProvisioningEnabled, signaturePolicy=$signaturePolicy)"
}

@Serializable
enum class SamlErrorCode {
    @SerialName("provider_disabled") PROVIDER_DISABLED,
    @SerialName("provider_metadata_invalid") PROVIDER_METADATA_INVALID,
    @SerialName("request_invalid") REQUEST_INVALID,
    @SerialName("request_expired") REQUEST_EXPIRED,
    @SerialName("response_invalid") RESPONSE_INVALID,
    @SerialName("signature_invalid") SIGNATURE_INVALID,
    @SerialName("assertion_replayed") ASSERTION_REPLAYED,
    @SerialName("external_identity_not_linked") EXTERNAL_IDENTITY_NOT_LINKED,
    @SerialName("external_identity_conflict") EXTERNAL_IDENTITY_CONFLICT,
    @SerialName("provisioning_failed") PROVISIONING_FAILED,
    @SerialName("store_unavailable") STORE_UNAVAILABLE
}

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class SamlError(
    val code: SamlErrorCode,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val message: String = code.genericMessage,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val retryable: Boolean = code.defaultRetryable
) {
    init {
        require(message == code.genericMessage) { "SAML errors must use the stable generic message" }
        require(retryable == code.defaultRetryable) { "SAML retryability is fixed by error code" }
    }
}

sealed interface SamlResult<out T> {
    data class Success<T>(val value: T) : SamlResult<T>
    data class Failure(val error: SamlError) : SamlResult<Nothing>

    fun valueOrNull(): T? = (this as? Success<T>)?.value
}

internal val SamlErrorCode.genericMessage: String
    get() = when (this) {
        SamlErrorCode.PROVIDER_DISABLED -> "The identity provider is unavailable."
        SamlErrorCode.PROVIDER_METADATA_INVALID -> "The identity provider configuration is invalid."
        SamlErrorCode.REQUEST_INVALID -> "The identity request is invalid or has already been used."
        SamlErrorCode.REQUEST_EXPIRED -> "The identity request has expired."
        SamlErrorCode.RESPONSE_INVALID -> "The identity response is invalid."
        SamlErrorCode.SIGNATURE_INVALID -> "The identity response signature is invalid."
        SamlErrorCode.ASSERTION_REPLAYED -> "The identity response has already been used."
        SamlErrorCode.EXTERNAL_IDENTITY_NOT_LINKED -> "The external identity is not linked."
        SamlErrorCode.EXTERNAL_IDENTITY_CONFLICT -> "The external identity cannot be linked."
        SamlErrorCode.PROVISIONING_FAILED -> "The external identity could not be provisioned."
        SamlErrorCode.STORE_UNAVAILABLE -> "The identity service is temporarily unavailable."
    }

internal val SamlErrorCode.defaultRetryable: Boolean
    get() = this == SamlErrorCode.STORE_UNAVAILABLE

/** Narrow provider surface consumed by the common-code HTTP middleware. */
interface SamlFederationProvider {
    val configuredTenantId: OrganizationId
    val configuredProviderId: String

    suspend fun beginAuthentication(
        request: SamlAuthenticationRequest = SamlAuthenticationRequest()
    ): SamlResult<SamlAuthenticationStart>
    suspend fun completeAuthentication(request: SamlPostResponseRequest): SamlResult<SamlAuthenticationResult>
}

class SamlAuthenticationRequest(
    val linkToUserId: UserId? = null
)

/** Caller-held request correlation. Keep it in an integrity-protected server-side session/cookie. */
class SamlAuthenticationState internal constructor(
    internal val challengeId: ChallengeId,
    internal val requestId: String,
    relayState: ByteArray,
    internal val linkToUserId: UserId?,
    val providerLease: FederationProviderLease,
    val expiresAt: Instant
) {
    private val relayStateValue = relayState.copyOf()

    init {
        require(providerLease.kind == FederationProviderKind.SAML) {
            "SAML authentication state requires a SAML provider lease"
        }
    }

    internal fun relayStateBytes(): ByteArray = relayStateValue.copyOf()
    internal fun destroy() { relayStateValue.fill(0) }
    override fun toString(): String =
        "SamlAuthenticationState(challengeId=<redacted>, requestId=<redacted>, relayState=<redacted>, expiresAt=$expiresAt)"
}

class SamlAuthenticationStart internal constructor(
    val redirectUrl: String,
    val state: SamlAuthenticationState,
    val expiresAt: Instant
) {
    override fun toString(): String = "SamlAuthenticationStart(redirectUrl=<redacted>, state=<redacted>, expiresAt=$expiresAt)"
}

class SamlPostResponseRequest(
    val samlResponse: String,
    val relayState: String,
    val state: SamlAuthenticationState,
    val auditRequest: AuditRequestMetadata? = null
) {
    init {
        require(samlResponse.isNotEmpty()) { "SAMLResponse must not be empty" }
        require(relayState.length in 16..80 && relayState.none(Char::isWhitespace)) { "Invalid RelayState" }
    }

    override fun toString(): String =
        "SamlPostResponseRequest(samlResponse=<redacted>, relayState=<redacted>, state=<redacted>, auditRequest=$auditRequest)"
}

data class SamlVerifiedClaims(
    val issuer: String,
    val subject: ExternalSubject,
    val nameIdFormat: String,
    val issuedAt: Instant,
    val authenticatedAt: Instant,
    val expiresAt: Instant,
    val sessionIndex: String?,
    val authenticationContext: String?,
    val attributes: Map<String, List<String>>
) {
    override fun toString(): String =
        "SamlVerifiedClaims(issuer=$issuer, subject=<redacted>, nameIdFormat=$nameIdFormat, issuedAt=$issuedAt, " +
            "authenticatedAt=$authenticatedAt, expiresAt=$expiresAt, sessionIndex=<redacted>, " +
            "authenticationContext=<redacted>, attributes=<redacted>)"
}

data class SamlAuthenticationResult(
    val userId: UserId,
    val externalIdentityId: ExternalIdentityId,
    val providerLease: FederationProviderLease,
    val assurance: AuthenticationAssurance = AuthenticationAssurance.SESSION,
    val authenticationMethod: SessionAuthenticationMethod = SessionAuthenticationMethod.SAML,
    val passkeyStepUpRequiredForSensitiveActions: Boolean = true,
    val claims: SamlVerifiedClaims
) {
    init {
        require(providerLease.kind == FederationProviderKind.SAML &&
            assurance == AuthenticationAssurance.SESSION &&
            authenticationMethod == SessionAuthenticationMethod.SAML &&
            passkeyStepUpRequiredForSensitiveActions
        ) { "SAML authentication results require a SAML session lease and passkey step-up" }
    }
}

private fun requireSafeHttpsOrLoopbackUrl(value: String) {
    require(value.length in 8..4_096 && '#' !in value) { "Invalid SAML endpoint URL" }
    IdentityHttpRequest(IdentityHttpMethod.GET, value)
}
