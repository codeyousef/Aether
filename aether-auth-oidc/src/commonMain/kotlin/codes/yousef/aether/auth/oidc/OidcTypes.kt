package codes.yousef.aether.auth.oidc

import codes.yousef.aether.auth.AuthenticationAssurance
import codes.yousef.aether.auth.AuditRequestMetadata
import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.aether.auth.ExternalIdentityId
import codes.yousef.aether.auth.ExternalSubject
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.SecretReference
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
private val SCOPE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}")

/** Immutable configuration for one tenant-scoped OpenID Provider. */
class OidcProviderConfig(
    val tenantId: OrganizationId,
    val providerId: String,
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
    scopes: Set<String> = setOf("openid", "profile", "email"),
    val clientSecretReference: SecretReference? = null,
    val enabled: Boolean = true,
    val jitProvisioningEnabled: Boolean = false,
    val transactionLifetime: Duration = 5.minutes,
    val clockSkew: Duration = 30.seconds,
    val maximumIdTokenLifetime: Duration = 24.hours,
    val discoveryCacheLifetime: Duration = 1.hours,
    val jwksCacheLifetime: Duration = 15.minutes,
    val maximumDiscoveryBytes: Int = 65_536,
    val maximumJwksBytes: Int = 262_144,
    val maximumTokenResponseBytes: Int = 65_536,
    val maximumIdTokenBytes: Int = 98_304,
    allowedEndpointOrigins: Set<String> = setOf(oidcEndpointOrigin(issuer))
) {
    val scopes: Set<String> = scopes.toSet()
    val allowedEndpointOrigins: Set<String> = allowedEndpointOrigins.toSet()

    init {
        require(PROVIDER_ID_PATTERN.matches(providerId)) { "Invalid OIDC provider ID" }
        require(clientId.isNotBlank() && clientId.length <= 512 && clientId.none(Char::isProtocolControl)) {
            "Invalid OIDC client ID"
        }
        requireCanonicalIssuer(issuer)
        requireSafeCallbackUri(redirectUri)
        require("openid" in this.scopes) { "OIDC scopes must include openid" }
        require(this.scopes.isNotEmpty() && this.scopes.size <= 32 && this.scopes.all(SCOPE_PATTERN::matches)) {
            "Invalid OIDC scope set"
        }
        require(transactionLifetime.isPositive() && transactionLifetime <= 15.minutes) {
            "OIDC transaction lifetime must be in 1ns..15m"
        }
        require(!clockSkew.isNegative() && clockSkew <= 5.minutes) { "OIDC clock skew must be in 0s..5m" }
        require(maximumIdTokenLifetime.isPositive() && maximumIdTokenLifetime <= 24.hours) {
            "Maximum ID-token lifetime must be in 1ns..24h"
        }
        require(discoveryCacheLifetime.isPositive() && discoveryCacheLifetime <= 24.hours)
        require(jwksCacheLifetime.isPositive() && jwksCacheLifetime <= 24.hours)
        require(maximumDiscoveryBytes in 1_024..1_048_576)
        require(maximumJwksBytes in 1_024..2_097_152)
        require(maximumTokenResponseBytes in 1_024..1_048_576)
        require(maximumIdTokenBytes in 1_024..262_144)
        require(this.allowedEndpointOrigins.isNotEmpty() && this.allowedEndpointOrigins.size <= 16) {
            "OIDC endpoint-origin allowlist must contain 1..16 exact origins"
        }
        this.allowedEndpointOrigins.forEach { origin ->
            require(origin.length <= 2_048 && origin == oidcEndpointOrigin(origin)) {
                "OIDC endpoint origins must be canonical scheme/authority values"
            }
        }
    }

    override fun toString(): String =
        "OidcProviderConfig(tenantId=$tenantId, providerId=$providerId, issuer=$issuer, " +
            "clientId=<redacted>, redirectUri=<redacted>, enabled=$enabled, " +
            "jitProvisioningEnabled=$jitProvisioningEnabled)"
}

internal fun oidcEndpointOrigin(value: String): String {
    codes.yousef.aether.auth.IdentityHttpRequest(
        codes.yousef.aether.auth.IdentityHttpMethod.GET,
        value
    )
    val separator = value.indexOf("://")
    val authorityStart = separator + 3
    val authorityEnd = listOf(value.indexOf('/', authorityStart), value.indexOf('?', authorityStart),
        value.indexOf('#', authorityStart))
        .filter { it >= 0 }
        .minOrNull() ?: value.length
    val scheme = value.substring(0, separator).lowercase()
    val authority = value.substring(authorityStart, authorityEnd).lowercase()
    return "$scheme://$authority"
}

