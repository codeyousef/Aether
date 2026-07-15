package codes.yousef.aether.auth

import codes.yousef.aether.auth.webauthn.AuthenticationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.RegistrationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.WebAuthnCeremonyBinding
import codes.yousef.aether.auth.webauthn.WebAuthnService
import codes.yousef.aether.auth.webauthn.issueWebAuthnCeremonyBinding
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.TrustedProxyResolver
import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.core.respondJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Limits and cookie conventions for the framework-neutral identity HTTP dispatcher.
 *
 * The authority deliberately accepts complete request bodies only after checking both the declared
 * and observed sizes. Request adapters should additionally enforce this limit while streaming.
 */
data class IdentityHttpApiConfig(
    val maximumJsonBodyBytes: Int = 1_048_576,
    val maximumFormBodyBytes: Int = 32_768,
    val requestIdHeader: String = DEFAULT_IDENTITY_REQUEST_ID_HEADER,
    val csrfHeader: String = "X-CSRF-Token",
    val ceremonyCookieName: String = "__Host-aether_ceremony"
) {
    init {
        require(maximumJsonBodyBytes in 4_096..4_194_304) { "JSON body limit must be 4 KiB..4 MiB" }
        require(maximumFormBodyBytes in 1_024..65_536) { "Form body limit must be 1 KiB..64 KiB" }
        require(requestIdHeader.matches(HTTP_TOKEN)) { "Invalid request-ID header name" }
        require(csrfHeader.matches(HTTP_TOKEN)) { "Invalid CSRF header name" }
        require(ceremonyCookieName.matches(HTTP_TOKEN)) { "Invalid ceremony cookie name" }
    }
}

@Serializable
data class PasskeyRegistrationFinishRequest(
    val ceremonyId: ChallengeId,
    val credentialName: String,
    val credential: RegistrationPublicKeyCredentialDto
) {
    override fun toString(): String =
        "PasskeyRegistrationFinishRequest(ceremonyId=$ceremonyId, credentialName=<redacted>, credential=<redacted>)"
}

@Serializable
data class PasskeyAuthenticationFinishRequest(
    val ceremonyId: ChallengeId,
    val credential: AuthenticationPublicKeyCredentialDto,
    val deviceLabel: String? = null,
    val devicePlatform: String? = null
) {
    init {
        require(deviceLabel == null || deviceLabel.length <= 200) { "Device label is too long" }
        require(devicePlatform == null || devicePlatform.length <= 200) { "Device platform is too long" }
    }

    override fun toString(): String =
        "PasskeyAuthenticationFinishRequest(ceremonyId=$ceremonyId, credential=<redacted>, " +
            "deviceLabel=${if (deviceLabel == null) "none" else "<redacted>"}, " +
            "devicePlatform=${if (devicePlatform == null) "none" else "<redacted>"})"
}

@Serializable
private data class WebAuthnFinishEnvelope(val ceremonyId: ChallengeId)

private sealed interface WebAuthnFinishDecode<out T> {
    data class Decoded<T>(val body: T) : WebAuthnFinishDecode<T>
    data class Malformed(val ceremonyId: ChallengeId?) : WebAuthnFinishDecode<Nothing>
}

@Serializable
data class RecoveryCodeUseRequest(
    val code: String,
    val deviceLabel: String? = null,
    val devicePlatform: String? = null
) {
    init {
        require(code.length in 20..128) { "Invalid recovery code" }
        require(deviceLabel == null || deviceLabel.length <= 200) { "Device label is too long" }
        require(devicePlatform == null || devicePlatform.length <= 200) { "Device platform is too long" }
    }

    override fun toString(): String =
        "RecoveryCodeUseRequest(code=<redacted>, " +
            "deviceLabel=${if (deviceLabel == null) "none" else "<redacted>"}, " +
            "devicePlatform=${if (devicePlatform == null) "none" else "<redacted>"})"
}

/** A safe registration result. Credential material and stored digests are intentionally omitted. */
@Serializable
data class PasskeyRegistrationResponse(
    val passkey: PasskeyView,
    /** Present only after recovery enrollment and shown exactly once. */
    val replacementRecoveryCodes: List<String>? = null
) {
    override fun toString(): String =
        "PasskeyRegistrationResponse(passkey=${passkey.id}, replacementRecoveryCodes=" +
            if (replacementRecoveryCodes == null) "none)" else "<redacted>)"
}

/** Safe session result. The secret is carried only by the HttpOnly cookie. */
@Serializable
data class IdentitySessionCreatedResponse(
    val userId: UserId,
    val sessionId: SessionId,
    val assurance: AuthenticationAssurance,
    val authenticatedAt: Instant,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
    val csrfToken: String
) {
    override fun toString(): String =
        "IdentitySessionCreatedResponse(userId=$userId, sessionId=$sessionId, assurance=$assurance, " +
            "authenticatedAt=$authenticatedAt, idleExpiresAt=$idleExpiresAt, " +
            "absoluteExpiresAt=$absoluteExpiresAt, csrfToken=<redacted>)"
}

@Serializable
data class IdentityOkResponse(val ok: Boolean = true)

/**
 * Public, storage-neutral HTTP surface for the passkey authority and RFC 8628 token endpoints.
 *
 * Install optional identity resolution before this middleware. This dispatcher performs its own
 * session-bound CSRF check for unsafe cookie-authenticated calls, so query-string CSRF values are
 * never accepted. Unknown routes fall through to the next middleware.
 */
