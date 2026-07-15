package codes.yousef.aether.auth

import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Newly issued session material. Secrets are redacted from diagnostics and must be shown once. */
class IssuedIdentitySession internal constructor(
    val session: IdentitySession,
    private val cookie: String,
    private val csrf: String
) {
    fun cookieValue(): String = cookie
    fun csrfToken(): String = csrf

    override fun toString(): String = "IssuedIdentitySession(session=${session.id}, cookie=<redacted>, csrf=<redacted>)"
}

/** Issues `selector.secret` sessions and persists only versioned HMAC-SHA-256 digests. */
class IdentitySessionIssuer(
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime),
    private val csrfConfig: IdentityCsrfConfig = IdentityCsrfConfig()
) {
    /**
     * Issues a tenant-bound federated session whose provenance can be selectively revoked when
     * an OIDC or SAML adapter is disabled. Federated assurance intentionally remains SESSION;
     * sensitive actions still require a subsequent passkey step-up.
     */
    suspend fun issueFederated(
        user: User,
        authenticationMethod: SessionAuthenticationMethod,
        providerLease: FederationProviderLease,
        externalIdentityId: ExternalIdentityId,
        authenticatedAt: Instant,
        device: DeviceMetadata = DeviceMetadata(),
        rotatedFrom: IdentitySession? = null
    ): IssuedIdentitySession {
        require(authenticationMethod == providerLease.kind.sessionAuthenticationMethod) {
            "Federated session method must match its provider lease"
        }
        return issue(
            user = user,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = authenticationMethod,
            authenticatedAt = authenticatedAt,
            device = device,
            rotatedFrom = rotatedFrom,
            federationOrganizationId = providerLease.organizationId,
            federationProviderKey = providerLease.storageKey,
            federationProviderSessionEpoch = providerLease.sessionEpoch,
            externalIdentityId = externalIdentityId
        )
    }

    suspend fun issue(
        user: User,
        assurance: AuthenticationAssurance,
        authenticationMethod: SessionAuthenticationMethod,
        authenticatedAt: Instant,
        device: DeviceMetadata = DeviceMetadata(),
        rotatedFrom: IdentitySession? = null,
        federationOrganizationId: OrganizationId? = null,
        federationProviderKey: String? = null,
        federationProviderSessionEpoch: Long? = null,
        externalIdentityId: ExternalIdentityId? = null,
        absoluteLifetime: IdentityDuration = config.lifetimes.sessionAbsolute,
        idleLifetime: IdentityDuration = config.lifetimes.sessionIdle
    ): IssuedIdentitySession {
        require(user.state == UserState.ACTIVE) { "Sessions may be issued only for active users" }
        require(assurance != AuthenticationAssurance.ANONYMOUS &&
            assurance != AuthenticationAssurance.SERVICE_CREDENTIAL
        ) { "Invalid user-session assurance" }
        require(rotatedFrom == null ||
            rotatedFrom.userId == user.id && rotatedFrom.state == SessionState.ACTIVE
        ) { "A rotated session must be an active session for the same user" }
        require(absoluteLifetime.seconds <= config.lifetimes.sessionAbsolute.seconds) {
            "Session lifetime cannot exceed the configured absolute maximum"
        }
        require(idleLifetime.seconds <= absoluteLifetime.seconds &&
            idleLifetime.seconds <= config.lifetimes.sessionIdle.seconds
        ) { "Session idle lifetime cannot exceed its absolute or configured idle maximum" }

        val selector = ids.newSessionId()
        val secretBytes = runtime.secureRandom.nextBytes(SESSION_SECRET_BYTES)
        require(secretBytes.size == SESSION_SECRET_BYTES) { "Secure random provider returned an invalid session secret" }
        val csrfToken = issueIdentityCsrfToken(runtime, config, csrfConfig)
        return try {
            val pepper = runtime.secrets.resolve(config.keys.sessionPepper)
            val digestBytes = runtime.crypto.hmacSha256(pepper, secretBytes)
            try {
                require(digestBytes.size == 32) { "HMAC-SHA-256 provider returned an invalid digest" }
                val absoluteCandidate = authenticatedAt + absoluteLifetime.seconds.seconds
                val absoluteExpiresAt = rotatedFrom?.absoluteExpiresAt?.let { previous ->
                    Instant.fromEpochMilliseconds(min(previous.toEpochMilliseconds(), absoluteCandidate.toEpochMilliseconds()))
                } ?: absoluteCandidate
                require(authenticatedAt < absoluteExpiresAt) { "The session family has reached its absolute expiration" }
                val idleCandidate = authenticatedAt + idleLifetime.seconds.seconds
                val idleExpiresAt = Instant.fromEpochMilliseconds(
                    min(idleCandidate.toEpochMilliseconds(), absoluteExpiresAt.toEpochMilliseconds())
                )
                val session = IdentitySession(
                    id = selector,
                    familyId = rotatedFrom?.familyId ?: selector,
                    userId = user.id,
                    tokenDigest = SecretDigest(
                        algorithm = DigestAlgorithm.HMAC_SHA256,
                        encoded = Base64Url.encode(digestBytes),
                        keyVersion = config.keys.sessionPepper.version
                    ),
                    csrfDigest = csrfToken.digest,
                    device = device,
                    assurance = assurance,
                    authenticationMethod = authenticationMethod,
                    federationOrganizationId = federationOrganizationId,
                    federationProviderKey = federationProviderKey,
                    federationProviderSessionEpoch = federationProviderSessionEpoch,
                    externalIdentityId = externalIdentityId,
                    userSessionEpoch = user.sessionEpoch,
                    rotationCounter = (rotatedFrom?.rotationCounter ?: -1L) + 1L,
                    createdAt = authenticatedAt,
                    authenticatedAt = authenticatedAt,
                    lastUsedAt = authenticatedAt,
                    idleExpiresAt = idleExpiresAt,
                    absoluteExpiresAt = absoluteExpiresAt,
                    rotatedFromId = rotatedFrom?.id
                )
                IssuedIdentitySession(
                    session = session,
                    cookie = "${selector.value}.${Base64Url.encode(secretBytes)}",
                    csrf = csrfToken.encoded
                )
            } finally {
                digestBytes.fill(0)
            }
        } finally {
            secretBytes.fill(0)
        }
    }

    companion object {
        const val SESSION_SECRET_BYTES: Int = 32
    }
}
