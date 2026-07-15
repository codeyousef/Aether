package codes.yousef.aether.auth

import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceAuthorizationResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Long,
    val interval: Int
) {
    override fun toString(): String =
        "DeviceAuthorizationResponse(deviceCode=<redacted>, userCode=<redacted>, " +
            "verificationUri=$verificationUri, expiresIn=$expiresIn, interval=$interval)"
}

@Serializable
enum class OAuthDeviceErrorCode(val publicMessage: String, val retryable: Boolean) {
    @SerialName("invalid_request") INVALID_REQUEST("The request is invalid.", false),
    @SerialName("unsupported_grant_type") UNSUPPORTED_GRANT_TYPE("The grant type is unsupported.", false),
    @SerialName("authorization_pending") AUTHORIZATION_PENDING("Authorization is still pending.", true),
    @SerialName("slow_down") SLOW_DOWN("Polling is too frequent.", true),
    @SerialName("access_denied") ACCESS_DENIED("Authorization was denied.", false),
    @SerialName("expired_token") EXPIRED_TOKEN("The device authorization expired.", false),
    @SerialName("invalid_grant") INVALID_GRANT("The grant is invalid.", false),
    @SerialName("invalid_scope") INVALID_SCOPE("The requested scope is invalid.", false)
}

@Serializable
data class OAuthDeviceErrorResponse(
    val error: OAuthDeviceErrorCode,
    val message: String,
    val requestId: String,
    val retryable: Boolean
) {
    init {
        require(message == error.publicMessage) { "OAuth device errors must use the stable public message" }
        require(retryable == error.retryable) { "OAuth device errors must use the stable retryability value" }
        require(requestId.isNotBlank() && requestId.length <= 255) { "OAuth device errors require a request ID" }
    }

    constructor(error: OAuthDeviceErrorCode, requestId: String) : this(
        error = error,
        message = error.publicMessage,
        requestId = requestId,
        retryable = error.retryable
    )
}

@Serializable
data class OAuthDeviceTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String,
    val scope: String
) {
    override fun toString(): String =
        "OAuthDeviceTokenResponse(accessToken=<redacted>, tokenType=$tokenType, expiresIn=$expiresIn, refreshToken=<redacted>, scope=$scope)"
}

sealed interface DeviceTokenEndpointResult {
    data class Success(val response: OAuthDeviceTokenResponse) : DeviceTokenEndpointResult
    data class Error(
        val code: OAuthDeviceErrorCode,
        val pollingIntervalSeconds: Int? = null
    ) : DeviceTokenEndpointResult
    data class Unavailable(val code: IdentityErrorCode) : DeviceTokenEndpointResult
}

