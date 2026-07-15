package codes.yousef.aether.auth.oidc

import codes.yousef.aether.auth.AuditRequestMetadata
import codes.yousef.aether.auth.IdentityAuditRedactor
import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.DeviceMetadata
import codes.yousef.aether.auth.FederationCallbackStateConsumeResult
import codes.yousef.aether.auth.FederationCallbackStateStore
import codes.yousef.aether.auth.FederationCallbackStateWriteResult
import codes.yousef.aether.auth.FederatedIdentitySessionCreator
import codes.yousef.aether.auth.FederatedIdentitySessionRequest
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.IdentityConfig
import codes.yousef.aether.auth.IdentityErrorCode
import codes.yousef.aether.auth.IdentityHttpMethod
import codes.yousef.aether.auth.IdentityHttpRequest
import codes.yousef.aether.auth.IdentityOperationResult
import codes.yousef.aether.auth.IdentityRuntime
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.SameSitePolicy
import codes.yousef.aether.auth.SessionId
import codes.yousef.aether.auth.identityContext
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.pipeline.Middleware
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val OIDC_COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
private val OIDC_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")

/** One tenant/provider entry resolved from application-owned configuration. */
class OidcFederationProviderRegistration(
    val provider: OidcFederationProvider,
    allowedAuthorizationEndpoints: Set<String>,
    val successRedirectUrl: String
) {
    val allowedAuthorizationEndpoints: Set<String> = allowedAuthorizationEndpoints.toSet()

    init {
        require(this.allowedAuthorizationEndpoints.isNotEmpty() &&
            this.allowedAuthorizationEndpoints.size <= 8
        ) { "At least one bounded OIDC authorization endpoint is required" }
        this.allowedAuthorizationEndpoints.forEach(::requireRedirectEndpoint)
        requireSafeRedirect(successRedirectUrl)
    }

    override fun toString(): String =
        "OidcFederationProviderRegistration(tenant=${provider.configuredTenantId}, " +
            "provider=${provider.configuredProviderId}, authorizationEndpoints=<redacted>, " +
            "successRedirect=<redacted>)"
}

sealed interface OidcFederationProviderResolution {
    data class Found(val registration: OidcFederationProviderRegistration) : OidcFederationProviderResolution
    /** The exact route belongs to another installed federation adapter (for example SAML). */
    data object NotOwned : OidcFederationProviderResolution
    data object Missing : OidcFederationProviderResolution
    data object Unavailable : OidcFederationProviderResolution
}

/** Registry lookup is deliberately exact and tenant scoped. */
fun interface OidcFederationProviderRegistry {
    suspend fun resolve(tenantId: OrganizationId, providerId: String): OidcFederationProviderResolution
}

/**
 * Secret-bearing callback correlation retained only by an injected server-side store. It is not
 * serializable, and its diagnostic representation never includes the PKCE verifier or binding.
 */
