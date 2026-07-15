package codes.yousef.aether.auth

import codes.yousef.aether.core.Exchange
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Resolves `selector.secret` session cookies without persisting or logging the secret. */
class StoreBackedIdentityContextResolver(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    private val organizationSelector: (Exchange) -> OrganizationId? = { null }
) : IdentityContextResolver {
    override suspend fun resolve(exchange: Exchange): IdentityResolutionResult {
        val cookie = exchange.request.cookies[config.cookie.name] ?: return IdentityResolutionResult.Anonymous
        val token = parseSessionToken(cookie.value) ?: return IdentityResolutionResult.Rejected(
            IdentityErrorCode.INVALID_CREDENTIALS
        )
        try {
            val session = when (val result = store.findSession(token.selector)) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> return result.error.toResolutionFailure()
            } ?: return IdentityResolutionResult.Rejected(IdentityErrorCode.INVALID_CREDENTIALS)

            if (!verifyToken(session, token.secret)) {
                return IdentityResolutionResult.Rejected(IdentityErrorCode.INVALID_CREDENTIALS)
            }

            val user = when (val result = store.findUser(session.userId)) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> return result.error.toResolutionFailure()
            } ?: return IdentityResolutionResult.Rejected(IdentityErrorCode.SESSION_REVOKED)
            if (user.state != UserState.ACTIVE || user.sessionEpoch != session.userSessionEpoch) {
                return IdentityResolutionResult.Rejected(IdentityErrorCode.SESSION_REVOKED)
            }

            when (validateFederatedSessionProvider(session)) {
                FederationSessionValidation.Valid -> Unit
                FederationSessionValidation.Revoked -> return IdentityResolutionResult.Rejected(
                    IdentityErrorCode.SESSION_REVOKED
                )
                FederationSessionValidation.Unavailable -> return IdentityResolutionResult.Rejected(
                    IdentityErrorCode.SERVICE_UNAVAILABLE
                )
            }

            val now = runtime.clock.now()
            if (session.state != SessionState.ACTIVE) {
                return IdentityResolutionResult.Rejected(IdentityErrorCode.SESSION_REVOKED)
            }
            if (!session.isUsableAt(now)) {
                return IdentityResolutionResult.Rejected(IdentityErrorCode.SESSION_EXPIRED)
            }
            val federationScope = session.federationOrganizationId?.let { organizationId ->
                val organization = when (val result = store.findOrganization(organizationId)) {
                    is StoreResult.Success -> result.value
                    is StoreResult.Failure -> return result.error.toResolutionFailure()
                }
                val membership = when (val result = store.findMembershipForUser(user.id, organizationId)) {
                    is StoreResult.Success -> result.value
                    is StoreResult.Failure -> return result.error.toResolutionFailure()
                }
                if (organization?.state != OrganizationState.ACTIVE ||
                    membership?.state != MembershipState.ACTIVE
                ) {
                    return IdentityResolutionResult.Rejected(IdentityErrorCode.SESSION_REVOKED)
                }
                organization to membership
            }
            val idleExpiresAt = minOf(
                now + config.lifetimes.sessionIdle.seconds.seconds,
                session.absoluteExpiresAt
            )
            val expectedTouchedSession = session.copy(
                version = session.version + 1,
                lastUsedAt = now,
                idleExpiresAt = idleExpiresAt
            )
            val renewedSession = when (val touched = store.touchIdentitySession(
                TouchIdentitySessionCommand(
                    sessionId = session.id,
                    expectedVersion = session.version,
                    lastUsedAt = now,
                    idleExpiresAt = idleExpiresAt
                )
            )) {
                is StoreResult.Success -> touched.value.takeIf { it == expectedTouchedSession }
                    ?: return IdentityResolutionResult.Rejected(IdentityErrorCode.SERVICE_UNAVAILABLE)
                is StoreResult.Failure -> {
                    if (touched.error.code != IdentityStoreErrorCode.VERSION_CONFLICT) {
                        return touched.error.toTouchResolutionFailure()
                    }
                    when (val concurrent = resolveConcurrentTouch(session, token.secret, now)) {
                        is ConcurrentTouchResult.Resolved -> concurrent.session
                        ConcurrentTouchResult.Revoked -> return IdentityResolutionResult.Rejected(
                            IdentityErrorCode.SESSION_REVOKED
                        )
                        ConcurrentTouchResult.Unavailable -> return IdentityResolutionResult.Rejected(
                            IdentityErrorCode.SERVICE_UNAVAILABLE
                        )
                    }
                }
            }

            val organizationId = organizationSelector(exchange)?.takeIf { selectedId ->
                session.federationOrganizationId == null || selectedId == session.federationOrganizationId
            }
            val organization = organizationId?.let { id ->
                federationScope?.first?.takeIf { it.id == id } ?: run {
                    when (val result = store.findOrganization(id)) {
                        is StoreResult.Success -> result.value
                        is StoreResult.Failure -> return result.error.toResolutionFailure()
                    }
                }
            }
            val membership = organizationId?.let { id ->
                federationScope?.second?.takeIf { it.organizationId == id } ?: run {
                    when (val result = store.findMembershipForUser(user.id, id)) {
                        is StoreResult.Success -> result.value
                        is StoreResult.Failure -> return result.error.toResolutionFailure()
                    }
                }
            }
            val principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = user.id,
                displayName = user.displayName,
                assurance = renewedSession.assurance,
                authenticatedAt = renewedSession.authenticatedAt,
                sessionId = renewedSession.id
            )
            return IdentityResolutionResult.Authenticated(
                IdentityContext(
                    principal = principal,
                    session = renewedSession,
                    organization = organization,
                    membership = membership
                )
            )
        } finally {
            token.secret.fill(0)
        }
    }

    private suspend fun verifyToken(session: IdentitySession, secret: ByteArray): Boolean {
        if (session.tokenDigest.algorithm != DigestAlgorithm.HMAC_SHA256 ||
            session.tokenDigest.keyVersion == null
        ) return false
        val pepperReference = config.keys.sessionPepper(session.tokenDigest.keyVersion) ?: return false
        val expectedDigest = runCatching {
            Base64Url.decode(session.tokenDigest.encoded, maximumBytes = 32)
        }.getOrNull() ?: return false
        if (expectedDigest.size != 32) {
            expectedDigest.fill(0)
            return false
        }
        return try {
            val actualDigest = runtime.crypto.hmacSha256(runtime.secrets.resolve(pepperReference), secret)
            try {
                actualDigest.size == 32 && runtime.crypto.constantTimeEquals(expectedDigest, actualDigest)
            } finally {
                actualDigest.fill(0)
            }
        } finally {
            expectedDigest.fill(0)
        }
    }

    /** Accept only a concurrent request that already performed a valid renewal for this credential. */
    private suspend fun resolveConcurrentTouch(
        previous: IdentitySession,
        secret: ByteArray,
        now: Instant
    ): ConcurrentTouchResult {
        val current = when (val found = store.findSession(previous.id)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return ConcurrentTouchResult.Unavailable
        } ?: return ConcurrentTouchResult.Unavailable
        if (current.version <= previous.version || current.userId != previous.userId ||
            current.state != SessionState.ACTIVE || !current.isUsableAt(now) ||
            !verifyToken(current, secret)
        ) return ConcurrentTouchResult.Unavailable
        val expectedIdleExpiration = minOf(
            current.lastUsedAt + config.lifetimes.sessionIdle.seconds.seconds,
            current.absoluteExpiresAt
        )
        if (current.idleExpiresAt != expectedIdleExpiration) return ConcurrentTouchResult.Unavailable
        val currentUser = when (val found = store.findUser(current.userId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return ConcurrentTouchResult.Unavailable
        } ?: return ConcurrentTouchResult.Revoked
        if (currentUser.state != UserState.ACTIVE || currentUser.sessionEpoch != current.userSessionEpoch) {
            return ConcurrentTouchResult.Revoked
        }
        return when (validateFederatedSessionProvider(current)) {
            FederationSessionValidation.Valid -> ConcurrentTouchResult.Resolved(current)
            FederationSessionValidation.Revoked -> ConcurrentTouchResult.Revoked
            FederationSessionValidation.Unavailable -> ConcurrentTouchResult.Unavailable
        }
    }

    private suspend fun validateFederatedSessionProvider(
        session: IdentitySession
    ): FederationSessionValidation {
        val storageKey = session.federationProviderKey ?: return FederationSessionValidation.Valid
        val organizationId = session.federationOrganizationId ?: return FederationSessionValidation.Revoked
        val sessionEpoch = session.federationProviderSessionEpoch ?: return FederationSessionValidation.Revoked
        val kind = session.authenticationMethod.federationProviderKindOrNull()
            ?: return FederationSessionValidation.Revoked
        val control = when (val found = store.findFederationProviderControlByStorageKey(storageKey)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return if (
                found.error.code == IdentityStoreErrorCode.UNAVAILABLE ||
                found.error.code == IdentityStoreErrorCode.INTERNAL
            ) FederationSessionValidation.Unavailable else FederationSessionValidation.Revoked
        } ?: return FederationSessionValidation.Revoked
        return if (control.organizationId == organizationId &&
            control.kind == kind &&
            control.storageKey == storageKey &&
            control.state == FederationProviderState.ENABLED &&
            control.sessionEpoch == sessionEpoch
        ) FederationSessionValidation.Valid else FederationSessionValidation.Revoked
    }

    private fun IdentitySession.isUsableAt(now: Instant): Boolean =
        state == SessionState.ACTIVE && now < idleExpiresAt && now < absoluteExpiresAt

    private fun parseSessionToken(value: String): ParsedSessionToken? {
        if (value.length !in 20..512 || value.count { it == '.' } != 1) return null
        val selectorValue = value.substringBefore('.')
        val secretValue = value.substringAfter('.')
        val selector = SessionId.parseOrNull(selectorValue) ?: return null
        val secret = runCatching { Base64Url.decode(secretValue, maximumBytes = 64) }.getOrNull() ?: return null
        if (secret.size != SESSION_SECRET_BYTES) {
            secret.fill(0)
            return null
        }
        return ParsedSessionToken(selector, secret)
    }

    private fun IdentityStoreError.toResolutionFailure(): IdentityResolutionResult.Rejected =
        IdentityResolutionResult.Rejected(
            if (code == IdentityStoreErrorCode.UNAVAILABLE || code == IdentityStoreErrorCode.INTERNAL) {
                IdentityErrorCode.SERVICE_UNAVAILABLE
            } else {
                IdentityErrorCode.INVALID_CREDENTIALS
            }
        )

    private fun IdentityStoreError.toTouchResolutionFailure(): IdentityResolutionResult.Rejected =
        IdentityResolutionResult.Rejected(
            when (code) {
                IdentityStoreErrorCode.SESSION_NOT_ACTIVE -> IdentityErrorCode.SESSION_REVOKED
                IdentityStoreErrorCode.SESSION_EXPIRED -> IdentityErrorCode.SESSION_EXPIRED
                IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED -> IdentityErrorCode.SESSION_REVOKED
                IdentityStoreErrorCode.NOT_FOUND -> IdentityErrorCode.SESSION_REVOKED
                else -> IdentityErrorCode.SERVICE_UNAVAILABLE
            }
        )

    private sealed interface FederationSessionValidation {
        data object Valid : FederationSessionValidation
        data object Revoked : FederationSessionValidation
        data object Unavailable : FederationSessionValidation
    }

    private sealed interface ConcurrentTouchResult {
        data class Resolved(val session: IdentitySession) : ConcurrentTouchResult
        data object Revoked : ConcurrentTouchResult
        data object Unavailable : ConcurrentTouchResult
    }

    private class ParsedSessionToken(val selector: SessionId, val secret: ByteArray)

    companion object {
        const val SESSION_SECRET_BYTES: Int = 32
    }
}