/** Complete RFC 8628 state machine plus opaque access/rotating-refresh credentials. */
class IdentityDeviceAuthorizationService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    private val allowedCapabilities: Set<Capability>,
    private val capabilityResolver: CapabilityResolver = CapabilityResolver.NONE,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime)
) {
    init { require(allowedCapabilities.isNotEmpty()) { "At least one device capability must be allowlisted" } }

    /** Resolves a human user code to a safe approval view without exposing either stored digest. */
    suspend fun inspect(
        userCode: String,
        viewer: IdentityContext
    ): IdentityOperationResult<DeviceGrantView> {
        val now = runtime.clock.now()
        val principal = viewer.principal
        val session = viewer.session
        if (principal?.kind != IdentityPrincipalKind.USER || session?.state != SessionState.ACTIVE ||
            session.assurance == AuthenticationAssurance.RECOVERY || !viewer.isSessionUsableAt(now)
        ) return IdentityOperationResult.Failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        val normalized = normalizeUserCode(userCode)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        val grant = findGrantByUserCode(normalized)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        if (now >= grant.expiresAt || grant.state == DeviceGrantState.CONSUMED ||
            grant.state == DeviceGrantState.CANCELLED || grant.state == DeviceGrantState.EXPIRED
        ) return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        return IdentityOperationResult.Success(
            DeviceGrantView(
                id = grant.id,
                clientName = grant.clientName,
                requestedCapabilities = grant.requestedCapabilities,
                state = grant.state,
                createdAt = grant.createdAt,
                expiresAt = grant.expiresAt
            )
        )
    }

    suspend fun start(
        clientId: String,
        requestedCapabilities: Set<Capability>,
        clientName: String = clientId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<DeviceAuthorizationResponse> {
        if (authorizationRequestError(clientId, clientName, requestedCapabilities) != null) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        val now = runtime.clock.now()
        val deviceBytes = runtime.secureRandom.nextBytes(DEVICE_CODE_BYTES)
        val userBytes = runtime.secureRandom.nextBytes(USER_CODE_BYTES)
        require(deviceBytes.size == DEVICE_CODE_BYTES && userBytes.size == USER_CODE_BYTES) {
            "Secure random provider returned invalid device authorization material"
        }
        return try {
            val deviceCode = Base64Url.encode(deviceBytes)
            val userCode = encodeUserCode(userBytes)
            val grant = DeviceGrant(
                id = ids.newDeviceGrantId(),
                deviceCodeDigest = digestDeviceSecret(DEVICE_CODE_CONTEXT, deviceBytes),
                userCodeDigest = digestDeviceSecret(USER_CODE_CONTEXT, userCode.encodeToByteArray()),
                clientId = clientId,
                clientName = clientName,
                requestedCapabilities = requestedCapabilities,
                createdAt = now,
                expiresAt = now + config.lifetimes.deviceGrant.seconds.seconds
            )
            val audit = grantAudit(
                grant,
                AuditActor(AuditActorType.ANONYMOUS),
                now,
                "device_authorization_started",
                request
            )
            when (val result = store.compareAndSetDeviceGrant(
                CompareAndSetDeviceGrantCommand(null, grant, audit)
            )) {
                is StoreResult.Failure -> IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
                is StoreResult.Success -> {
                    // The verification URI loads a signed-in manual code-entry UI. The optional
                    // verification_uri_complete field is deliberately omitted so human codes do
                    // not enter browser history, referrers, proxy logs, or failure diagnostics.
                    val verificationUri = "${config.publicBaseUrl}/identity"
                    IdentityOperationResult.Success(
                        DeviceAuthorizationResponse(
                            deviceCode = deviceCode,
                            userCode = userCode,
                            verificationUri = verificationUri,
                            expiresIn = config.lifetimes.deviceGrant.seconds,
                            interval = grant.pollingIntervalSeconds
                        )
                    )
                }
            }
        } finally {
            deviceBytes.fill(0)
            userBytes.fill(0)
        }
    }

    internal fun authorizationRequestError(
        clientId: String,
        clientName: String,
        requestedCapabilities: Set<Capability>
    ): OAuthDeviceErrorCode? = when {
        !isValidClientId(clientId) || clientName.isBlank() || clientName.length > 200 ->
            OAuthDeviceErrorCode.INVALID_REQUEST
        requestedCapabilities.isEmpty() || !allowedCapabilities.containsAll(requestedCapabilities) ->
            OAuthDeviceErrorCode.INVALID_SCOPE
        else -> null
    }

    suspend fun approve(
        userCode: String,
        approver: IdentityContext,
        organizationId: OrganizationId,
        approvedCapabilities: Set<Capability>,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Unit> {
        val normalizedCode = normalizeUserCode(userCode)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        val grant = findGrantByUserCode(normalizedCode)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        val now = runtime.clock.now()
        val principal = approver.principal
        if (principal?.kind != IdentityPrincipalKind.USER || approver.session?.assurance == AuthenticationAssurance.RECOVERY ||
            grant.state != DeviceGrantState.PENDING || now >= grant.expiresAt ||
            approvedCapabilities.isEmpty() || !grant.requestedCapabilities.containsAll(approvedCapabilities)
        ) return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        val userId = requireNotNull(principal.userId)
        val organization = when (val found = store.findOrganization(organizationId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return IdentityOperationResult.Failure(found.error.toIdentityErrorCode())
        } ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        val membership = when (val found = store.findMembershipForUser(userId, organizationId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return IdentityOperationResult.Failure(found.error.toIdentityErrorCode())
        } ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        val scoped = IdentityContext(principal, approver.session, organization, membership)
        if (organization.state != OrganizationState.ACTIVE || membership.state != MembershipState.ACTIVE ||
            !scoped.capabilities(capabilityResolver).containsAll(approvedCapabilities)
        ) return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        val replacement = grant.copy(
            approvedCapabilities = approvedCapabilities,
            state = DeviceGrantState.AUTHORIZED,
            userId = userId,
            organizationId = organization.id,
            membershipId = membership.id,
            membershipVersion = membership.version,
            authorizedByUserId = userId,
            version = grant.version + 1,
            authorizedAt = now
        )
        val audit = grantAudit(
            replacement,
            AuditActor(AuditActorType.USER, userId = userId),
            now,
            "device_authorization_approved",
            request,
            organization.id
        )
        return store.compareAndSetDeviceGrant(
            CompareAndSetDeviceGrantCommand(grant.version, replacement, audit)
        ).toOperationResult().mapToUnit()
    }

    suspend fun deny(
        userCode: String,
        approver: IdentityContext,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Unit> {
        val normalized = normalizeUserCode(userCode)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        val grant = findGrantByUserCode(normalized)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        val principal = approver.principal
        val now = runtime.clock.now()
        if (principal?.kind != IdentityPrincipalKind.USER || approver.session?.assurance == AuthenticationAssurance.RECOVERY ||
            grant.state != DeviceGrantState.PENDING || now >= grant.expiresAt
        ) return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        val replacement = grant.copy(
            state = DeviceGrantState.DENIED,
            version = grant.version + 1,
            deniedAt = now
        )
        val audit = grantAudit(
            replacement,
            AuditActor(AuditActorType.USER, userId = principal.userId),
            now,
            "device_authorization_denied",
            request
        )
        return store.compareAndSetDeviceGrant(
            CompareAndSetDeviceGrantCommand(grant.version, replacement, audit)
        ).toOperationResult().mapToUnit()
    }

    suspend fun cancel(
        deviceCode: String,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Unit> {
        val parsed = decodeDeviceCode(deviceCode)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        try {
            val grant = findGrantByDeviceCode(parsed)
                ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
            val now = runtime.clock.now()
            if (grant.state != DeviceGrantState.PENDING && grant.state != DeviceGrantState.AUTHORIZED) {
                return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
            }
            val replacement = grant.copy(
                state = DeviceGrantState.CANCELLED,
                version = grant.version + 1,
                cancelledAt = now
            )
            val audit = grantAudit(
                replacement,
                AuditActor(AuditActorType.ANONYMOUS),
                now,
                "device_authorization_cancelled",
                request
            )
            return store.compareAndSetDeviceGrant(
                CompareAndSetDeviceGrantCommand(grant.version, replacement, audit)
            ).toOperationResult().mapToUnit()
        } finally {
            parsed.fill(0)
        }
    }

    suspend fun poll(
        deviceCode: String,
        clientId: String,
        request: AuditRequestMetadata? = null
    ): DeviceTokenEndpointResult {
        if (!isValidClientId(clientId)) return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
        val parsed = decodeDeviceCode(deviceCode)
            ?: return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
        try {
            val grant = findGrantByDeviceCode(parsed) ?: return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            if (grant.clientId != clientId) return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            val now = runtime.clock.now()
            if (now >= grant.expiresAt && grant.state != DeviceGrantState.CONSUMED) {
                if (grant.state == DeviceGrantState.PENDING || grant.state == DeviceGrantState.AUTHORIZED) {
                    val expired = grant.copy(
                        state = DeviceGrantState.EXPIRED,
                        version = grant.version + 1,
                        expiredAt = now
                    )
                    store.compareAndSetDeviceGrant(
                        CompareAndSetDeviceGrantCommand(
                            grant.version,
                            expired,
                            grantAudit(expired, AuditActor(AuditActorType.ANONYMOUS), now,
                                "device_authorization_expired", request)
                        )
                    )
                }
                return oauthError(OAuthDeviceErrorCode.EXPIRED_TOKEN)
            }
            return when (grant.state) {
                DeviceGrantState.PENDING -> recordPoll(grant, now, request)
                DeviceGrantState.AUTHORIZED -> exchange(grant, now, request)
                DeviceGrantState.DENIED -> oauthError(OAuthDeviceErrorCode.ACCESS_DENIED)
                DeviceGrantState.EXPIRED -> oauthError(OAuthDeviceErrorCode.EXPIRED_TOKEN)
                DeviceGrantState.CONSUMED,
                DeviceGrantState.CANCELLED -> oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            }
        } finally {
            parsed.fill(0)
        }
    }

    suspend fun refresh(
        refreshToken: String,
        clientId: String,
        request: AuditRequestMetadata? = null
    ): DeviceTokenEndpointResult {
        if (!isValidClientId(clientId)) return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
        val parsed = parseSelectorSecret(refreshToken, REFRESH_TOKEN_BYTES)
            ?: return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
        try {
            val stored = when (val found = store.findDeviceRefreshTokenBySelector(parsed.selector)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return DeviceTokenEndpointResult.Unavailable(found.error.toIdentityErrorCode())
            } ?: return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            if (!verifyTokenDigest(REFRESH_TOKEN_CONTEXT, parsed, stored.secretDigest)) {
                return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            }
            val family = when (val found = store.findDeviceTokenFamily(stored.familyId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return DeviceTokenEndpointResult.Unavailable(found.error.toIdentityErrorCode())
            } ?: return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            if (family.clientId != clientId) return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            val now = runtime.clock.now()
            if (stored.state == DeviceRefreshTokenState.ROTATED) {
                return revokeRefreshReplay(family, now, request)
            }
            if (stored.state != DeviceRefreshTokenState.ACTIVE || family.state != DeviceTokenFamilyState.ACTIVE ||
                now >= stored.expiresAt || now >= family.expiresAt
            ) return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            when (val authorization = validateBoundAuthorization(family)) {
                is BoundAuthorization.Valid -> Unit
                BoundAuthorization.Invalid -> {
                    revokeInvalidAuthorization(family, now, request)
                    return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
                }
                is BoundAuthorization.Unavailable -> {
                    return DeviceTokenEndpointResult.Unavailable(authorization.code)
                }
            }
            val issued = issueTokenPair(family, stored.rotationCounter + 1, now)
            val audit = tokenAudit(
                family,
                AuditAction.DEVICE_TOKEN_REFRESHED,
                AuditOutcome.SUCCEEDED,
                now,
                null,
                request
            )
            return when (val result = store.rotateDeviceRefreshToken(
                RotateDeviceRefreshTokenCommand(
                    refreshTokenId = stored.id,
                    expectedRefreshTokenVersion = stored.version,
                    expectedFamilyVersion = family.version,
                    replacementAccessToken = issued.access,
                    replacementRefreshToken = issued.refresh,
                    rotatedAt = now,
                    auditEvent = audit
                )
            )) {
                is StoreResult.Success -> DeviceTokenEndpointResult.Success(issued.response(now, family.capabilities))
                is StoreResult.Failure -> if (result.error.code == IdentityStoreErrorCode.VERSION_CONFLICT ||
                    result.error.code == IdentityStoreErrorCode.INVALID_TRANSITION
                ) resolveRefreshRotationConflict(parsed, stored, family, clientId, now, request, result.error)
                else DeviceTokenEndpointResult.Unavailable(result.error.toIdentityErrorCode())
            }
        } finally {
            parsed.secret.fill(0)
        }
    }

    /**
     * A refresh rotation conflict can mean another request consumed the same token after this
     * request read it. Re-read the token before returning so that a confirmed replay revokes the
     * whole family instead of being reported as a transient store conflict.
     */
    private suspend fun resolveRefreshRotationConflict(
        parsed: ParsedSelectorSecret,
        expectedToken: DeviceRefreshToken,
        expectedFamily: DeviceTokenFamily,
        clientId: String,
        now: Instant,
        request: AuditRequestMetadata?,
        rotationError: IdentityStoreError
    ): DeviceTokenEndpointResult {
        val latestToken = when (val found = store.findDeviceRefreshTokenBySelector(parsed.selector)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return DeviceTokenEndpointResult.Unavailable(found.error.toIdentityErrorCode())
        } ?: return DeviceTokenEndpointResult.Unavailable(IdentityErrorCode.SERVICE_UNAVAILABLE)
        if (latestToken.id != expectedToken.id || latestToken.familyId != expectedFamily.id) {
            return DeviceTokenEndpointResult.Unavailable(IdentityErrorCode.SERVICE_UNAVAILABLE)
        }
        if (!verifyTokenDigest(REFRESH_TOKEN_CONTEXT, parsed, latestToken.secretDigest)) {
            return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
        }
        val latestFamily = when (val found = store.findDeviceTokenFamily(latestToken.familyId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return DeviceTokenEndpointResult.Unavailable(found.error.toIdentityErrorCode())
        } ?: return DeviceTokenEndpointResult.Unavailable(IdentityErrorCode.SERVICE_UNAVAILABLE)
        if (latestFamily.clientId != clientId) return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
        if (latestToken.state == DeviceRefreshTokenState.ROTATED) {
            return revokeRefreshReplay(latestFamily, now, request)
        }
            if (latestFamily.state != DeviceTokenFamilyState.ACTIVE ||
            latestToken.state != DeviceRefreshTokenState.ACTIVE
        ) return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
        when (val authorization = validateBoundAuthorization(latestFamily)) {
            is BoundAuthorization.Valid -> Unit
            BoundAuthorization.Invalid -> {
                revokeInvalidAuthorization(latestFamily, now, request)
                return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            }
            is BoundAuthorization.Unavailable -> {
                return DeviceTokenEndpointResult.Unavailable(authorization.code)
            }
        }
        return DeviceTokenEndpointResult.Unavailable(rotationError.toIdentityErrorCode())
    }

    /** Revokes a replayed refresh token's family, tolerating a concurrent successful revocation. */
    private suspend fun revokeRefreshReplay(
        family: DeviceTokenFamily,
        now: Instant,
        request: AuditRequestMetadata?
    ): DeviceTokenEndpointResult {
        if (family.state != DeviceTokenFamilyState.ACTIVE) {
            return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
        }
        val audit = tokenAudit(
            family,
            AuditAction.DEVICE_TOKEN_REPLAY_DETECTED,
            AuditOutcome.DENIED,
            now,
            "refresh_token_replay",
            request
        )
        val revoked = store.revokeDeviceTokenFamily(
            RevokeDeviceTokenFamilyCommand(
                family.id,
                family.version,
                now,
                "refresh_token_replay",
                replayDetected = true,
                auditEvent = audit
            )
        )
        if (revoked is StoreResult.Failure) {
            val latest = when (val found = store.findDeviceTokenFamily(family.id)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return DeviceTokenEndpointResult.Unavailable(
                    found.error.toIdentityErrorCode()
                )
            }
            if (latest?.state != DeviceTokenFamilyState.REVOKED) {
                return DeviceTokenEndpointResult.Unavailable(revoked.error.toIdentityErrorCode())
            }
        }
        return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
    }

    /**
     * RFC 7009-style refresh-token revocation. Unknown, malformed, expired, or already-revoked
     * tokens intentionally return success so this endpoint cannot be used as a token oracle.
     */
    suspend fun revokeRefreshToken(
        refreshToken: String,
        clientId: String,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Unit> {
        if (!isValidClientId(clientId)) return IdentityOperationResult.Success(Unit)
        val parsed = parseSelectorSecret(refreshToken, REFRESH_TOKEN_BYTES)
            ?: return IdentityOperationResult.Success(Unit)
        try {
            val stored = when (val found = store.findDeviceRefreshTokenBySelector(parsed.selector)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return if (found.error.code == IdentityStoreErrorCode.UNAVAILABLE ||
                    found.error.code == IdentityStoreErrorCode.INTERNAL
                ) IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
                else IdentityOperationResult.Success(Unit)
            } ?: return IdentityOperationResult.Success(Unit)
            if (!verifyTokenDigest(REFRESH_TOKEN_CONTEXT, parsed, stored.secretDigest)) {
                return IdentityOperationResult.Success(Unit)
            }
            val family = when (val found = store.findDeviceTokenFamily(stored.familyId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return if (found.error.code == IdentityStoreErrorCode.UNAVAILABLE ||
                    found.error.code == IdentityStoreErrorCode.INTERNAL
                ) IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
                else IdentityOperationResult.Success(Unit)
            } ?: return IdentityOperationResult.Success(Unit)
            if (family.clientId != clientId) return IdentityOperationResult.Success(Unit)
            if (family.state != DeviceTokenFamilyState.ACTIVE) return IdentityOperationResult.Success(Unit)
            val now = runtime.clock.now()
            val audit = tokenAudit(
                family,
                AuditAction.DEVICE_TOKEN_REVOKED,
                AuditOutcome.SUCCEEDED,
                now,
                "client_revocation",
                request
            )
            return when (val result = store.revokeDeviceTokenFamily(
                RevokeDeviceTokenFamilyCommand(
                    familyId = family.id,
                    expectedFamilyVersion = family.version,
                    revokedAt = now,
                    reasonCode = "client_revocation",
                    auditEvent = audit
                )
            )) {
                is StoreResult.Success -> IdentityOperationResult.Success(Unit)
                is StoreResult.Failure -> if (result.error.code == IdentityStoreErrorCode.UNAVAILABLE ||
                    result.error.code == IdentityStoreErrorCode.INTERNAL
                ) IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
                else IdentityOperationResult.Success(Unit)
            }
        } finally {
            parsed.secret.fill(0)
        }
    }

    suspend fun authenticateAccessToken(token: String): IdentityOperationResult<IdentityContext> {
        val parsed = parseSelectorSecret(token, ACCESS_TOKEN_BYTES)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        try {
            val access = when (val found = store.findDeviceAccessTokenBySelector(parsed.selector)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return IdentityOperationResult.Failure(found.error.toIdentityErrorCode())
            } ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            if (!verifyTokenDigest(ACCESS_TOKEN_CONTEXT, parsed, access.secretDigest)) {
                return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            }
            val family = when (val found = store.findDeviceTokenFamily(access.familyId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return IdentityOperationResult.Failure(found.error.toIdentityErrorCode())
            } ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            val now = runtime.clock.now()
            if (access.state != DeviceAccessTokenState.ACTIVE || family.state != DeviceTokenFamilyState.ACTIVE ||
                now >= access.expiresAt || now >= family.expiresAt
            ) return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            val authorization = when (val validation = validateBoundAuthorization(family)) {
                is BoundAuthorization.Valid -> validation
                BoundAuthorization.Invalid -> {
                    revokeInvalidAuthorization(family, now, request = null)
                    return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
                }
                is BoundAuthorization.Unavailable -> return IdentityOperationResult.Failure(validation.code)
            }
            val principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.DEVICE,
                userId = authorization.user.id,
                displayName = authorization.user.displayName,
                assurance = AuthenticationAssurance.DEVICE_TOKEN,
                authenticatedAt = access.createdAt,
                deviceTokenFamilyId = family.id,
                directCapabilities = family.capabilities
            )
            return IdentityOperationResult.Success(
                IdentityContext(principal = principal, organization = authorization.organization)
            )
        } finally {
            parsed.secret.fill(0)
        }
    }

    private suspend fun recordPoll(
        grant: DeviceGrant,
        now: Instant,
        request: AuditRequestMetadata?
    ): DeviceTokenEndpointResult {
        val tooFast = grant.lastPolledAt?.let {
            now < it + grant.pollingIntervalSeconds.seconds
        } == true
        val interval = if (tooFast) min(grant.pollingIntervalSeconds + SLOW_DOWN_INCREMENT_SECONDS, 300)
        else grant.pollingIntervalSeconds
        val replacement = grant.copy(
            pollingIntervalSeconds = interval,
            pollCount = grant.pollCount + 1,
            lastPolledAt = now,
            version = grant.version + 1
        )
        val reason = if (tooFast) "device_poll_slow_down" else "device_poll_pending"
        return when (val result = store.compareAndSetDeviceGrant(
            CompareAndSetDeviceGrantCommand(
                grant.version,
                replacement,
                grantAudit(replacement, AuditActor(AuditActorType.ANONYMOUS), now, reason, request)
            )
        )) {
            is StoreResult.Success -> oauthError(
                if (tooFast) OAuthDeviceErrorCode.SLOW_DOWN else OAuthDeviceErrorCode.AUTHORIZATION_PENDING,
                interval
            )
            is StoreResult.Failure -> DeviceTokenEndpointResult.Unavailable(result.error.toIdentityErrorCode())
        }
    }

    private suspend fun exchange(
        grant: DeviceGrant,
        now: Instant,
        request: AuditRequestMetadata?
    ): DeviceTokenEndpointResult {
        val family = DeviceTokenFamily(
            id = ids.newDeviceTokenFamilyId(),
            deviceGrantId = grant.id,
            clientId = grant.clientId,
            userId = requireNotNull(grant.userId),
            organizationId = requireNotNull(grant.organizationId),
            membershipId = requireNotNull(grant.membershipId),
            membershipVersion = requireNotNull(grant.membershipVersion),
            capabilities = grant.approvedCapabilities,
            createdAt = now,
            expiresAt = now + config.lifetimes.deviceRefreshToken.seconds.seconds
        )
        when (val authorization = validateBoundAuthorization(family)) {
            is BoundAuthorization.Valid -> Unit
            BoundAuthorization.Invalid -> {
                cancelInvalidAuthorizationGrant(grant, now, request)
                return oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
            }
            is BoundAuthorization.Unavailable -> {
                return DeviceTokenEndpointResult.Unavailable(authorization.code)
            }
        }
        val issued = issueTokenPair(family, 0, now)
        val audit = tokenAudit(
            family,
            AuditAction.DEVICE_TOKEN_ISSUED,
            AuditOutcome.SUCCEEDED,
            now,
            null,
            request
        )
        return when (val result = store.exchangeDeviceGrant(
            ExchangeDeviceGrantCommand(
                grant.id,
                grant.version,
                family,
                issued.access,
                issued.refresh,
                now,
                audit
            )
        )) {
            is StoreResult.Success -> DeviceTokenEndpointResult.Success(issued.response(now, family.capabilities))
            is StoreResult.Failure -> when (result.error.code) {
                IdentityStoreErrorCode.INVALID_TRANSITION -> {
                    if (validateBoundAuthorization(family) == BoundAuthorization.Invalid) {
                        cancelInvalidAuthorizationGrant(grant, now, request)
                    }
                    oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
                }
                IdentityStoreErrorCode.VERSION_CONFLICT -> oauthError(OAuthDeviceErrorCode.INVALID_GRANT)
                else -> DeviceTokenEndpointResult.Unavailable(result.error.toIdentityErrorCode())
            }
        }
    }

    /**
     * Resolves the exact membership snapshot from which a grant or token family was issued. The
     * resolver probe carries no direct scopes, so application role mappings are re-evaluated from
     * the current membership and can only preserve (never expand) the family's approved scopes.
     */
    private suspend fun validateBoundAuthorization(family: DeviceTokenFamily): BoundAuthorization {
        val user = when (val found = store.findUser(family.userId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return BoundAuthorization.Unavailable(found.error.toIdentityErrorCode())
        } ?: return BoundAuthorization.Invalid
        val organization = when (val found = store.findOrganization(family.organizationId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return BoundAuthorization.Unavailable(found.error.toIdentityErrorCode())
        } ?: return BoundAuthorization.Invalid
        val membership = when (val found = store.findMembership(family.membershipId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return BoundAuthorization.Unavailable(found.error.toIdentityErrorCode())
        } ?: return BoundAuthorization.Invalid
        if (user.state != UserState.ACTIVE || organization.state != OrganizationState.ACTIVE ||
            membership.state != MembershipState.ACTIVE || membership.version != family.membershipVersion ||
            membership.userId != family.userId || membership.organizationId != family.organizationId
        ) return BoundAuthorization.Invalid
        val resolutionPrincipal = IdentityPrincipal(
            kind = IdentityPrincipalKind.DEVICE,
            userId = user.id,
            displayName = user.displayName,
            assurance = AuthenticationAssurance.DEVICE_TOKEN,
            authenticatedAt = family.createdAt,
            deviceTokenFamilyId = family.id
        )
        val resolutionContext = IdentityContext(
            principal = resolutionPrincipal,
            organization = organization,
            membership = membership
        )
        val currentlyGranted = membership.role.capabilities + capabilityResolver.resolve(resolutionContext)
        if (!currentlyGranted.containsAll(family.capabilities)) return BoundAuthorization.Invalid
        return BoundAuthorization.Valid(user, organization, membership)
    }

    private suspend fun cancelInvalidAuthorizationGrant(
        grant: DeviceGrant,
        now: Instant,
        request: AuditRequestMetadata?
    ) {
        if (grant.state != DeviceGrantState.AUTHORIZED) return
        val cancelled = grant.copy(
            state = DeviceGrantState.CANCELLED,
            version = grant.version + 1,
            cancelledAt = now
        )
        store.compareAndSetDeviceGrant(
            CompareAndSetDeviceGrantCommand(
                grant.version,
                cancelled,
                grantAudit(
                    cancelled,
                    AuditActor(AuditActorType.USER, userId = grant.userId),
                    now,
                    "membership_authorization_changed",
                    request
                )
            )
        )
    }

    private suspend fun revokeInvalidAuthorization(
        family: DeviceTokenFamily,
        now: Instant,
        request: AuditRequestMetadata?
    ) {
        if (family.state != DeviceTokenFamilyState.ACTIVE) return
        store.revokeDeviceTokenFamily(
            RevokeDeviceTokenFamilyCommand(
                familyId = family.id,
                expectedFamilyVersion = family.version,
                revokedAt = now,
                reasonCode = "membership_authorization_changed",
                auditEvent = tokenAudit(
                    family,
                    AuditAction.DEVICE_TOKEN_REVOKED,
                    AuditOutcome.SUCCEEDED,
                    now,
                    "membership_authorization_changed",
                    request
                )
            )
        )
    }

    private suspend fun issueTokenPair(
        family: DeviceTokenFamily,
        rotationCounter: Long,
        now: Instant
    ): IssuedTokenPair {
        val accessId = ids.newDeviceAccessTokenId()
        val refreshId = ids.newDeviceRefreshTokenId()
        val accessSecret = runtime.secureRandom.nextBytes(ACCESS_TOKEN_BYTES)
        val refreshSecret = runtime.secureRandom.nextBytes(REFRESH_TOKEN_BYTES)
        require(accessSecret.size == ACCESS_TOKEN_BYTES && refreshSecret.size == REFRESH_TOKEN_BYTES) {
            "Secure random provider returned invalid device token material"
        }
        return try {
            val access = DeviceAccessToken(
                id = accessId,
                familyId = family.id,
                publicSelector = accessId.value,
                secretDigest = digestToken(ACCESS_TOKEN_CONTEXT, accessId.value, accessSecret),
                createdAt = now,
                expiresAt = Instant.fromEpochMilliseconds(
                    min(
                        (now + config.lifetimes.deviceAccessToken.seconds.seconds).toEpochMilliseconds(),
                        family.expiresAt.toEpochMilliseconds()
                    )
                )
            )
            val refresh = DeviceRefreshToken(
                id = refreshId,
                familyId = family.id,
                publicSelector = refreshId.value,
                secretDigest = digestToken(REFRESH_TOKEN_CONTEXT, refreshId.value, refreshSecret),
                rotationCounter = rotationCounter,
                createdAt = now,
                expiresAt = family.expiresAt
            )
            IssuedTokenPair(
                access,
                refresh,
                "${accessId.value}.${Base64Url.encode(accessSecret)}",
                "${refreshId.value}.${Base64Url.encode(refreshSecret)}"
            )
        } finally {
            accessSecret.fill(0)
            refreshSecret.fill(0)
        }
    }

    private suspend fun findGrantByDeviceCode(bytes: ByteArray): DeviceGrant? {
        for (reference in listOf(config.keys.deviceTokenPepper) + config.keys.previousDeviceTokenPeppers) {
            val digest = digestDeviceSecret(DEVICE_CODE_CONTEXT, bytes, reference)
            when (val found = store.findDeviceGrantByDeviceCodeDigest(digest)) {
                is StoreResult.Success -> found.value?.let { return it }
                is StoreResult.Failure -> return null
            }
        }
        return null
    }

    private suspend fun findGrantByUserCode(code: String): DeviceGrant? {
        val bytes = code.encodeToByteArray()
        try {
            for (reference in listOf(config.keys.deviceTokenPepper) + config.keys.previousDeviceTokenPeppers) {
                val digest = digestDeviceSecret(USER_CODE_CONTEXT, bytes, reference)
                when (val found = store.findDeviceGrantByUserCodeDigest(digest)) {
                    is StoreResult.Success -> found.value?.let { return it }
                    is StoreResult.Failure -> return null
                }
            }
            return null
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun digestDeviceSecret(
        context: String,
        secret: ByteArray,
        reference: SecretReference = config.keys.deviceTokenPepper
    ): SecretDigest {
        val input = "$context\u0000".encodeToByteArray() + secret
        return try {
            val digest = runtime.crypto.hmacSha256(runtime.secrets.resolve(reference), input)
            try {
                SecretDigest(DigestAlgorithm.HMAC_SHA256, Base64Url.encode(digest), reference.version)
            } finally {
                digest.fill(0)
            }
        } finally {
            input.fill(0)
        }
    }

    private suspend fun digestToken(
        context: String,
        selector: String,
        secret: ByteArray,
        reference: SecretReference = config.keys.deviceTokenPepper
    ): SecretDigest {
        val input = "$context\u0000$selector\u0000".encodeToByteArray() + secret
        return try {
            val digest = runtime.crypto.hmacSha256(runtime.secrets.resolve(reference), input)
            try {
                SecretDigest(DigestAlgorithm.HMAC_SHA256, Base64Url.encode(digest), reference.version)
            } finally {
                digest.fill(0)
            }
        } finally {
            input.fill(0)
        }
    }

    private suspend fun verifyTokenDigest(
        context: String,
        parsed: ParsedSelectorSecret,
        stored: SecretDigest
    ): Boolean {
        if (stored.algorithm != DigestAlgorithm.HMAC_SHA256) return false
        val reference = config.keys.deviceTokenPepper(stored.keyVersion) ?: return false
        val actual = digestToken(context, parsed.selector, parsed.secret, reference)
        val expectedBytes = runCatching { Base64Url.decode(stored.encoded, maximumBytes = 32) }.getOrNull()
            ?: return false
        val actualBytes = runCatching { Base64Url.decode(actual.encoded, maximumBytes = 32) }.getOrNull()
            ?: return false
        return try {
            expectedBytes.size == 32 && actualBytes.size == 32 &&
                runtime.crypto.constantTimeEquals(expectedBytes, actualBytes)
        } finally {
            expectedBytes.fill(0)
            actualBytes.fill(0)
        }
    }

    private fun decodeDeviceCode(value: String): ByteArray? = runCatching {
        Base64Url.decode(value, maximumBytes = DEVICE_CODE_BYTES)
    }.getOrNull()?.takeIf { it.size == DEVICE_CODE_BYTES }

    private fun normalizeUserCode(value: String): String? {
        val normalized = value.trim().uppercase()
        return normalized.takeIf {
            it.length == 9 && it[4] == '-' &&
                (it.take(4) + it.takeLast(4)).all(USER_CODE_ALPHABET::contains)
        }
    }

    private fun parseSelectorSecret(value: String, secretBytes: Int): ParsedSelectorSecret? {
        if (value.length !in 20..512 || value.count { it == '.' } != 1) return null
        val selector = value.substringBefore('.')
        if (runCatching { requireValidIdentityId(selector, "token selector") }.isFailure) return null
        val secret = runCatching { Base64Url.decode(value.substringAfter('.'), maximumBytes = secretBytes) }.getOrNull()
            ?: return null
        if (secret.size != secretBytes) {
            secret.fill(0)
            return null
        }
        return ParsedSelectorSecret(selector, secret)
    }

    private fun encodeUserCode(bytes: ByteArray): String {
        require(bytes.size == USER_CODE_BYTES)
        val output = StringBuilder(9)
        var accumulator = 0
        var bits = 0
        bytes.forEach { byte ->
            accumulator = (accumulator shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                output.append(USER_CODE_ALPHABET[(accumulator ushr bits) and 31])
            }
        }
        output.insert(4, '-')
        return output.toString()
    }

    private fun grantAudit(
        grant: DeviceGrant,
        actor: AuditActor,
        at: Instant,
        reasonCode: String,
        request: AuditRequestMetadata?,
        organizationId: OrganizationId? = grant.organizationId
    ): AuditEvent = AuditEvent(
        id = ids.newAuditEventId(),
        actor = actor,
        organizationId = organizationId,
        action = AuditAction.DEVICE_GRANT_CHANGED,
        target = AuditTarget(AuditTargetType.DEVICE_GRANT, grant.id.value),
        outcome = AuditOutcome.SUCCEEDED,
        reasonCode = reasonCode,
        request = request,
        occurredAt = at
    )

    private fun tokenAudit(
        family: DeviceTokenFamily,
        action: AuditAction,
        outcome: AuditOutcome,
        at: Instant,
        reasonCode: String?,
        request: AuditRequestMetadata?
    ): AuditEvent = AuditEvent(
        id = ids.newAuditEventId(),
        actor = AuditActor(AuditActorType.USER, userId = family.userId),
        organizationId = family.organizationId,
        action = action,
        target = AuditTarget(AuditTargetType.DEVICE_GRANT, family.deviceGrantId.value),
        outcome = outcome,
        reasonCode = reasonCode,
        request = request,
        occurredAt = at
    )

    private data class ParsedSelectorSecret(val selector: String, val secret: ByteArray)

    private sealed interface BoundAuthorization {
        data class Valid(
            val user: User,
            val organization: Organization,
            val membership: Membership
        ) : BoundAuthorization

        data object Invalid : BoundAuthorization
        data class Unavailable(val code: IdentityErrorCode) : BoundAuthorization
    }

    private data class IssuedTokenPair(
        val access: DeviceAccessToken,
        val refresh: DeviceRefreshToken,
        val accessValue: String,
        val refreshValue: String
    ) {
        fun response(now: Instant, capabilities: Set<Capability>): OAuthDeviceTokenResponse =
            OAuthDeviceTokenResponse(
                accessToken = accessValue,
                expiresIn = (access.expiresAt - now).inWholeSeconds,
                refreshToken = refreshValue,
                scope = capabilities.map(Capability::wireName).sorted().joinToString(" ")
            )

        override fun toString(): String = "IssuedTokenPair(<redacted>)"
    }

    companion object {
        const val DEVICE_CODE_BYTES = 32
        const val USER_CODE_BYTES = 5
        const val ACCESS_TOKEN_BYTES = 32
        const val REFRESH_TOKEN_BYTES = 32
        const val SLOW_DOWN_INCREMENT_SECONDS = 5
        private const val USER_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val DEVICE_CODE_CONTEXT = "aether-device-code-v1"
        private const val USER_CODE_CONTEXT = "aether-device-user-code-v1"
        private const val ACCESS_TOKEN_CONTEXT = "aether-device-access-token-v1"
        private const val REFRESH_TOKEN_CONTEXT = "aether-device-refresh-token-v1"
    }
}

private fun <T> IdentityOperationResult<T>.mapToUnit(): IdentityOperationResult<Unit> = when (this) {
    is IdentityOperationResult.Success -> IdentityOperationResult.Success(Unit)
    is IdentityOperationResult.Failure -> this
}

private fun oauthError(code: OAuthDeviceErrorCode, interval: Int? = null): DeviceTokenEndpointResult.Error =
    DeviceTokenEndpointResult.Error(code, interval)

private fun isValidClientId(clientId: String): Boolean =
    clientId.length in 1..200 && clientId.all { it.code in 0x21..0x7e }