class OidcServerCallbackState internal constructor(
    val providerLease: FederationProviderLease,
    internal val callbackSecret: OidcCallbackSecret,
    callbackBinding: ByteArray,
    val predecessorSessionId: SessionId?,
    val expectedPredecessorVersion: Long?,
    val expiresAt: Instant
) {
    private val callbackBindingValue = callbackBinding.copyOf()
    val tenantId: OrganizationId get() = providerLease.organizationId
    val providerId: String get() = providerLease.providerId

    init {
        require(callbackBindingValue.size == CALLBACK_BINDING_BYTES)
        require(providerLease.kind == FederationProviderKind.OIDC) {
            "OIDC callback state requires an OIDC provider lease"
        }
        require((predecessorSessionId == null) == (expectedPredecessorVersion == null)) {
            "OIDC predecessor selector and version must either both be present or both be absent"
        }
        require(expectedPredecessorVersion == null || expectedPredecessorVersion >= 0) {
            "OIDC predecessor version must not be negative"
        }
    }

    internal fun callbackBinding(): ByteArray = callbackBindingValue.copyOf()

    /**
     * Supplies defensive copies to an application-owned authenticated-encryption boundary for a
     * distributed server-side state store. The block must not return or log either byte array.
     */
    suspend fun <T> useForProtection(
        block: suspend (
            providerLease: FederationProviderLease,
            challengeId: codes.yousef.aether.auth.ChallengeId,
            verifierSeed: ByteArray,
            callbackBinding: ByteArray,
            predecessorSessionId: SessionId?,
            expectedPredecessorVersion: Long?,
            expiresAt: Instant
        ) -> T
    ): T {
        val binding = callbackBindingValue.copyOf()
        return try {
            callbackSecret.useSeedForProtection { verifier ->
                block(
                    providerLease,
                    callbackSecret.challengeId,
                    verifier,
                    binding,
                    predecessorSessionId,
                    expectedPredecessorVersion,
                    expiresAt
                )
            }
        } finally {
            binding.fill(0)
        }
    }

    /** Zero callback material when a store evicts or expires this record without consuming it. */
    fun destroy() {
        callbackBindingValue.fill(0)
        callbackSecret.destroy()
    }

    override fun toString(): String =
        "OidcServerCallbackState(tenantId=$tenantId, providerId=$providerId, " +
            "callbackSecret=<redacted>, callbackBinding=<redacted>, " +
            "predecessor=${if (predecessorSessionId == null) "none" else "present"}, expiresAt=$expiresAt)"

    companion object {
        /** Restore only after application-owned authenticated decryption of server-side state. */
        fun restore(
            providerLease: FederationProviderLease,
            challengeId: codes.yousef.aether.auth.ChallengeId,
            verifierSeed: ByteArray,
            callbackBinding: ByteArray,
            expiresAt: Instant,
            predecessorSessionId: SessionId? = null,
            expectedPredecessorVersion: Long? = null
        ): OidcServerCallbackState = OidcServerCallbackState(
            providerLease,
            OidcCallbackSecret.restore(challengeId, verifierSeed),
            callbackBinding,
            predecessorSessionId,
            expectedPredecessorVersion,
            expiresAt
        )
    }
}

data class OidcFederationHttpConfig(
    val stateCookieName: String = "__Host-aether_oidc_state",
    val csrfCookieName: String = "__Host-aether_csrf",
    val requestIdHeader: String = "X-Request-ID",
    val maximumQueryBytes: Int = 12_288,
    val csrfCookieLifetimeSeconds: Long = 300
) {
    init {
        require(OIDC_COOKIE_NAME.matches(stateCookieName) && stateCookieName.startsWith("__Host-")) {
            "OIDC state cookie must be a valid __Host- cookie"
        }
        require(OIDC_COOKIE_NAME.matches(csrfCookieName) && csrfCookieName.startsWith("__Host-")) {
            "OIDC CSRF handoff cookie must be a valid __Host- cookie"
        }
        require(OIDC_HEADER_NAME.matches(requestIdHeader)) { "Invalid request-ID header" }
        require(maximumQueryBytes in 1_024..65_536) { "OIDC query limit must be 1 KiB..64 KiB" }
        require(csrfCookieLifetimeSeconds in 30..600) { "OIDC CSRF handoff lifetime must be 30..600 seconds" }
    }
}

@Serializable
enum class OidcFederationHttpErrorCode {
    @SerialName("request_invalid") REQUEST_INVALID,
    @SerialName("provider_not_found") PROVIDER_NOT_FOUND,
    @SerialName("identity_response_invalid") IDENTITY_RESPONSE_INVALID,
    @SerialName("service_unavailable") SERVICE_UNAVAILABLE
}

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class OidcFederationHttpError(
    val code: OidcFederationHttpErrorCode,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val message: String = code.genericMessage,
    val requestId: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val retryable: Boolean = code.defaultRetryable
) {
    init {
        require(message == code.genericMessage) { "OIDC federation errors must use the stable generic message" }
        require(retryable == code.defaultRetryable) { "OIDC federation retryability is fixed by error code" }
    }
}

internal val OidcFederationHttpErrorCode.genericMessage: String
    get() = when (this) {
        OidcFederationHttpErrorCode.REQUEST_INVALID -> "The federation request is invalid."
        OidcFederationHttpErrorCode.PROVIDER_NOT_FOUND -> "The identity provider was not found."
        OidcFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID -> "The identity response could not be accepted."
        OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE -> "The identity service is temporarily unavailable."
    }

internal val OidcFederationHttpErrorCode.defaultRetryable: Boolean
    get() = this == OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE

/**
 * Common-code OIDC transport for the two fixed federation endpoints:
 * `/identity/v1/federation/{tenantId}/{providerId}/start` and `/callback`.
 *
 * Provider configuration, callback-state persistence, and session persistence are mandatory
 * dependencies. No PKCE verifier, assertion, session secret, or provider exception is written to
 * JSON or a redirect location.
 */