@Serializable
enum class OidcErrorCode {
    @SerialName("provider_disabled") PROVIDER_DISABLED,
    @SerialName("discovery_unavailable") DISCOVERY_UNAVAILABLE,
    @SerialName("provider_metadata_invalid") PROVIDER_METADATA_INVALID,
    @SerialName("invalid_callback") INVALID_CALLBACK,
    @SerialName("invalid_state") INVALID_STATE,
    @SerialName("transaction_expired") TRANSACTION_EXPIRED,
    @SerialName("token_exchange_failed") TOKEN_EXCHANGE_FAILED,
    @SerialName("id_token_invalid") ID_TOKEN_INVALID,
    @SerialName("signature_invalid") SIGNATURE_INVALID,
    @SerialName("assertion_replayed") ASSERTION_REPLAYED,
    @SerialName("external_identity_not_linked") EXTERNAL_IDENTITY_NOT_LINKED,
    @SerialName("external_identity_conflict") EXTERNAL_IDENTITY_CONFLICT,
    @SerialName("provisioning_failed") PROVISIONING_FAILED,
    @SerialName("store_unavailable") STORE_UNAVAILABLE
}

/** Safe protocol failure. Provider payloads, token values, and exception messages never appear here. */
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class OidcError(
    val code: OidcErrorCode,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val message: String = code.genericMessage,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val retryable: Boolean = code.defaultRetryable
) {
    init {
        require(message == code.genericMessage) { "OIDC errors must use the stable generic message" }
        require(retryable == code.defaultRetryable) { "OIDC retryability is fixed by error code" }
    }
}

sealed interface OidcResult<out T> {
    data class Success<T>(val value: T) : OidcResult<T>
    data class Failure(val error: OidcError) : OidcResult<Nothing>

    fun valueOrNull(): T? = (this as? Success<T>)?.value
}

internal val OidcErrorCode.genericMessage: String
    get() = when (this) {
        OidcErrorCode.PROVIDER_DISABLED -> "The identity provider is unavailable."
        OidcErrorCode.DISCOVERY_UNAVAILABLE -> "The identity provider could not be reached."
        OidcErrorCode.PROVIDER_METADATA_INVALID -> "The identity provider configuration is invalid."
        OidcErrorCode.INVALID_CALLBACK -> "The identity response is invalid."
        OidcErrorCode.INVALID_STATE -> "The identity request is invalid or has already been used."
        OidcErrorCode.TRANSACTION_EXPIRED -> "The identity request has expired."
        OidcErrorCode.TOKEN_EXCHANGE_FAILED -> "The identity response could not be completed."
        OidcErrorCode.ID_TOKEN_INVALID -> "The identity response is invalid."
        OidcErrorCode.SIGNATURE_INVALID -> "The identity response signature is invalid."
        OidcErrorCode.ASSERTION_REPLAYED -> "The identity response has already been used."
        OidcErrorCode.EXTERNAL_IDENTITY_NOT_LINKED -> "The external identity is not linked."
        OidcErrorCode.EXTERNAL_IDENTITY_CONFLICT -> "The external identity cannot be linked."
        OidcErrorCode.PROVISIONING_FAILED -> "The external identity could not be provisioned."
        OidcErrorCode.STORE_UNAVAILABLE -> "The identity service is temporarily unavailable."
    }

internal val OidcErrorCode.defaultRetryable: Boolean
    get() = this == OidcErrorCode.DISCOVERY_UNAVAILABLE ||
        this == OidcErrorCode.STORE_UNAVAILABLE

/** Narrow provider surface consumed by the common-code HTTP middleware. */
interface OidcFederationProvider {
    val configuredTenantId: OrganizationId
    val configuredProviderId: String

    suspend fun beginAuthorization(request: OidcAuthorizationRequest): OidcResult<OidcAuthorizationStart>
    suspend fun completeAuthorization(request: OidcCallbackRequest): OidcResult<OidcAuthenticationResult>
}