class IdentityHttpApi(
    private val runtime: IdentityRuntime,
    private val identityConfig: IdentityConfig,
    private val webAuthn: WebAuthnService,
    private val recovery: IdentityRecoveryService,
    private val accounts: IdentityAccountManagementService,
    private val deviceAuthorization: IdentityDeviceAuthorizationService,
    private val httpConfig: IdentityHttpApiConfig = IdentityHttpApiConfig(),
    private val management: IdentityHttpManagementServices = IdentityHttpManagementServices(),
    private val recoveryAttemptLimiter: IdentityRecoveryAttemptLimiter? = null
) {
    private val auditRedactor = IdentityAuditRedactor(runtime, identityConfig)
    private val trustedProxyResolver = TrustedProxyResolver(
        identityConfig.trustedProxy.trustedCidrs
    )
    private val enforceExternalAuthority = identityConfig.environment == IdentityEnvironment.PRODUCTION ||
        identityConfig.environment == IdentityEnvironment.STAGING
    private val publicScheme = identityConfig.publicBaseUrl.substringBefore("://").lowercase()
    private val publicHost = identityConfig.publicBaseUrl.substringAfter("://").lowercase()
    private val json: Json = DEFAULT_IDENTITY_JSON

    init {
        require(identityConfig.environment != IdentityEnvironment.PRODUCTION || recoveryAttemptLimiter != null) {
            "Production identity HTTP authorities require a recovery attempt limiter"
        }
    }

    fun asMiddleware(): Middleware = middleware@{ exchange, next ->
        val route = route(exchange) ?: run {
            next()
            return@middleware
        }
        val requestId = exchange.ensureIdentityRequestId(runtime.secureRandom, httpConfig.requestIdHeader)
        exchange.response.setHeader("Cache-Control", "no-store")

        try {
            if (!hasValidRequestAuthority(exchange)) {
                exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
                return@middleware
            }
            if (containsQueryCsrf(exchange) || !validateCsrf(exchange)) {
                exchange.respondIdentityError(IdentityErrorCode.CSRF_INVALID, requestId)
                return@middleware
            }
            if (exchange.identityContext.session?.assurance == AuthenticationAssurance.RECOVERY &&
                route != Route.RegistrationStart && route != Route.RegistrationFinish && route != Route.Logout
            ) {
                exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
                return@middleware
            }
            dispatch(route, exchange, requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Provider, parser, and storage exception text must never cross the wire boundary.
            exchange.respondIdentityError(IdentityErrorCode.INTERNAL_ERROR, requestId)
        }
    }

    private suspend fun dispatch(route: Route, exchange: Exchange, requestId: String) {
        when (route) {
            Route.Me -> me(exchange, requestId)
            Route.RegistrationStart -> registrationStart(exchange, requestId)
            Route.RegistrationFinish -> registrationFinish(exchange, requestId)
            Route.AuthenticationStart -> authenticationStart(exchange, requestId, stepUp = false)
            Route.AuthenticationFinish -> authenticationFinish(exchange, requestId, stepUp = false)
            Route.StepUpStart -> authenticationStart(exchange, requestId, stepUp = true)
            Route.StepUpFinish -> authenticationFinish(exchange, requestId, stepUp = true)
            Route.ListSessions -> listSessions(exchange, requestId)
            Route.Logout -> logout(exchange, requestId)
            is Route.RevokeSession -> revokeSession(exchange, requestId, route.encodedId)
            Route.RevokeOtherSessions -> revokeOtherSessions(exchange, requestId)
            Route.RevokeAllSessions -> revokeAllSessions(exchange, requestId)
            Route.ListPasskeys -> listPasskeys(exchange, requestId)
            is Route.RenamePasskey -> renamePasskey(exchange, requestId, route.encodedId)
            is Route.RevokePasskey -> revokePasskey(exchange, requestId, route.encodedId)
            Route.ReplaceRecoveryCodes -> replaceRecoveryCodes(exchange, requestId)
            Route.UseRecoveryCode -> useRecoveryCode(exchange, requestId)
            Route.IssueAdministrativeRecovery -> issueAdministrativeRecovery(exchange, requestId)
            is Route.CancelAdministrativeRecovery -> cancelAdministrativeRecovery(
                exchange, requestId, route.encodedId
            )
            Route.RedeemAdministrativeRecovery -> redeemAdministrativeRecovery(exchange, requestId)
            Route.ListOrganizations -> listOrganizations(exchange, requestId)
            Route.CreateOrganization -> createOrganization(exchange, requestId)
            is Route.GetOrganization -> getOrganization(exchange, requestId, route.organizationId)
            is Route.UpdateOrganization -> updateOrganization(exchange, requestId, route.organizationId)
            is Route.DeleteOrganization -> deleteOrganization(exchange, requestId, route.organizationId)
            is Route.ListMemberships -> listMemberships(exchange, requestId, route.organizationId)
            is Route.ListAuditEvents -> listAuditEvents(exchange, requestId, route.organizationId)
            is Route.UpdateMembership -> updateMembership(
                exchange, requestId, route.organizationId, route.membershipId
            )
            is Route.RemoveMembership -> removeMembership(
                exchange, requestId, route.organizationId, route.membershipId
            )
            is Route.ListInvitations -> listInvitations(exchange, requestId, route.organizationId)
            is Route.CreateInvitation -> createInvitation(exchange, requestId, route.organizationId)
            is Route.RevokeInvitation -> revokeInvitation(
                exchange, requestId, route.organizationId, route.invitationId
            )
            Route.AcceptInvitation -> acceptInvitation(exchange, requestId)
            Route.EnrollInvitation -> enrollInvitation(exchange, requestId)
            Route.InspectDeviceGrant -> inspectDeviceGrant(exchange, requestId)
            Route.ApproveDeviceGrant -> approveDeviceGrant(exchange, requestId)
            Route.DenyDeviceGrant -> denyDeviceGrant(exchange, requestId)
            Route.CancelDeviceGrant -> cancelDeviceGrant(exchange, requestId)
            Route.DeviceAuthorization -> startDeviceAuthorization(exchange, requestId)
            Route.DeviceToken -> deviceToken(exchange, requestId)
            Route.DeviceTokenRevoke -> revokeDeviceToken(exchange, requestId)
            is Route.ListServiceIdentities -> listServiceIdentities(exchange, requestId, route.organizationId)
            is Route.CreateServiceIdentity -> createServiceIdentity(exchange, requestId, route.organizationId)
            is Route.RevokeServiceIdentity -> revokeServiceIdentity(
                exchange, requestId, route.organizationId, route.serviceIdentityId
            )
            is Route.ListServiceCredentials -> listServiceCredentials(
                exchange, requestId, route.organizationId, route.serviceIdentityId
            )
            is Route.CreateServiceCredential -> createServiceCredential(
                exchange, requestId, route.organizationId, route.serviceIdentityId
            )
            is Route.RotateServiceCredential -> rotateServiceCredential(
                exchange, requestId, route.organizationId, route.serviceIdentityId, route.credentialId
            )
            is Route.RevokeServiceCredential -> revokeServiceCredential(
                exchange, requestId, route.organizationId, route.serviceIdentityId, route.credentialId
            )
            Route.Bootstrap -> bootstrap(exchange, requestId)
        }
    }

    private suspend fun me(exchange: Exchange, requestId: String) {
        val principal = exchange.identityContext.principal
        val userId = principal?.userId
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        if (principal.kind != IdentityPrincipalKind.USER && principal.kind != IdentityPrincipalKind.DEVICE) {
            return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        }
        if (principal.kind == IdentityPrincipalKind.DEVICE && Capability.IDENTITY_PROFILE !in principal.directCapabilities) {
            return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        }
        exchange.respondJson(
            200,
            IdentityMeResponse(userId, principal.displayName, principal.assurance),
            json
        )
    }

    private suspend fun registrationStart(exchange: Exchange, requestId: String) {
        val context = exchange.identityContext
        val userId = context.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        val now = runtime.clock.now()
        context.accountSecurityError(
            IdentityAccountSecurityAction.ENROLL_PASSKEY,
            now,
            identityConfig.lifetimes.recentPasskey
        )?.let { return exchange.respondIdentityError(it, requestId) }
        val registrationSource = registrationSource(context, now)
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        if (!identityConfig.allowsRegistration(registrationSource)) {
            return exchange.respondIdentityError(IdentityErrorCode.REGISTRATION_NOT_ALLOWED, requestId)
        }
        val issuedBinding = issueWebAuthnCeremonyBinding(runtime)
        when (val result = webAuthn.startRegistration(userId, issuedBinding.binding, registrationSource)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> {
                exchange.response.setCookie(ceremonyCookie(issuedBinding.cookieValue()))
                exchange.respondJson(200, result.value, json)
            }
        }
    }

    private suspend fun registrationFinish(exchange: Exchange, requestId: String) {
        exchange.response.setCookie(clearCeremonyCookie())
        val body = when (
            val decoded = exchange.decodeWebAuthnFinish<PasskeyRegistrationFinishRequest>(
                httpConfig.maximumJsonBodyBytes
            )
        ) {
            is WebAuthnFinishDecode.Decoded -> decoded.body
            is WebAuthnFinishDecode.Malformed -> {
                val ceremonyId = decoded.ceremonyId
                if (ceremonyId == null) {
                    exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
                } else {
                    rejectFinishAttempt(exchange, ceremonyId, IdentityErrorCode.REQUEST_INVALID, requestId)
                }
                return
            }
        }
        val context = exchange.identityContext
        val userId = context.authenticatedUserId()
        if (userId == null) {
            rejectFinishAttempt(exchange, body.ceremonyId, IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
            return
        }
        val now = runtime.clock.now()
        val securityError = context.accountSecurityError(
            IdentityAccountSecurityAction.ENROLL_PASSKEY,
            now,
            identityConfig.lifetimes.recentPasskey
        )
        if (securityError != null) {
            rejectFinishAttempt(exchange, body.ceremonyId, securityError, requestId)
            return
        }
        val registrationSource = registrationSource(context, now)
        if (registrationSource == null) {
            rejectFinishAttempt(exchange, body.ceremonyId, IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
            return
        }
        val binding = ceremonyBinding(exchange)
        val recoverySession = context.session?.takeIf { it.assurance == AuthenticationAssurance.RECOVERY }
        val result = webAuthn.finishRegistration(
            ceremonyId = body.ceremonyId,
            credentialName = body.credentialName,
            browserCredential = body.credential,
            binding = binding,
            registrationSource = registrationSource,
            request = auditMetadata(exchange, requestId),
            recoverySession = recoverySession,
            expectedUserId = userId
        )
        when (result) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> {
                if (result.value.clearRecoverySessionCookie) {
                    exchange.response.setCookie(clearSessionCookie())
                }
                exchange.respondJson(
                    200,
                    PasskeyRegistrationResponse(
                        passkey = result.value.credential.toPasskeyView(),
                        replacementRecoveryCodes = result.value.replacementRecoveryCodes?.codes?.map { it.reveal() }
                    ),
                    json
                )
            }
        }
    }

    private suspend fun authenticationStart(exchange: Exchange, requestId: String, stepUp: Boolean) {
        val issuedBinding = issueWebAuthnCeremonyBinding(runtime)
        val result = if (stepUp) {
            val userId = exchange.identityContext.authenticatedUserId()
                ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
            webAuthn.startStepUp(userId, issuedBinding.binding)
        } else {
            webAuthn.startAuthentication(issuedBinding.binding)
        }
        when (result) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> {
                exchange.response.setCookie(ceremonyCookie(issuedBinding.cookieValue()))
                exchange.respondJson(200, result.value, json)
            }
        }
    }

    private suspend fun authenticationFinish(exchange: Exchange, requestId: String, stepUp: Boolean) {
        exchange.response.setCookie(clearCeremonyCookie())
        val body = when (
            val decoded = exchange.decodeWebAuthnFinish<PasskeyAuthenticationFinishRequest>(
                httpConfig.maximumJsonBodyBytes
            )
        ) {
            is WebAuthnFinishDecode.Decoded -> decoded.body
            is WebAuthnFinishDecode.Malformed -> {
                val ceremonyId = decoded.ceremonyId
                if (ceremonyId == null) {
                    exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
                } else {
                    rejectFinishAttempt(exchange, ceremonyId, IdentityErrorCode.REQUEST_INVALID, requestId)
                }
                return
            }
        }
        val context = exchange.identityContext
        if (stepUp && context.authenticatedUserId() == null) {
            rejectFinishAttempt(exchange, body.ceremonyId, IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
            return
        }
        val result = webAuthn.finishAuthentication(
            ceremonyId = body.ceremonyId,
            browserCredential = body.credential,
            binding = ceremonyBinding(exchange),
            rotatedFrom = context.session,
            device = DeviceMetadata(
                label = body.deviceLabel,
                platform = body.devicePlatform,
                userAgent = exchange.request.headers["User-Agent"]?.take(MAX_USER_AGENT_LENGTH)
            ),
            request = auditMetadata(exchange, requestId)
        )
        when (result) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> respondIssuedSession(exchange, result.value.issuedSession)
        }
    }

    private suspend fun listSessions(exchange: Exchange, requestId: String) {
        val context = exchange.identityContext
        val userId = context.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        val sessionId = context.session?.id
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        when (val result = accounts.listSessions(userId, sessionId)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun logout(exchange: Exchange, requestId: String) {
        val context = exchange.identityContext
        val userId = context.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        val sessionId = context.session?.id
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        when (val result = accounts.logout(userId, sessionId, auditMetadata(exchange, requestId))) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> {
                exchange.response.setCookie(clearSessionCookie())
                exchange.respondJson(200, IdentityOkResponse(), json)
            }
        }
    }

    private suspend fun revokeSession(exchange: Exchange, requestId: String, encodedId: String) {
        val context = exchange.identityContext
        val userId = context.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        val currentSessionId = context.session?.id
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        val sessionId = SessionId.parseOrNull(encodedId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = accounts.revokeOne(
            userId,
            sessionId,
            currentSessionId,
            request = auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> {
                if (result.value.clearCurrentCookie) exchange.response.setCookie(clearSessionCookie())
                exchange.respondJson(200, result.value, json)
            }
        }
    }

    private suspend fun revokeOtherSessions(exchange: Exchange, requestId: String) {
        val context = exchange.identityContext
        val userId = context.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        val current = context.session?.id
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        when (val result = accounts.revokeOtherSessions(userId, current, auditMetadata(exchange, requestId))) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun revokeAllSessions(exchange: Exchange, requestId: String) {
        val userId = exchange.identityContext.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        when (val result = accounts.revokeAllSessions(userId, auditMetadata(exchange, requestId))) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> {
                exchange.response.setCookie(clearSessionCookie())
                exchange.respondJson(200, result.value, json)
            }
        }
    }

    private suspend fun listPasskeys(exchange: Exchange, requestId: String) {
        val userId = exchange.identityContext.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        when (val result = accounts.listPasskeys(userId)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun renamePasskey(exchange: Exchange, requestId: String, encodedId: String) {
        val userId = exchange.identityContext.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        val credentialId = CredentialId.parseOrNull(encodedId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val body = exchange.decodeJson<RenamePasskeyRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = accounts.renamePasskey(
            userId, credentialId, body.name, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun revokePasskey(exchange: Exchange, requestId: String, encodedId: String) {
        val context = exchange.identityContext
        val userId = context.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        context.accountSecurityError(
            IdentityAccountSecurityAction.REVOKE_PASSKEY,
            runtime.clock.now(),
            identityConfig.lifetimes.recentPasskey
        )?.let { return exchange.respondIdentityError(it, requestId) }
        val credentialId = CredentialId.parseOrNull(encodedId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = accounts.revokePasskey(
            userId, credentialId, request = auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun replaceRecoveryCodes(exchange: Exchange, requestId: String) {
        val context = exchange.identityContext
        val userId = context.authenticatedUserId()
            ?: return exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED, requestId)
        context.accountSecurityError(
            IdentityAccountSecurityAction.REPLACE_RECOVERY_CODES,
            runtime.clock.now(),
            identityConfig.lifetimes.recentPasskey
        )?.let { return exchange.respondIdentityError(it, requestId) }
        val body = exchange.decodeJson<ReplaceRecoveryCodesRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = recovery.replaceCodes(
            userId = userId,
            expectedGeneration = body.expectedGeneration,
            request = auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(
                200,
                RecoveryCodesResponse(result.value.generation, result.value.codes.map { it.reveal() }),
                json
            )
        }
    }

    private suspend fun issueAdministrativeRecovery(exchange: Exchange, requestId: String) {
        val service = management.administrativeRecovery
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val body = exchange.decodeJson<AdministrativeRecoveryIssueRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.issueTicket(
            exchange.identityContext, body.userId, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(201, result.value, json)
        }
    }

    private suspend fun cancelAdministrativeRecovery(
        exchange: Exchange,
        requestId: String,
        encodedId: String
    ) {
        val service = management.administrativeRecovery
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val ticketId = ChallengeId.parseOrNull(encodedId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.cancelTicket(
            exchange.identityContext, ticketId, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun redeemAdministrativeRecovery(exchange: Exchange, requestId: String) {
        val service = management.administrativeRecovery
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val body = exchange.decodeJson<AdministrativeRecoveryRedeemRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.redeemTicket(
            body.token,
            DeviceMetadata(
                label = body.deviceLabel,
                platform = body.devicePlatform,
                userAgent = exchange.request.headers["User-Agent"]?.take(MAX_USER_AGENT_LENGTH)
            ),
            auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> respondIssuedSession(exchange, result.value.issuedSession)
        }
    }

    private suspend fun listOrganizations(exchange: Exchange, requestId: String) {
        val context = exchange.identityContext
        if (context.principal?.kind == IdentityPrincipalKind.DEVICE) {
            if (Capability.IDENTITY_ORGANIZATIONS !in context.principal.directCapabilities) {
                return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
            }
            val organization = context.organization
                ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
            exchange.respondJson(
                200,
                listOf(OrganizationAccessView(organization.id, organization.name, organization.slug, "device")),
                json
            )
            return
        }
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        when (val result = service.listOrganizationAccess(context)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun getOrganization(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String
    ) {
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val context = exchange.identityContext
        if (context.principal?.kind == IdentityPrincipalKind.DEVICE) {
            if (Capability.IDENTITY_ORGANIZATIONS !in context.principal.directCapabilities) {
                return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
            }
            val organization = context.organization?.takeIf { it.id == organizationId && it.state == OrganizationState.ACTIVE }
                ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
            exchange.respondJson(
                200,
                OrganizationAccessView(organization.id, organization.name, organization.slug, "device"),
                json
            )
            return
        }
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        when (val result = service.getOrganizationAccess(context, organizationId)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun createOrganization(exchange: Exchange, requestId: String) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val body = exchange.decodeJson<CreateOrganizationRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.createOrganization(
            exchange.identityContext, body.name, body.slug, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(201, result.value, json)
        }
    }

    private suspend fun updateOrganization(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String
    ) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val body = exchange.decodeJson<UpdateOrganizationRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.updateOrganization(
            exchange.identityContext, organizationId, body.name, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun deleteOrganization(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String
    ) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.deleteOrganization(
            exchange.identityContext, organizationId, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun listMemberships(exchange: Exchange, requestId: String, encodedOrganizationId: String) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.listMemberships(exchange.identityContext, organizationId)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun listAuditEvents(exchange: Exchange, requestId: String, encodedOrganizationId: String) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val pageQuery = exchange.organizationAuditPageQuery()
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.listAuditEvents(
            actor = exchange.identityContext,
            organizationId = organizationId,
            cursor = pageQuery.cursor,
            limit = pageQuery.limit
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(
                200,
                OrganizationAuditEventsResponse(
                    events = result.value.events,
                    nextCursor = result.value.nextCursor?.toOpaqueToken()
                ),
                json
            )
        }
    }

    private suspend fun updateMembership(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String,
        encodedMembershipId: String
    ) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val membershipId = MembershipId.parseOrNull(encodedMembershipId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val body = exchange.decodeJson<UpdateMembershipRoleRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.changeMembershipRole(
            exchange.identityContext, organizationId, membershipId, body.role, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun removeMembership(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String,
        encodedMembershipId: String
    ) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val membershipId = MembershipId.parseOrNull(encodedMembershipId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.removeMembership(
            exchange.identityContext, organizationId, membershipId, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun listInvitations(exchange: Exchange, requestId: String, encodedOrganizationId: String) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.listInvitations(exchange.identityContext, organizationId)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun createInvitation(exchange: Exchange, requestId: String, encodedOrganizationId: String) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val body = exchange.decodeJson<CreateInvitationRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.invite(
            exchange.identityContext,
            organizationId,
            body.email,
            body.role,
            auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(
                201,
                IssuedInvitationResponse(result.value.invitation, result.value.revealToken()),
                json
            )
        }
    }

    private suspend fun revokeInvitation(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String,
        encodedInvitationId: String
    ) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val invitationId = InvitationId.parseOrNull(encodedInvitationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.revokeInvitation(
            exchange.identityContext, organizationId, invitationId, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun acceptInvitation(exchange: Exchange, requestId: String) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val body = exchange.decodeJson<AcceptInvitationRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.acceptInvitation(
            exchange.identityContext, body.token, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun enrollInvitation(exchange: Exchange, requestId: String) {
        val service = management.organizations
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val body = exchange.decodeJson<EnrollInvitationRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.enrollInvitation(
            body.token,
            body.displayName,
            auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> {
                val issued = result.value.issuedEnrollmentSession
                val session = issued.session
                exchange.response.setCookie(sessionCookie(issued.cookieValue()))
                exchange.respondJson(
                    201,
                    InvitationEnrollmentResponse(
                        userId = result.value.user.id,
                        organizationId = result.value.membership.organizationId,
                        membership = result.value.membership,
                        sessionId = session.id,
                        assurance = session.assurance,
                        authenticatedAt = session.authenticatedAt,
                        idleExpiresAt = session.idleExpiresAt,
                        absoluteExpiresAt = session.absoluteExpiresAt,
                        csrfToken = issued.csrfToken()
                    ),
                    json
                )
            }
        }
    }

    private suspend fun inspectDeviceGrant(exchange: Exchange, requestId: String) {
        val body = exchange.decodeJson<InspectDeviceGrantRequest>(MAX_DEVICE_INSPECTION_BODY_BYTES)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = deviceAuthorization.inspect(body.userCode, exchange.identityContext)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun approveDeviceGrant(exchange: Exchange, requestId: String) {
        val body = exchange.decodeJson<ApproveDeviceGrantRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = deviceAuthorization.approve(
            body.userCode,
            exchange.identityContext,
            body.organizationId,
            body.capabilities,
            auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, IdentityOkResponse(), json)
        }
    }

    private suspend fun denyDeviceGrant(exchange: Exchange, requestId: String) {
        val body = exchange.decodeJson<DenyDeviceGrantRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = deviceAuthorization.deny(
            body.userCode, exchange.identityContext, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, IdentityOkResponse(), json)
        }
    }

    private suspend fun cancelDeviceGrant(exchange: Exchange, requestId: String) {
        val body = exchange.decodeJson<CancelDeviceGrantRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = deviceAuthorization.cancel(body.deviceCode, auditMetadata(exchange, requestId))) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, IdentityOkResponse(), json)
        }
    }

    private suspend fun listServiceIdentities(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String
    ) {
        val service = management.serviceIdentities
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.list(exchange.identityContext, organizationId)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun createServiceIdentity(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String
    ) {
        val service = management.serviceIdentities
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val body = exchange.decodeJson<CreateServiceIdentityRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val lifetime = body.lifetimeSeconds?.let { runCatching { IdentityDuration.seconds(it) }.getOrNull() }
            ?: identityConfig.lifetimes.serviceCredential
        when (val result = service.create(
            exchange.identityContext,
            organizationId,
            body.name,
            body.description,
            body.capabilities,
            lifetime,
            auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(
                201,
                IssuedServiceIdentityResponse(
                    result.value.value.identity,
                    result.value.value.credential,
                    result.value.revealToken()
                ),
                json
            )
        }
    }

    private suspend fun revokeServiceIdentity(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String,
        encodedIdentityId: String
    ) {
        val service = management.serviceIdentities
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val identityId = ServiceIdentityId.parseOrNull(encodedIdentityId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.revokeIdentity(
            exchange.identityContext, organizationId, identityId, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun listServiceCredentials(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String,
        encodedIdentityId: String
    ) {
        val service = management.serviceIdentities
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val identityId = ServiceIdentityId.parseOrNull(encodedIdentityId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.listCredentials(exchange.identityContext, organizationId, identityId)) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun createServiceCredential(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String,
        encodedIdentityId: String
    ) {
        val service = management.serviceIdentities
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val identityId = ServiceIdentityId.parseOrNull(encodedIdentityId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val body = exchange.decodeJson<CreateServiceCredentialRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val lifetime = body.lifetimeSeconds?.let { runCatching { IdentityDuration.seconds(it) }.getOrNull() }
            ?: identityConfig.lifetimes.serviceCredential
        when (val result = service.createCredential(
            exchange.identityContext,
            organizationId,
            identityId,
            body.capabilities,
            lifetime,
            auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(
                201,
                IssuedServiceCredentialResponse(result.value.credential, result.value.revealToken()),
                json
            )
        }
    }

    private suspend fun rotateServiceCredential(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String,
        encodedIdentityId: String,
        encodedCredentialId: String
    ) {
        val service = management.serviceIdentities
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val credentialId = ServiceCredentialId.parseOrNull(encodedCredentialId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val identityId = ServiceIdentityId.parseOrNull(encodedIdentityId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val owned = service.listCredentials(exchange.identityContext, organizationId, identityId)) {
            is IdentityOperationResult.Failure -> return exchange.respondIdentityError(owned.code, requestId)
            is IdentityOperationResult.Success -> if (owned.value.none { it.id == credentialId }) {
                return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
            }
        }
        val body = exchange.decodeJson<RotateServiceCredentialRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val lifetime = body.lifetimeSeconds?.let { runCatching { IdentityDuration.seconds(it) }.getOrNull() }
            ?: identityConfig.lifetimes.serviceCredential
        when (val result = service.rotateCredential(
            exchange.identityContext, organizationId, credentialId, lifetime, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(
                200,
                IssuedServiceCredentialResponse(result.value.credential, result.value.revealToken()),
                json
            )
        }
    }

    private suspend fun revokeServiceCredential(
        exchange: Exchange,
        requestId: String,
        encodedOrganizationId: String,
        encodedIdentityId: String,
        encodedCredentialId: String
    ) {
        val service = management.serviceIdentities
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val organizationId = parseOrganizationId(encodedOrganizationId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val credentialId = ServiceCredentialId.parseOrNull(encodedCredentialId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val identityId = ServiceIdentityId.parseOrNull(encodedIdentityId)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val owned = service.listCredentials(exchange.identityContext, organizationId, identityId)) {
            is IdentityOperationResult.Failure -> return exchange.respondIdentityError(owned.code, requestId)
            is IdentityOperationResult.Success -> if (owned.value.none { it.id == credentialId }) {
                return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
            }
        }
        when (val result = service.revokeCredential(
            exchange.identityContext, organizationId, credentialId, auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun bootstrap(exchange: Exchange, requestId: String) {
        val service = management.bootstrap
            ?: return exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND, requestId)
        val body = exchange.decodeJson<BootstrapIdentityRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = service.bootstrap(
            body.secret,
            body.displayName,
            body.primaryEmail,
            body.organizationName,
            body.organizationSlug,
            auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> {
                val issued = result.value.issuedEnrollmentSession
                exchange.response.setCookie(sessionCookie(issued.cookieValue()))
                exchange.respondJson(
                    201,
                    BootstrapIdentityResponse(
                        result.value.user.id,
                        result.value.organization,
                        result.value.ownerMembership,
                        issued.session.id,
                        issued.session.idleExpiresAt,
                        issued.session.absoluteExpiresAt,
                        issued.csrfToken()
                    ),
                    json
                )
            }
        }
    }

    private suspend fun useRecoveryCode(exchange: Exchange, requestId: String) {
        val limiter = recoveryAttemptLimiter
        if (limiter != null) {
            val allowed = try {
                limiter.allow(recoveryAttempt(exchange))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return exchange.respondIdentityError(IdentityErrorCode.SERVICE_UNAVAILABLE, requestId)
            }
            if (!allowed) return exchange.respondIdentityError(IdentityErrorCode.RATE_LIMITED, requestId)
        }
        val body = exchange.decodeJson<RecoveryCodeUseRequest>(httpConfig.maximumJsonBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        when (val result = recovery.recover(
            encodedCode = body.code,
            device = DeviceMetadata(
                label = body.deviceLabel,
                platform = body.devicePlatform,
                userAgent = exchange.request.headers["User-Agent"]?.take(MAX_USER_AGENT_LENGTH)
            ),
            request = auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> respondIssuedSession(exchange, result.value.issuedSession)
        }
    }

    private suspend fun startDeviceAuthorization(exchange: Exchange, requestId: String) {
        exchange.response.setHeader("Pragma", "no-cache")
        val form = exchange.decodeForm(httpConfig.maximumFormBodyBytes)
            ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
        val clientId = form.single("client_id")
            ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
        val clientNameValues = form["client_name"]
        val clientName = if (clientNameValues == null) {
            clientId
        } else {
            clientNameValues.singleOrNull()
                ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
        }
        val scopeValues = form["scope"]
            ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_SCOPE)
        val scope = scopeValues.singleOrNull()
            ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
        val scopes = parseCapabilities(scope)
            ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_SCOPE)
        val requestError = deviceAuthorization.authorizationRequestError(clientId, clientName, scopes)
        if (requestError != null) return exchange.respondOAuthDeviceError(requestError)
        when (val result = deviceAuthorization.start(
            clientId = clientId,
            requestedCapabilities = scopes,
            clientName = clientName,
            request = auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> if (result.code == IdentityErrorCode.REQUEST_INVALID) {
                exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
            } else {
                exchange.respondIdentityError(result.code, requestId)
            }
            is IdentityOperationResult.Success -> exchange.respondJson(200, result.value, json)
        }
    }

    private suspend fun deviceToken(exchange: Exchange, requestId: String) {
        exchange.response.setHeader("Pragma", "no-cache")
        val form = exchange.decodeForm(httpConfig.maximumFormBodyBytes)
            ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
        val clientId = form.single("client_id")?.takeIf { it.isNotBlank() }
            ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
        val grantType = form.single("grant_type")?.takeIf { it.isNotBlank() }
            ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
        val result = when (grantType) {
            DEVICE_CODE_GRANT_TYPE -> {
                val code = form.single("device_code")?.takeIf { it.isNotBlank() }
                    ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
                deviceAuthorization.poll(code, clientId, auditMetadata(exchange, requestId))
            }
            REFRESH_TOKEN_GRANT_TYPE -> {
                val token = form.single("refresh_token")?.takeIf { it.isNotBlank() }
                    ?: return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.INVALID_REQUEST)
                deviceAuthorization.refresh(token, clientId, auditMetadata(exchange, requestId))
            }
            else -> return exchange.respondOAuthDeviceError(OAuthDeviceErrorCode.UNSUPPORTED_GRANT_TYPE)
        }
        when (result) {
            is DeviceTokenEndpointResult.Success -> exchange.respondJson(200, result.response, json)
            is DeviceTokenEndpointResult.Error -> exchange.respondOAuthDeviceError(result.code)
            is DeviceTokenEndpointResult.Unavailable -> exchange.respondIdentityError(result.code, requestId)
        }
    }

    private suspend fun Exchange.respondOAuthDeviceError(code: OAuthDeviceErrorCode) {
        val requestId = requireNotNull(attributes.get(IdentityRequestIdAttributeKey)) {
            "Identity request ID must be established before dispatch"
        }
        respondJson(400, OAuthDeviceErrorResponse(code, requestId), json)
    }

    private suspend fun revokeDeviceToken(exchange: Exchange, requestId: String) {
        exchange.response.setHeader("Pragma", "no-cache")
        val form = exchange.decodeForm(httpConfig.maximumFormBodyBytes)
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val token = form.single("token")
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        val clientId = form.single("client_id")
            ?: return exchange.respondIdentityError(IdentityErrorCode.REQUEST_INVALID, requestId)
        // RFC 7009 permits ignoring token_type_hint; the presented token remains authoritative.
        when (val result = deviceAuthorization.revokeRefreshToken(
            token,
            clientId,
            auditMetadata(exchange, requestId)
        )) {
            is IdentityOperationResult.Failure -> exchange.respondIdentityError(result.code, requestId)
            is IdentityOperationResult.Success -> {
                exchange.response.statusCode = 200
                exchange.response.end()
            }
        }
    }

    private suspend fun respondIssuedSession(exchange: Exchange, issued: IssuedIdentitySession) {
        exchange.response.setCookie(sessionCookie(issued.cookieValue()))
        val session = issued.session
        exchange.respondJson(
            200,
            IdentitySessionCreatedResponse(
                userId = session.userId,
                sessionId = session.id,
                assurance = session.assurance,
                authenticatedAt = session.authenticatedAt,
                idleExpiresAt = session.idleExpiresAt,
                absoluteExpiresAt = session.absoluteExpiresAt,
                csrfToken = issued.csrfToken()
            ),
            json
        )
    }

    private fun route(exchange: Exchange): Route? {
        val method = exchange.request.method
        val path = exchange.request.path
        return when {
            method == HttpMethod.GET && path == ME -> Route.Me
            method == HttpMethod.POST && path == REGISTRATION_START -> Route.RegistrationStart
            method == HttpMethod.POST && path == REGISTRATION_FINISH -> Route.RegistrationFinish
            method == HttpMethod.POST && path == AUTHENTICATION_START -> Route.AuthenticationStart
            method == HttpMethod.POST && path == AUTHENTICATION_FINISH -> Route.AuthenticationFinish
            method == HttpMethod.POST && path == STEP_UP_START -> Route.StepUpStart
            method == HttpMethod.POST && path == STEP_UP_FINISH -> Route.StepUpFinish
            method == HttpMethod.GET && path == SESSIONS -> Route.ListSessions
            method == HttpMethod.POST && path == LOGOUT -> Route.Logout
            method == HttpMethod.POST && path == REVOKE_OTHERS -> Route.RevokeOtherSessions
            method == HttpMethod.POST && path == REVOKE_ALL -> Route.RevokeAllSessions
            method == HttpMethod.DELETE && path.startsWith("$SESSIONS/") &&
                path.removePrefix("$SESSIONS/").let { it.isNotEmpty() && '/' !in it } ->
                Route.RevokeSession(path.removePrefix("$SESSIONS/"))
            method == HttpMethod.GET && path == PASSKEYS -> Route.ListPasskeys
            method == HttpMethod.PATCH && path.startsWith("$PASSKEYS/") &&
                path.removePrefix("$PASSKEYS/").let { it.isNotEmpty() && '/' !in it } ->
                Route.RenamePasskey(path.removePrefix("$PASSKEYS/"))
            method == HttpMethod.DELETE && path.startsWith("$PASSKEYS/") &&
                path.removePrefix("$PASSKEYS/").let { it.isNotEmpty() && '/' !in it } ->
                Route.RevokePasskey(path.removePrefix("$PASSKEYS/"))
            method == HttpMethod.POST && path == RECOVERY_CODES_REPLACE -> Route.ReplaceRecoveryCodes
            method == HttpMethod.POST && path == RECOVERY_CODE_USE -> Route.UseRecoveryCode
            method == HttpMethod.POST && path == ADMIN_RECOVERY_ISSUE -> Route.IssueAdministrativeRecovery
            method == HttpMethod.POST && path == ADMIN_RECOVERY_REDEEM -> Route.RedeemAdministrativeRecovery
            method == HttpMethod.DELETE && path.startsWith("$ADMIN_RECOVERY_ISSUE/") &&
                path.removePrefix("$ADMIN_RECOVERY_ISSUE/").let { it.isNotEmpty() && '/' !in it } ->
                Route.CancelAdministrativeRecovery(path.removePrefix("$ADMIN_RECOVERY_ISSUE/"))
            method == HttpMethod.GET && path == ORGANIZATIONS -> Route.ListOrganizations
            method == HttpMethod.POST && path == ORGANIZATIONS -> Route.CreateOrganization
            method == HttpMethod.POST && path == INVITATION_ACCEPT -> Route.AcceptInvitation
            method == HttpMethod.POST && path == INVITATION_ENROLL -> Route.EnrollInvitation
            method == HttpMethod.POST && path == DEVICE_VERIFICATION -> Route.InspectDeviceGrant
            method == HttpMethod.POST && path == DEVICE_APPROVE -> Route.ApproveDeviceGrant
            method == HttpMethod.POST && path == DEVICE_DENY -> Route.DenyDeviceGrant
            method == HttpMethod.POST && path == DEVICE_CANCEL -> Route.CancelDeviceGrant
            method == HttpMethod.POST && path == DEVICE_AUTHORIZATION -> Route.DeviceAuthorization
            method == HttpMethod.POST && path == DEVICE_TOKEN -> Route.DeviceToken
            method == HttpMethod.POST && path == DEVICE_TOKEN_REVOKE -> Route.DeviceTokenRevoke
            method == HttpMethod.POST && path == BOOTSTRAP -> Route.Bootstrap
            else -> organizationRoute(method, path)
        }
    }

    private fun organizationRoute(method: HttpMethod, path: String): Route? {
        val segments = path.removePrefix("/").split('/')
        if (segments.size < 4 || segments[0] != "identity" || segments[1] != "v1" ||
            segments[2] != "organizations" || segments.any(String::isEmpty)
        ) return null
        val organizationId = segments[3]
        return when {
            segments.size == 4 && method == HttpMethod.GET -> Route.GetOrganization(organizationId)
            segments.size == 4 && method == HttpMethod.PATCH -> Route.UpdateOrganization(organizationId)
            segments.size == 4 && method == HttpMethod.DELETE -> Route.DeleteOrganization(organizationId)
            segments.size == 5 && segments[4] == "memberships" && method == HttpMethod.GET ->
                Route.ListMemberships(organizationId)
            segments.size == 5 && segments[4] == "audit-events" && method == HttpMethod.GET ->
                Route.ListAuditEvents(organizationId)
            segments.size == 6 && segments[4] == "memberships" && method == HttpMethod.PATCH ->
                Route.UpdateMembership(organizationId, segments[5])
            segments.size == 6 && segments[4] == "memberships" && method == HttpMethod.DELETE ->
                Route.RemoveMembership(organizationId, segments[5])
            segments.size == 5 && segments[4] == "invitations" && method == HttpMethod.GET ->
                Route.ListInvitations(organizationId)
            segments.size == 5 && segments[4] == "invitations" && method == HttpMethod.POST ->
                Route.CreateInvitation(organizationId)
            segments.size == 6 && segments[4] == "invitations" && method == HttpMethod.DELETE ->
                Route.RevokeInvitation(organizationId, segments[5])
            segments.size == 5 && segments[4] == "service-identities" && method == HttpMethod.GET ->
                Route.ListServiceIdentities(organizationId)
            segments.size == 5 && segments[4] == "service-identities" && method == HttpMethod.POST ->
                Route.CreateServiceIdentity(organizationId)
            segments.size == 6 && segments[4] == "service-identities" && method == HttpMethod.DELETE ->
                Route.RevokeServiceIdentity(organizationId, segments[5])
            segments.size == 7 && segments[4] == "service-identities" && segments[6] == "credentials" &&
                method == HttpMethod.GET -> Route.ListServiceCredentials(organizationId, segments[5])
            segments.size == 7 && segments[4] == "service-identities" && segments[6] == "credentials" &&
                method == HttpMethod.POST -> Route.CreateServiceCredential(organizationId, segments[5])
            segments.size == 8 && segments[4] == "service-identities" && segments[6] == "credentials" &&
                method == HttpMethod.DELETE -> Route.RevokeServiceCredential(organizationId, segments[5], segments[7])
            segments.size == 9 && segments[4] == "service-identities" && segments[6] == "credentials" &&
                segments[8] == "rotate" && method == HttpMethod.POST ->
                Route.RotateServiceCredential(organizationId, segments[5], segments[7])
            else -> null
        }
    }

    private suspend fun validateCsrf(exchange: Exchange): Boolean {
        if (exchange.request.method !in PROTECTED_METHODS) return true
        val hasSessionCookie = exchange.request.cookies.contains(identityConfig.cookie.name)
        val session = exchange.identityContext.session
        if (!hasSessionCookie && session == null) return true
        if (!hasSessionCookie || session == null) return false
        val origin = exchange.request.headers.getAll("Origin").singleOrNull()
        val encoded = exchange.request.headers.getAll(httpConfig.csrfHeader).singleOrNull()
        if (origin !in identityConfig.relyingParty.allowedOrigins || encoded == null ||
            session.csrfDigest.algorithm != DigestAlgorithm.HMAC_SHA256
        ) return false
        val provided = runCatching { Base64Url.decode(encoded, maximumBytes = CSRF_TOKEN_BYTES) }.getOrNull()
            ?: return false
        if (provided.size != CSRF_TOKEN_BYTES) {
            provided.fill(0)
            return false
        }
        return try {
            val reference = identityConfig.keys.sessionPepper(session.csrfDigest.keyVersion) ?: return false
            val expected = runCatching {
                Base64Url.decode(session.csrfDigest.encoded, maximumBytes = 32)
            }.getOrNull() ?: return false
            try {
                if (expected.size != 32) return false
                val actual = runtime.crypto.hmacSha256(runtime.secrets.resolve(reference), provided)
                try {
                    actual.size == 32 && runtime.crypto.constantTimeEquals(expected, actual)
                } finally {
                    actual.fill(0)
                }
            } finally {
                expected.fill(0)
            }
        } finally {
            provided.fill(0)
        }
    }

    private fun containsQueryCsrf(exchange: Exchange): Boolean {
        return containsIdentityCsrfQueryToken(exchange.request.query, httpConfig.csrfHeader)
    }

    private fun hasValidRequestAuthority(exchange: Exchange): Boolean {
        if (!enforceExternalAuthority) return true
        val resolved = trustedProxyResolver.resolve(exchange)
        return resolved.scheme?.lowercase() == publicScheme && resolved.host?.lowercase() == publicHost
    }

    private fun ceremonyBinding(exchange: Exchange): WebAuthnCeremonyBinding {
        val encoded = exchange.request.cookies[httpConfig.ceremonyCookieName]?.value
        return encoded?.let { runCatching { WebAuthnCeremonyBinding.fromEncoded(it) }.getOrNull() }
            // A false, fresh binding still lets the service atomically consume a supplied ceremony.
            ?: issueWebAuthnCeremonyBinding(runtime).binding
    }

    private fun registrationSource(context: IdentityContext, now: Instant): IdentityRegistrationSource? {
        val session = context.session ?: return null
        if (!context.isSessionUsableAt(now)) return null
        return session.passkeyRegistrationSource()
    }

    private suspend fun rejectFinishAttempt(
        exchange: Exchange,
        ceremonyId: ChallengeId,
        code: IdentityErrorCode,
        requestId: String
    ) {
        val rejected = webAuthn.rejectFinishAttempt(
            ceremonyId = ceremonyId,
            code = code,
            request = auditMetadata(exchange, requestId)
        )
        val responseCode = when (rejected) {
            is IdentityOperationResult.Failure -> rejected.code
            is IdentityOperationResult.Success -> code
        }
        exchange.respondIdentityError(responseCode, requestId)
    }

    private fun ceremonyCookie(value: String): Cookie = Cookie(
        name = httpConfig.ceremonyCookieName,
        value = value,
        path = "/",
        secure = httpConfig.ceremonyCookieName.startsWith("__Host-") || identityConfig.cookie.secure,
        httpOnly = true,
        sameSite = Cookie.SameSite.STRICT,
        maxAge = identityConfig.lifetimes.challenge.seconds
    )

    private fun clearCeremonyCookie(): Cookie = ceremonyCookie("").copy(maxAge = 0)

    private fun sessionCookie(value: String): Cookie = Cookie(
        name = identityConfig.cookie.name,
        value = value,
        path = identityConfig.cookie.path,
        domain = identityConfig.cookie.domain,
        secure = identityConfig.cookie.secure,
        httpOnly = identityConfig.cookie.httpOnly,
        sameSite = identityConfig.cookie.sameSite.toCoreSameSite()
    )

    private fun clearSessionCookie(): Cookie = sessionCookie("").copy(maxAge = 0)

    private suspend fun auditMetadata(exchange: Exchange, requestId: String): AuditRequestMetadata {
        val resolved = trustedProxyResolver.resolve(exchange)
        return AuditRequestMetadata(
            requestId = requestId,
            method = exchange.request.method.name,
            path = exchange.request.path.take(4_096),
            userAgent = auditRedactor.userAgent(exchange.request.headers["User-Agent"]),
            clientIpDigest = auditRedactor.clientAddress(resolved.clientAddress),
            trustedProxy = resolved.usedForwardedHeaders
        )
    }

    private suspend fun recoveryAttempt(exchange: Exchange): IdentityRecoveryAttempt {
        val clientAddress = trustedProxyResolver.resolve(exchange).clientAddress ?: "unknown-peer"
        val material = "aether-recovery-attempt-v1\u0000$clientAddress".encodeToByteArray()
        return try {
            val digest = runtime.crypto.hmacSha256(
                runtime.secrets.resolve(identityConfig.keys.auditPseudonymizationKey),
                material
            )
            try {
                require(digest.size == 32) { "Invalid recovery-attempt pseudonym digest" }
                IdentityRecoveryAttempt(
                    key = IdentityRecoveryAttemptKey(Base64Url.encode(digest)),
                    attemptedAt = runtime.clock.now()
                )
            } finally {
                digest.fill(0)
            }
        } finally {
            material.fill(0)
        }
    }

    private suspend inline fun <reified T> Exchange.decodeJson(maximumBytes: Int): T? {
        if (!request.hasSingleContentType("application/json") || request.declaredBodyTooLarge(maximumBytes)) {
            return null
        }
        val bytes = request.bodyBytes()
        if (bytes.isEmpty() || bytes.size > maximumBytes) {
            bytes.fill(0)
            return null
        }
        return try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            json.decodeFromString<T>(text)
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Reads a finish body once, first recovering only its opaque ceremony ID with unknown fields
     * skipped. A syntactically valid envelope therefore remains consumable even when the nested
     * browser credential cannot be deserialized.
     */
    private suspend inline fun <reified T> Exchange.decodeWebAuthnFinish(
        maximumBytes: Int
    ): WebAuthnFinishDecode<T> {
        if (!request.hasSingleContentType("application/json") || request.declaredBodyTooLarge(maximumBytes)) {
            return WebAuthnFinishDecode.Malformed(null)
        }
        val bytes = request.bodyBytes()
        if (bytes.isEmpty() || bytes.size > maximumBytes) {
            bytes.fill(0)
            return WebAuthnFinishDecode.Malformed(null)
        }
        return try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            val envelope = try {
                WEB_AUTHN_FINISH_ENVELOPE_JSON.decodeFromString<WebAuthnFinishEnvelope>(text)
            } catch (_: IllegalArgumentException) {
                return WebAuthnFinishDecode.Malformed(null)
            }
            val decoded = try {
                json.decodeFromString<T>(text)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (decoded == null) {
                WebAuthnFinishDecode.Malformed(envelope.ceremonyId)
            } else {
                WebAuthnFinishDecode.Decoded(decoded)
            }
        } catch (_: IllegalArgumentException) {
            WebAuthnFinishDecode.Malformed(null)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun Exchange.decodeForm(maximumBytes: Int): Map<String, List<String>>? {
        if (!request.hasSingleContentType("application/x-www-form-urlencoded") ||
            request.declaredBodyTooLarge(maximumBytes)
        ) return null
        val bytes = request.bodyBytes()
        if (bytes.isEmpty() || bytes.size > maximumBytes) {
            bytes.fill(0)
            return null
        }
        return try {
            val encoded = bytes.decodeToString(throwOnInvalidSequence = true)
            val result = linkedMapOf<String, MutableList<String>>()
            val fields = encoded.split('&')
            if (fields.size > MAX_FORM_FIELDS) return null
            for (field in fields) {
                val separator = field.indexOf('=')
                if (separator <= 0) return null
                val name = decodeFormComponent(field.substring(0, separator)) ?: return null
                val value = decodeFormComponent(field.substring(separator + 1)) ?: return null
                if (name.length !in 1..100 || value.length > MAX_FORM_VALUE_LENGTH) return null
                result.getOrPut(name) { mutableListOf() }.add(value)
            }
            result
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun parseCapabilities(value: String?): Set<Capability>? {
        if (value == null || value.length > 4_096) return null
        val values = value.split(' ').filter(String::isNotBlank)
        if (values.isEmpty() || values.size > MAX_SCOPES || values.toSet().size != values.size) return null
        return runCatching { values.mapTo(linkedSetOf(), ::Capability) }.getOrNull()
    }

    private sealed interface Route {
        data object Me : Route
        data object RegistrationStart : Route
        data object RegistrationFinish : Route
        data object AuthenticationStart : Route
        data object AuthenticationFinish : Route
        data object StepUpStart : Route
        data object StepUpFinish : Route
        data object ListSessions : Route
        data object Logout : Route
        data class RevokeSession(val encodedId: String) : Route
        data object RevokeOtherSessions : Route
        data object RevokeAllSessions : Route
        data object ListPasskeys : Route
        data class RenamePasskey(val encodedId: String) : Route
        data class RevokePasskey(val encodedId: String) : Route
        data object ReplaceRecoveryCodes : Route
        data object UseRecoveryCode : Route
        data object IssueAdministrativeRecovery : Route
        data class CancelAdministrativeRecovery(val encodedId: String) : Route
        data object RedeemAdministrativeRecovery : Route
        data object ListOrganizations : Route
        data object CreateOrganization : Route
        data class GetOrganization(val organizationId: String) : Route
        data class UpdateOrganization(val organizationId: String) : Route
        data class DeleteOrganization(val organizationId: String) : Route
        data class ListMemberships(val organizationId: String) : Route
        data class ListAuditEvents(val organizationId: String) : Route
        data class UpdateMembership(val organizationId: String, val membershipId: String) : Route
        data class RemoveMembership(val organizationId: String, val membershipId: String) : Route
        data class ListInvitations(val organizationId: String) : Route
        data class CreateInvitation(val organizationId: String) : Route
        data class RevokeInvitation(val organizationId: String, val invitationId: String) : Route
        data object AcceptInvitation : Route
        data object EnrollInvitation : Route
        data object InspectDeviceGrant : Route
        data object ApproveDeviceGrant : Route
        data object DenyDeviceGrant : Route
        data object CancelDeviceGrant : Route
        data object DeviceAuthorization : Route
        data object DeviceToken : Route
        data object DeviceTokenRevoke : Route
        data class ListServiceIdentities(val organizationId: String) : Route
        data class CreateServiceIdentity(val organizationId: String) : Route
        data class RevokeServiceIdentity(val organizationId: String, val serviceIdentityId: String) : Route
        data class ListServiceCredentials(val organizationId: String, val serviceIdentityId: String) : Route
        data class CreateServiceCredential(val organizationId: String, val serviceIdentityId: String) : Route
        data class RotateServiceCredential(
            val organizationId: String,
            val serviceIdentityId: String,
            val credentialId: String
        ) : Route
        data class RevokeServiceCredential(
            val organizationId: String,
            val serviceIdentityId: String,
            val credentialId: String
        ) : Route
        data object Bootstrap : Route
    }

    companion object {
        const val ME = "/identity/v1/me"
        const val REGISTRATION_START = "/identity/v1/passkeys/registration/start"
        const val REGISTRATION_FINISH = "/identity/v1/passkeys/registration/finish"
        const val AUTHENTICATION_START = "/identity/v1/passkeys/authentication/start"
        const val AUTHENTICATION_FINISH = "/identity/v1/passkeys/authentication/finish"
        const val STEP_UP_START = "/identity/v1/passkeys/step-up/start"
        const val STEP_UP_FINISH = "/identity/v1/passkeys/step-up/finish"
        const val SESSIONS = "/identity/v1/sessions"
        const val LOGOUT = "/identity/v1/logout"
        const val REVOKE_OTHERS = "/identity/v1/sessions/revoke-others"
        const val REVOKE_ALL = "/identity/v1/sessions/revoke-all"
        const val PASSKEYS = "/identity/v1/passkeys"
        const val RECOVERY_CODES_REPLACE = "/identity/v1/recovery/codes/replace"
        const val RECOVERY_CODE_USE = "/identity/v1/recovery/codes/use"
        const val ADMIN_RECOVERY_ISSUE = "/identity/v1/recovery/admin/tickets"
        const val ADMIN_RECOVERY_REDEEM = "/identity/v1/recovery/admin/tickets/redeem"
        const val ORGANIZATIONS = "/identity/v1/organizations"
        const val INVITATION_ACCEPT = "/identity/v1/invitations/accept"
        const val INVITATION_ENROLL = "/identity/v1/invitations/enroll"
        const val DEVICE_VERIFICATION = "/identity/v1/device"
        const val DEVICE_APPROVE = "/identity/v1/device/approve"
        const val DEVICE_DENY = "/identity/v1/device/deny"
        const val DEVICE_CANCEL = "/identity/v1/device/cancel"
        const val DEVICE_AUTHORIZATION = "/oauth/device_authorization"
        const val DEVICE_TOKEN = "/oauth/token"
        const val DEVICE_TOKEN_REVOKE = "/oauth/revoke"
        const val BOOTSTRAP = "/identity/v1/bootstrap"
    }
}

private val DEFAULT_IDENTITY_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowStructuredMapKeys = false
}

private val WEB_AUTHN_FINISH_ENVELOPE_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = false
    coerceInputValues = false
    allowStructuredMapKeys = false
}

private val HTTP_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
private const val CSRF_TOKEN_BYTES = 32
private const val MAX_QUERY_LENGTH = 8_192
private const val MAX_FORM_FIELDS = 32
private const val MAX_FORM_VALUE_LENGTH = 8_192
private const val MAX_SCOPES = 64
private const val MAX_DEVICE_INSPECTION_BODY_BYTES = 256
private const val MAX_USER_AGENT_LENGTH = 2_048
private val ORGANIZATION_AUDIT_QUERY_FIELDS = setOf("cursor", "limit")
private val ORGANIZATION_AUDIT_LIMIT = Regex("[1-9][0-9]{0,2}")
private const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
private const val REFRESH_TOKEN_GRANT_TYPE = "refresh_token"
private val PROTECTED_METHODS = setOf(
    HttpMethod.POST,
    HttpMethod.PUT,
    HttpMethod.PATCH,
    HttpMethod.DELETE,
    HttpMethod.CONNECT
)

private fun IdentityContext.authenticatedUserId(): UserId? =
    principal?.takeIf { it.kind == IdentityPrincipalKind.USER }?.userId

private fun parseOrganizationId(encoded: String): OrganizationId? =
    OrganizationId.parseOrNull(encoded)

private data class OrganizationAuditPageQuery(
    val cursor: OrganizationAuditEventCursor?,
    val limit: Int
)

/** Strictly accepts at most one `cursor` and one canonical positive `limit`; unknown fields fail. */
private fun Exchange.organizationAuditPageQuery(): OrganizationAuditPageQuery? {
    val query = request.query
        ?: return OrganizationAuditPageQuery(null, OrganizationAuditEventPageRequest.DEFAULT_LIMIT)
    if (query.isEmpty()) return OrganizationAuditPageQuery(null, OrganizationAuditEventPageRequest.DEFAULT_LIMIT)
    if (query.length > MAX_QUERY_LENGTH) return null
    val fields = linkedMapOf<String, String>()
    for (encodedField in query.split('&')) {
        val separator = encodedField.indexOf('=')
        if (separator <= 0 || separator != encodedField.lastIndexOf('=')) return null
        val name = decodeFormComponent(encodedField.substring(0, separator)) ?: return null
        val value = decodeFormComponent(encodedField.substring(separator + 1)) ?: return null
        if (name !in ORGANIZATION_AUDIT_QUERY_FIELDS || value.isEmpty() || fields.put(name, value) != null) {
            return null
        }
    }
    val cursor = fields["cursor"]?.let(OrganizationAuditEventCursor::fromOpaqueToken)
    if ("cursor" in fields && cursor == null) return null
    val limit = fields["limit"]?.let { encoded ->
        if (!ORGANIZATION_AUDIT_LIMIT.matches(encoded)) return null
        encoded.toIntOrNull()?.takeIf { it in 1..OrganizationAuditEventPageRequest.MAXIMUM_LIMIT }
            ?: return null
    } ?: OrganizationAuditEventPageRequest.DEFAULT_LIMIT
    return OrganizationAuditPageQuery(cursor, limit)
}

private fun Credential.toPasskeyView(): PasskeyView = PasskeyView(
    id = id,
    name = name,
    transports = transports,
    backupEligible = backupEligible,
    backedUp = backedUp,
    state = state,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt
)

private fun SameSitePolicy.toCoreSameSite(): Cookie.SameSite = when (this) {
    SameSitePolicy.STRICT -> Cookie.SameSite.STRICT
    SameSitePolicy.LAX -> Cookie.SameSite.LAX
    SameSitePolicy.NONE -> Cookie.SameSite.NONE
}

private fun codes.yousef.aether.core.Request.hasSingleContentType(expected: String): Boolean {
    val values = headers.getAll("Content-Type")
    if (values.size != 1) return false
    return values.single().substringBefore(';').trim().lowercase() == expected
}

private fun codes.yousef.aether.core.Request.declaredBodyTooLarge(maximumBytes: Int): Boolean {
    val values = headers.getAll("Content-Length")
    if (values.isEmpty()) return false
    val declared = values.singleOrNull()?.toLongOrNull() ?: return true
    return declared < 0 || declared > maximumBytes
}

private fun Map<String, List<String>>.single(name: String): String? = get(name)?.singleOrNull()

private fun decodeFormComponent(encoded: String): String? {
    if (encoded.length > MAX_FORM_VALUE_LENGTH * 3) return null
    val bytes = ByteArray(encoded.length)
    var read = 0
    var written = 0
    while (read < encoded.length) {
        val character = encoded[read]
        when {
            character == '+' -> {
                bytes[written++] = ' '.code.toByte()
                read++
            }
            character == '%' -> {
                if (read + 2 >= encoded.length) return null
                val high = encoded[read + 1].digitToIntOrNull(16) ?: return null
                val low = encoded[read + 2].digitToIntOrNull(16) ?: return null
                bytes[written++] = ((high shl 4) or low).toByte()
                read += 3
            }
            character.code in 0x21..0x7e -> {
                bytes[written++] = character.code.toByte()
                read++
            }
            else -> return null
        }
    }
    return try {
        bytes.decodeToString(0, written, throwOnInvalidSequence = true)
    } catch (_: IllegalArgumentException) {
        null
    } finally {
        bytes.fill(0)
    }
}