class OidcFederationHttpMiddleware(
    private val runtime: IdentityRuntime,
    private val identityConfig: IdentityConfig,
    private val providers: OidcFederationProviderRegistry,
    private val callbackStates: FederationCallbackStateStore<OidcServerCallbackState>,
    private val sessions: FederatedIdentitySessionCreator,
    private val config: OidcFederationHttpConfig = OidcFederationHttpConfig()
) {
    private val auditRedactor = IdentityAuditRedactor(runtime, identityConfig)
    fun asMiddleware(): Middleware = middleware@{ exchange, next ->
        if (!isFederationPath(exchange.request.path)) {
            next()
            return@middleware
        }

        val requestId = requestId(exchange)
        secureResponse(exchange)
        if (hasInvalidRequestId(exchange)) {
            fail(exchange, 400, OidcFederationHttpErrorCode.REQUEST_INVALID, requestId)
            return@middleware
        }
        try {
            val route = parseRoute(exchange.request.path)
            if (route == null) {
                fail(exchange, 404, OidcFederationHttpErrorCode.PROVIDER_NOT_FOUND, requestId)
                return@middleware
            }
            val resolution = try {
                providers.resolve(route.tenantId, route.providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                OidcFederationProviderResolution.Unavailable
            }
            val registration = when (resolution) {
                is OidcFederationProviderResolution.Found -> resolution.registration
                OidcFederationProviderResolution.NotOwned -> {
                    next()
                    return@middleware
                }
                OidcFederationProviderResolution.Missing -> {
                    fail(exchange, 404, OidcFederationHttpErrorCode.PROVIDER_NOT_FOUND, requestId)
                    return@middleware
                }
                OidcFederationProviderResolution.Unavailable -> {
                    fail(exchange, 503, OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
                    return@middleware
                }
            }
            if (
                registration.provider.configuredTenantId != route.tenantId ||
                registration.provider.configuredProviderId != route.providerId
            ) {
                fail(exchange, 404, OidcFederationHttpErrorCode.PROVIDER_NOT_FOUND, requestId)
                return@middleware
            }

            when (route.action) {
                FederationAction.START -> handleStart(exchange, registration, route, requestId)
                FederationAction.CALLBACK -> handleCallback(exchange, registration, route, requestId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail(exchange, 503, OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
        }
    }

    private suspend fun handleStart(
        exchange: Exchange,
        registration: OidcFederationProviderRegistration,
        route: FederationRoute,
        requestId: String
    ) {
        if (exchange.request.method != HttpMethod.GET) {
            methodNotAllowed(exchange, requestId, "GET")
            return
        }
        if (!exchange.request.query.isNullOrEmpty() || !hasNoBody(exchange)) {
            fail(exchange, 400, OidcFederationHttpErrorCode.REQUEST_INVALID, requestId)
            return
        }

        previousStateSelector(exchange)?.let { oldSelector ->
            try {
                val stale = callbackStates.consume(oldSelector)
                if (stale is FederationCallbackStateConsumeResult.Consumed) stale.state.destroy()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A stale browser cookie must not make a fresh start depend on best-effort cleanup.
            }
        }

        val binding = runtime.secureRandom.nextBytes(CALLBACK_BINDING_BYTES)
        if (binding.size != CALLBACK_BINDING_BYTES) {
            binding.fill(0)
            fail(exchange, 503, OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
            return
        }
        val started = try {
            registration.provider.beginAuthorization(OidcAuthorizationRequest(binding))
        } catch (cancelled: CancellationException) {
            binding.fill(0)
            throw cancelled
        } catch (_: Throwable) {
            binding.fill(0)
            fail(exchange, 503, OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
            return
        }
        val value = when (started) {
            is OidcResult.Success -> started.value
            is OidcResult.Failure -> {
                binding.fill(0)
                val disabled = started.error.code == OidcErrorCode.PROVIDER_DISABLED
                fail(
                    exchange,
                    if (disabled) 404 else if (started.error.retryable) 503 else 400,
                    if (disabled) OidcFederationHttpErrorCode.PROVIDER_NOT_FOUND
                    else if (started.error.retryable) OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE
                    else OidcFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID,
                    requestId
                )
                return
            }
        }
        if (!registration.allowsAuthorizationRedirect(value.authorizationUrl)) {
            binding.fill(0)
            value.callbackSecret.destroy()
            fail(exchange, 503, OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
            return
        }
        if (value.providerLease.organizationId != route.tenantId ||
            value.providerLease.providerId != route.providerId ||
            value.providerLease.kind != FederationProviderKind.OIDC
        ) {
            binding.fill(0)
            value.callbackSecret.destroy()
            fail(exchange, 404, OidcFederationHttpErrorCode.PROVIDER_NOT_FOUND, requestId)
            return
        }

        val predecessor = exchange.identityContext.session
        val state = OidcServerCallbackState(
            providerLease = value.providerLease,
            callbackSecret = value.callbackSecret,
            callbackBinding = binding,
            predecessorSessionId = predecessor?.id,
            expectedPredecessorVersion = predecessor?.version,
            expiresAt = value.expiresAt
        )
        binding.fill(0)
        val selector = storeState(state)
        if (selector == null) {
            state.destroy()
            fail(exchange, 503, OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
            return
        }
        val lifetime = (value.expiresAt.toEpochMilliseconds() - runtime.clock.now().toEpochMilliseconds()) / 1_000
        if (lifetime <= 0) {
            callbackStates.consume(selector)
            state.destroy()
            fail(exchange, 400, OidcFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
            return
        }
        exchange.response.setCookie(stateCookie(selector, lifetime))
        redirect(exchange, value.authorizationUrl, 302)
    }

    private suspend fun handleCallback(
        exchange: Exchange,
        registration: OidcFederationProviderRegistration,
        route: FederationRoute,
        requestId: String
    ) {
        exchange.response.setCookie(clearStateCookie())
        if (exchange.request.method != HttpMethod.GET) {
            methodNotAllowed(exchange, requestId, "GET")
            return
        }
        if (!hasNoBody(exchange)) {
            fail(exchange, 400, OidcFederationHttpErrorCode.REQUEST_INVALID, requestId)
            return
        }
        val query = parseCallbackQuery(exchange.request.query)
        val selector = stateSelector(exchange)
        if (query == null || selector == null) {
            fail(exchange, 400, OidcFederationHttpErrorCode.REQUEST_INVALID, requestId)
            return
        }
        val stored = try {
            callbackStates.consume(selector)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            FederationCallbackStateConsumeResult.Unavailable
        }
        val state = when (stored) {
            is FederationCallbackStateConsumeResult.Consumed -> stored.state
            FederationCallbackStateConsumeResult.Missing -> {
                fail(exchange, 400, OidcFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
                return
            }
            FederationCallbackStateConsumeResult.Unavailable -> {
                fail(exchange, 503, OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
                return
            }
        }
        try {
            if (state.tenantId != route.tenantId || state.providerId != route.providerId ||
                state.expiresAt <= runtime.clock.now()
            ) {
                fail(exchange, 400, OidcFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
                return
            }

            val binding = state.callbackBinding()
            val audit = auditRequest(exchange, requestId)
            val completed = try {
                registration.provider.completeAuthorization(
                    OidcCallbackRequest(
                        state = query.getValue("state"),
                        authorizationCode = query.getValue("code"),
                        callbackBinding = binding,
                        callbackSecret = state.callbackSecret,
                        providerLease = state.providerLease,
                        auditRequest = audit
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                OidcResult.Failure(OidcError(OidcErrorCode.STORE_UNAVAILABLE))
            } finally {
                binding.fill(0)
            }
            val authenticated = when (completed) {
                is OidcResult.Success -> completed.value
                is OidcResult.Failure -> {
                    val disabled = completed.error.code == OidcErrorCode.PROVIDER_DISABLED
                    fail(
                        exchange,
                        if (disabled) 404 else if (completed.error.retryable) 503 else 400,
                        if (disabled) OidcFederationHttpErrorCode.PROVIDER_NOT_FOUND
                        else if (completed.error.retryable) OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE
                        else OidcFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID,
                        requestId
                    )
                    return
                }
            }
            if (authenticated.providerLease != state.providerLease ||
                authenticated.providerLease.organizationId != route.tenantId ||
                authenticated.providerLease.providerId != route.providerId ||
                authenticated.providerLease.kind != FederationProviderKind.OIDC ||
                authenticated.authenticationMethod != codes.yousef.aether.auth.SessionAuthenticationMethod.OIDC ||
                authenticated.assurance != codes.yousef.aether.auth.AuthenticationAssurance.SESSION ||
                !authenticated.passkeyStepUpRequiredForSensitiveActions
            ) {
                fail(exchange, 400, OidcFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
                return
            }
            val currentSession = exchange.identityContext.session
            if (state.predecessorSessionId != null && currentSession != null &&
                state.predecessorSessionId != currentSession.id
            ) {
                fail(exchange, 400, OidcFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
                return
            }
            val predecessorSessionId = state.predecessorSessionId ?: currentSession?.id
            val expectedPredecessorVersion = if (currentSession != null && currentSession.id == predecessorSessionId) {
                currentSession.version
            } else {
                state.expectedPredecessorVersion
            }
            val session = sessions.create(
                FederatedIdentitySessionRequest(
                    userId = authenticated.userId,
                    providerLease = authenticated.providerLease,
                    externalIdentityId = authenticated.externalIdentityId,
                    authenticationMethod = authenticated.authenticationMethod,
                    authenticatedAt = runtime.clock.now(),
                    device = DeviceMetadata(userAgent = singleSafeHeader(exchange, "User-Agent")?.take(2_048)),
                    predecessorSessionId = predecessorSessionId,
                    expectedPredecessorVersion = expectedPredecessorVersion,
                    auditRequest = audit
                )
            )
            val issued = when (session) {
                is IdentityOperationResult.Success -> session.value
                is IdentityOperationResult.Failure -> {
                    val unavailable = session.code == IdentityErrorCode.SERVICE_UNAVAILABLE || session.code.retryable
                    fail(
                        exchange,
                        if (unavailable) 503 else 400,
                        if (unavailable) OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE
                        else OidcFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID,
                        requestId
                    )
                    return
                }
            }
            exchange.response.setCookie(identitySessionCookie(issued.cookieValue()))
            // This short-lived, script-readable value is session-bound and never authenticates a
            // request by itself. Keeping it out of Location avoids history and referrer disclosure.
            exchange.response.setCookie(csrfHandoffCookie(issued.csrfToken()))
            redirect(exchange, registration.successRedirectUrl, 303)
        } finally {
            state.destroy()
        }
    }

    private suspend fun storeState(state: OidcServerCallbackState): String? {
        repeat(MAX_SELECTOR_ATTEMPTS) {
            val entropy = runtime.secureRandom.nextBytes(STATE_SELECTOR_BYTES)
            if (entropy.size != STATE_SELECTOR_BYTES) {
                entropy.fill(0)
                return null
            }
            val selector = try {
                Base64Url.encode(entropy)
            } finally {
                entropy.fill(0)
            }
            when (try {
                callbackStates.store(selector, state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                FederationCallbackStateWriteResult.Unavailable
            }) {
                FederationCallbackStateWriteResult.Stored -> return selector
                FederationCallbackStateWriteResult.Conflict -> Unit
                FederationCallbackStateWriteResult.Unavailable -> return null
            }
        }
        return null
    }

    private suspend fun hasNoBody(exchange: Exchange): Boolean {
        if (exchange.request.headers.getAll("Transfer-Encoding").isNotEmpty()) return false
        val lengths = exchange.request.headers.getAll("Content-Length")
        if (lengths.size > 1 || lengths.singleOrNull()?.let { it != "0" } == true) return false
        if (exchange.request.headers.getAll("Content-Type").isNotEmpty()) return false
        val bytes = exchange.request.bodyBytes()
        return try {
            bytes.isEmpty()
        } finally {
            bytes.fill(0)
        }
    }

    private fun parseCallbackQuery(raw: String?): Map<String, String>? {
        if (raw.isNullOrEmpty() || raw.length > config.maximumQueryBytes) return null
        return try {
            val result = linkedMapOf<String, String>()
            val fields = raw.split('&')
            if (fields.size != 2) return null
            fields.forEach { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) return null
                val name = decodeFormComponent(field.substring(0, separator))
                val value = decodeFormComponent(field.substring(separator + 1))
                if (name !in CALLBACK_QUERY_FIELDS || value.any(::isProtocolControl) ||
                    result.put(name, value) != null
                ) return null
            }
            val state = result["state"] ?: return null
            val code = result["code"] ?: return null
            if (state.length !in 16..512 || state.any(Char::isWhitespace) ||
                code.length !in 1..8_192 || code.any(Char::isWhitespace)
            ) null else result
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun stateSelector(exchange: Exchange): String? {
        if (exchange.request.headers.getAll("Cookie").size > 1) return null
        val fromParsed = exchange.request.cookies[config.stateCookieName]?.value
        val raw = exchange.request.headers["Cookie"] ?: return fromParsed?.takeIf(STATE_SELECTOR::matches)
        if (raw.length > MAXIMUM_COOKIE_HEADER || raw.any(::isProtocolControl)) return null
        val parts = raw.split(';')
        if (parts.size > MAXIMUM_COOKIE_FIELDS) return null
        val matches = parts.map(String::trim).mapNotNull { item ->
            val separator = item.indexOf('=')
            if (separator <= 0 || item.substring(0, separator) != config.stateCookieName) null
            else item.substring(separator + 1)
        }
        if (matches.size != 1 || (fromParsed != null && fromParsed != matches.single())) return null
        return matches.single().takeIf(STATE_SELECTOR::matches)
    }

    private fun previousStateSelector(exchange: Exchange): String? = stateSelector(exchange)

    private fun requestId(exchange: Exchange): String {
        val supplied = exchange.request.headers.getAll(config.requestIdHeader)
        supplied.singleOrNull()?.takeIf(REQUEST_ID::matches)?.let { return it }
        val bytes = runtime.secureRandom.nextBytes(REQUEST_ID_BYTES)
        return try {
            if (bytes.size == REQUEST_ID_BYTES) "req_${Base64Url.encode(bytes)}" else "req_unavailable"
        } finally {
            bytes.fill(0)
        }
    }

    private fun hasInvalidRequestId(exchange: Exchange): Boolean {
        val values = exchange.request.headers.getAll(config.requestIdHeader)
        return values.size > 1 || values.singleOrNull()?.let { !REQUEST_ID.matches(it) } == true
    }

    private suspend fun auditRequest(exchange: Exchange, requestId: String): AuditRequestMetadata =
        AuditRequestMetadata(
            requestId = requestId,
            method = exchange.request.method.name,
            path = exchange.request.path.take(4_096),
            userAgent = auditRedactor.userAgent(singleSafeHeader(exchange, "User-Agent"))
        )

    private fun singleSafeHeader(exchange: Exchange, name: String): String? {
        val values = exchange.request.headers.getAll(name)
        if (values.size > 1) return null
        return values.singleOrNull()?.takeIf { value ->
            value.length <= 8_192 && value.none(::isProtocolControl)
        }
    }

    private suspend fun methodNotAllowed(exchange: Exchange, requestId: String, allow: String) {
        exchange.response.setHeader("Allow", allow)
        fail(exchange, 405, OidcFederationHttpErrorCode.REQUEST_INVALID, requestId)
    }

    private suspend fun fail(
        exchange: Exchange,
        status: Int,
        code: OidcFederationHttpErrorCode,
        requestId: String
    ) {
        if (exchange.response.statusCode in 300..399 || exchange.response.statusCode in 400..599) return
        secureResponse(exchange)
        val error = OidcFederationHttpError(code = code, requestId = requestId)
        exchange.response.statusCode = status
        exchange.response.setHeader("Content-Type", "application/json; charset=utf-8")
        exchange.response.write(ERROR_JSON.encodeToString(error))
        exchange.response.end()
    }

    private suspend fun redirect(exchange: Exchange, location: String, status: Int) {
        secureResponse(exchange)
        exchange.response.statusCode = status
        exchange.response.setHeader("Location", location)
        exchange.response.end()
    }

    private fun secureResponse(exchange: Exchange) {
        exchange.response.setHeader("Cache-Control", "no-store")
        exchange.response.setHeader("Pragma", "no-cache")
        exchange.response.setHeader("Referrer-Policy", "no-referrer")
        exchange.response.setHeader("X-Content-Type-Options", "nosniff")
    }

    private fun stateCookie(value: String, maxAge: Long): Cookie = Cookie(
        name = config.stateCookieName,
        value = value,
        path = "/",
        maxAge = maxAge.coerceAtMost(900),
        secure = true,
        httpOnly = true,
        sameSite = Cookie.SameSite.LAX
    )

    private fun clearStateCookie(): Cookie = stateCookie("", 0)

    private fun identitySessionCookie(value: String): Cookie = Cookie(
        name = identityConfig.cookie.name,
        value = value,
        path = identityConfig.cookie.path,
        domain = identityConfig.cookie.domain,
        secure = identityConfig.cookie.secure,
        httpOnly = true,
        sameSite = identityConfig.cookie.sameSite.toCoreSameSite()
    )

    private fun csrfHandoffCookie(value: String): Cookie = Cookie(
        name = config.csrfCookieName,
        value = value,
        path = "/",
        maxAge = config.csrfCookieLifetimeSeconds,
        secure = true,
        httpOnly = false,
        sameSite = Cookie.SameSite.LAX
    )

    private fun parseRoute(path: String): FederationRoute? {
        if (path.length > 2_048 || '?' in path || '#' in path || !path.startsWith("$FEDERATION_BASE/")) return null
        val segments = path.removePrefix("$FEDERATION_BASE/").split('/')
        if (segments.size != 3 || segments.any(String::isEmpty)) return null
        val tenant = OrganizationId.parseOrNull(segments[0]) ?: return null
        if (!PROVIDER_ID.matches(segments[1])) return null
        val action = when (segments[2]) {
            "start" -> FederationAction.START
            "callback" -> FederationAction.CALLBACK
            else -> return null
        }
        return FederationRoute(tenant, segments[1], action)
    }

    private fun isFederationPath(path: String): Boolean =
        path == FEDERATION_BASE || path.startsWith("$FEDERATION_BASE/")

    private data class FederationRoute(
        val tenantId: OrganizationId,
        val providerId: String,
        val action: FederationAction
    )

    private enum class FederationAction { START, CALLBACK }

    private companion object {
        const val FEDERATION_BASE = "/identity/v1/federation"
        const val STATE_SELECTOR_BYTES = 32
        const val CALLBACK_BINDING_BYTES = 32
        const val REQUEST_ID_BYTES = 12
        const val MAX_SELECTOR_ATTEMPTS = 3
        const val MAXIMUM_COOKIE_HEADER = 8_192
        const val MAXIMUM_COOKIE_FIELDS = 64
        val CALLBACK_QUERY_FIELDS = setOf("code", "state")
        val PROVIDER_ID = Regex("[a-z0-9][a-z0-9_-]{0,62}")
        val STATE_SELECTOR = Regex("[A-Za-z0-9_-]{43}")
        val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,254}")
        val ERROR_JSON = Json { encodeDefaults = true; explicitNulls = false }
    }
}

private fun OidcFederationProviderRegistration.allowsAuthorizationRedirect(value: String): Boolean {
    if (value.length > 8_192 || '#' in value || isProtocolControlIn(value)) return false
    return allowedAuthorizationEndpoints.any { endpoint ->
        value.startsWith("$endpoint?") && value.length > endpoint.length + 1
    }
}

private fun requireRedirectEndpoint(value: String) {
    require(value.length in 8..4_096 && '?' !in value && '#' !in value && !isProtocolControlIn(value)) {
        "Invalid OIDC authorization endpoint"
    }
    IdentityHttpRequest(IdentityHttpMethod.GET, value)
}

private fun requireSafeRedirect(value: String) {
    require(value.length in 8..4_096 && '#' !in value && !isProtocolControlIn(value)) {
        "Invalid OIDC success redirect"
    }
    IdentityHttpRequest(IdentityHttpMethod.GET, value)
}

private fun decodeFormComponent(value: String): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < value.length) {
        when (val character = value[index]) {
            '%' -> {
                require(index + 2 < value.length)
                val high = value[index + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException()
                val low = value[index + 2].digitToIntOrNull(16) ?: throw IllegalArgumentException()
                bytes += ((high shl 4) or low).toByte()
                index += 3
            }
            '+' -> {
                bytes += ' '.code.toByte()
                index += 1
            }
            else -> {
                require(character.code !in 0xD800..0xDFFF)
                bytes += character.toString().encodeToByteArray().toList()
                index += 1
            }
        }
    }
    return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
}

private fun isProtocolControl(character: Char): Boolean = character.code < 0x20 || character.code == 0x7f
private fun isProtocolControlIn(value: String): Boolean = value.any(::isProtocolControl)

private fun SameSitePolicy.toCoreSameSite(): Cookie.SameSite = when (this) {
    SameSitePolicy.STRICT -> Cookie.SameSite.STRICT
    SameSitePolicy.LAX -> Cookie.SameSite.LAX
    SameSitePolicy.NONE -> Cookie.SameSite.NONE
}

private const val CALLBACK_BINDING_BYTES = 32