/** Caller-held, server-side PKCE material. It must be stored in an encrypted or integrity-protected cookie/store. */
class OidcCallbackSecret internal constructor(
    val challengeId: ChallengeId,
    verifier: ByteArray
) {
    private val verifierValue = verifier.copyOf()

    init { require(verifierValue.size == 32) { "OIDC PKCE verifier seed must be 32 bytes" } }

    internal suspend fun <T> useVerifier(block: suspend (String) -> T): T {
        return useSeedForProtection { seed -> block(codes.yousef.aether.auth.Base64Url.encode(seed)) }
    }

    /**
     * Supplies a temporary verifier-seed copy to an application-owned authenticated-encryption
     * boundary. The resulting envelope may be persisted in an HttpOnly callback cookie or a
     * server-side distributed store; the raw seed must never be sent or logged.
     */
    suspend fun <T> useSeedForProtection(block: suspend (ByteArray) -> T): T {
        val copy = verifierValue.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    internal fun destroy() {
        verifierValue.fill(0)
    }

    override fun toString(): String = "OidcCallbackSecret(<redacted>)"

    companion object {
        /** Restores a callback secret after application-owned authenticated decryption. */
        fun restore(challengeId: ChallengeId, verifierSeed: ByteArray): OidcCallbackSecret =
            OidcCallbackSecret(challengeId, verifierSeed)
    }
}

class OidcAuthorizationStart internal constructor(
    val authorizationUrl: String,
    val callbackSecret: OidcCallbackSecret,
    val providerLease: FederationProviderLease,
    val expiresAt: Instant
) {
    init {
        require(providerLease.kind == FederationProviderKind.OIDC) {
            "OIDC authorization requires an OIDC provider lease"
        }
    }

    override fun toString(): String =
        "OidcAuthorizationStart(authorizationUrl=<redacted>, callbackSecret=<redacted>, " +
            "providerLease=$providerLease, expiresAt=$expiresAt)"
}

class OidcAuthorizationRequest(
    callbackBinding: ByteArray,
    val linkToUserId: UserId? = null
) {
    private val callbackBindingValue = callbackBinding.copyOf()

    init { require(callbackBindingValue.size in 16..1_024) { "OIDC callback binding must be 16..1024 bytes" } }

    internal fun callbackBindingBytes(): ByteArray = callbackBindingValue.copyOf()
    override fun toString(): String = "OidcAuthorizationRequest(callbackBinding=<redacted>, linkToUserId=$linkToUserId)"
}

class OidcCallbackRequest(
    val state: String,
    val authorizationCode: String,
    callbackBinding: ByteArray,
    val callbackSecret: OidcCallbackSecret,
    val providerLease: FederationProviderLease,
    val auditRequest: AuditRequestMetadata? = null
) {
    private val callbackBindingValue = callbackBinding.copyOf()

    init {
        require(state.length in 16..512 && state.none { it.isWhitespace() || it.isProtocolControl() }) {
            "Invalid OIDC state"
        }
        require(authorizationCode.length in 1..8_192 &&
            authorizationCode.none { it.isWhitespace() || it.isProtocolControl() }
        ) {
            "Invalid OIDC authorization code"
        }
        require(callbackBindingValue.size in 16..1_024) { "OIDC callback binding must be 16..1024 bytes" }
        require(providerLease.kind == FederationProviderKind.OIDC) {
            "OIDC callbacks require an OIDC provider lease"
        }
    }

    internal fun callbackBindingBytes(): ByteArray = callbackBindingValue.copyOf()
    override fun toString(): String =
        "OidcCallbackRequest(state=<redacted>, authorizationCode=<redacted>, callbackBinding=<redacted>, " +
            "callbackSecret=<redacted>, auditRequest=$auditRequest)"
}

data class OidcVerifiedClaims(
    val issuer: String,
    val subject: ExternalSubject,
    val audiences: Set<String>,
    val authorizedParty: String?,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val email: EmailAddress?,
    val displayName: String?
) {
    override fun toString(): String =
        "OidcVerifiedClaims(issuer=$issuer, subject=<redacted>, audiences=<redacted>, " +
            "authorizedParty=<redacted>, issuedAt=$issuedAt, expiresAt=$expiresAt, " +
            "email=${if (email == null) "none" else "<redacted>"}, displayName=${if (displayName == null) "none" else "<redacted>"})"
}

data class OidcAuthenticationResult(
    val userId: UserId,
    val externalIdentityId: ExternalIdentityId,
    val providerLease: FederationProviderLease,
    val assurance: AuthenticationAssurance = AuthenticationAssurance.SESSION,
    val authenticationMethod: SessionAuthenticationMethod = SessionAuthenticationMethod.OIDC,
    val passkeyStepUpRequiredForSensitiveActions: Boolean = true,
    val claims: OidcVerifiedClaims
) {
    init {
        require(providerLease.kind == FederationProviderKind.OIDC &&
            assurance == AuthenticationAssurance.SESSION &&
            authenticationMethod == SessionAuthenticationMethod.OIDC &&
            passkeyStepUpRequiredForSensitiveActions
        ) { "OIDC authentication results require an OIDC session lease and passkey step-up" }
    }
}

private fun requireCanonicalIssuer(value: String) {
    require(value.length in 8..2_048 && value == value.trim() && !value.endsWith('/')) { "Invalid OIDC issuer" }
    require(value.none { it.isWhitespace() || it.isProtocolControl() }) { "Invalid OIDC issuer" }
    require('?' !in value && '#' !in value && '@' !in value.substringAfter("://", "")) { "Invalid OIDC issuer" }
    // Reuse the identity runtime's strict HTTPS/loopback validation without performing I/O.
    codes.yousef.aether.auth.IdentityHttpRequest(
        codes.yousef.aether.auth.IdentityHttpMethod.GET,
        value
    )
}

private fun requireSafeCallbackUri(value: String) {
    require(value.length in 8..4_096 && '#' !in value &&
        value.none { it.isWhitespace() || it.isProtocolControl() }
    ) { "Invalid OIDC redirect URI" }
    codes.yousef.aether.auth.IdentityHttpRequest(
        codes.yousef.aether.auth.IdentityHttpMethod.GET,
        value
    )
}
