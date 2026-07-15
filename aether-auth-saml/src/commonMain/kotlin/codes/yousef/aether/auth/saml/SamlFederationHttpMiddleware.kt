package codes.yousef.aether.auth.saml

import codes.yousef.aether.auth.AuditRequestMetadata
import codes.yousef.aether.auth.IdentityAuditRedactor
import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.DeviceMetadata
import codes.yousef.aether.auth.FederationCallbackStateConsumeResult
import codes.yousef.aether.auth.FederationCallbackStateStore
import codes.yousef.aether.auth.FederationCallbackStateWriteResult
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.FederatedIdentitySessionCreator
import codes.yousef.aether.auth.FederatedIdentitySessionRequest
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

private val SAML_COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
private val SAML_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")

class SamlFederationProviderRegistration(
    val provider: SamlFederationProvider,
    allowedSsoRedirectEndpoints: Set<String>,
    val successRedirectUrl: String
) {
    val allowedSsoRedirectEndpoints: Set<String> = allowedSsoRedirectEndpoints.toSet()

    init {
        require(this.allowedSsoRedirectEndpoints.isNotEmpty() &&
            this.allowedSsoRedirectEndpoints.size <= 8
        ) { "At least one bounded SAML SSO endpoint is required" }
        this.allowedSsoRedirectEndpoints.forEach(::requireRedirectEndpoint)
        requireSafeRedirect(successRedirectUrl)
    }

    override fun toString(): String =
        "SamlFederationProviderRegistration(tenant=${provider.configuredTenantId}, " +
            "provider=${provider.configuredProviderId}, ssoEndpoints=<redacted>, successRedirect=<redacted>)"
}

sealed interface SamlFederationProviderResolution {
    data class Found(val registration: SamlFederationProviderRegistration) : SamlFederationProviderResolution
    /** The exact route belongs to another installed federation adapter (for example OIDC). */
    data object NotOwned : SamlFederationProviderResolution
    data object Missing : SamlFederationProviderResolution
    data object Unavailable : SamlFederationProviderResolution
}

fun interface SamlFederationProviderRegistry {
    suspend fun resolve(tenantId: OrganizationId, providerId: String): SamlFederationProviderResolution
}

/** Server-only SAML request correlation; RelayState and request IDs never enter the state cookie. */
class SamlServerCallbackState internal constructor(
    val providerLease: FederationProviderLease,
    internal val authenticationState: SamlAuthenticationState,
    val predecessorSessionId: SessionId?,
    val expectedPredecessorVersion: Long?,
    val expiresAt: Instant
) {
    val tenantId: OrganizationId get() = providerLease.organizationId
    val providerId: String get() = providerLease.providerId

    init {
        require(providerLease.kind == FederationProviderKind.SAML &&
            authenticationState.providerLease == providerLease
        ) { "SAML callback state requires one exact SAML provider lease" }
        require(authenticationState.expiresAt == expiresAt) {
            "SAML callback state expiry must match its authentication state"
        }
        require((predecessorSessionId == null) == (expectedPredecessorVersion == null)) {
            "SAML predecessor selector and version must either both be present or both be absent"
        }
        require(expectedPredecessorVersion == null || expectedPredecessorVersion >= 0) {
            "SAML predecessor version must not be negative"
        }
    }

    /**
     * Supplies defensive copies to an application-owned authenticated-encryption boundary for a
     * distributed server-side state store. The block must not return or log RelayState.
     */
    suspend fun <T> useForProtection(
        block: suspend (
            providerLease: FederationProviderLease,
            challengeId: codes.yousef.aether.auth.ChallengeId,
            requestId: String,
            relayState: ByteArray,
            linkToUserId: codes.yousef.aether.auth.UserId?,
            predecessorSessionId: SessionId?,
            expectedPredecessorVersion: Long?,
            expiresAt: Instant
        ) -> T
    ): T {
        val relay = authenticationState.relayStateBytes()
        return try {
            block(
                providerLease,
                authenticationState.challengeId,
                authenticationState.requestId,
                relay,
                authenticationState.linkToUserId,
                predecessorSessionId,
                expectedPredecessorVersion,
                expiresAt
            )
        } finally {
            relay.fill(0)
        }
    }

    /** Zero callback material when a store evicts or expires this record without consuming it. */
    fun destroy() { authenticationState.destroy() }

    override fun toString(): String =
        "SamlServerCallbackState(tenantId=$tenantId, providerId=$providerId, " +
            "authenticationState=<redacted>, " +
            "predecessor=${if (predecessorSessionId == null) "none" else "present"}, expiresAt=$expiresAt)"

    companion object {
        /** Restore only after application-owned authenticated decryption of server-side state. */
        fun restore(
            providerLease: FederationProviderLease,
            challengeId: codes.yousef.aether.auth.ChallengeId,
            requestId: String,
            relayState: ByteArray,
            linkToUserId: codes.yousef.aether.auth.UserId?,
            expiresAt: Instant,
            predecessorSessionId: SessionId? = null,
            expectedPredecessorVersion: Long? = null
        ): SamlServerCallbackState = SamlServerCallbackState(
            providerLease = providerLease,
            authenticationState = SamlAuthenticationState(
                challengeId,
                requestId,
                relayState,
                linkToUserId,
                providerLease,
                expiresAt
            ),
            predecessorSessionId = predecessorSessionId,
            expectedPredecessorVersion = expectedPredecessorVersion,
            expiresAt = expiresAt
        )
    }
}

data class SamlFederationHttpConfig(
    val stateCookieName: String = "__Host-aether_saml_state",
    val csrfCookieName: String = "__Host-aether_csrf",
    val requestIdHeader: String = "X-Request-ID",
    val maximumBodyBytes: Int = 8_388_608,
    val maximumEncodedResponseCharacters: Int = 4_194_304,
    val csrfCookieLifetimeSeconds: Long = 300
) {
    init {
        require(SAML_COOKIE_NAME.matches(stateCookieName) && stateCookieName.startsWith("__Host-")) {
            "SAML state cookie must be a valid __Host- cookie"
        }
        require(SAML_COOKIE_NAME.matches(csrfCookieName) && csrfCookieName.startsWith("__Host-")) {
            "SAML CSRF handoff cookie must be a valid __Host- cookie"
        }
        require(SAML_HEADER_NAME.matches(requestIdHeader)) { "Invalid request-ID header" }
        require(maximumBodyBytes in 4_096..16_777_216) { "SAML form limit must be 4 KiB..16 MiB" }
        require(maximumEncodedResponseCharacters in 4_096..8_388_608) {
            "SAML response limit must be 4 KiB..8 MiB"
        }
        require(maximumEncodedResponseCharacters <= maximumBodyBytes) {
            "SAML response limit must not exceed the form-body limit"
        }
        require(csrfCookieLifetimeSeconds in 30..600) { "SAML CSRF handoff lifetime must be 30..600 seconds" }
    }
}

@Serializable
enum class SamlFederationHttpErrorCode {
    @SerialName("request_invalid") REQUEST_INVALID,
    @SerialName("provider_not_found") PROVIDER_NOT_FOUND,
    @SerialName("identity_response_invalid") IDENTITY_RESPONSE_INVALID,
    @SerialName("service_unavailable") SERVICE_UNAVAILABLE
}

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class SamlFederationHttpError(
    val code: SamlFederationHttpErrorCode,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val message: String = code.genericMessage,
    val requestId: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val retryable: Boolean = code.defaultRetryable
) {
    init {
        require(message == code.genericMessage) { "SAML federation errors must use the stable generic message" }
        require(retryable == code.defaultRetryable) { "SAML federation retryability is fixed by error code" }
    }
}

internal val SamlFederationHttpErrorCode.genericMessage: String
    get() = when (this) {
        SamlFederationHttpErrorCode.REQUEST_INVALID -> "The federation request is invalid."
        SamlFederationHttpErrorCode.PROVIDER_NOT_FOUND -> "The identity provider was not found."
        SamlFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID -> "The identity response could not be accepted."
        SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE -> "The identity service is temporarily unavailable."
    }

internal val SamlFederationHttpErrorCode.defaultRetryable: Boolean
    get() = this == SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE

/** Common-code HTTP-Redirect/POST SAML transport on the fixed federation route. */
class SamlFederationHttpMiddleware(
    private val runtime: IdentityRuntime,
    private val identityConfig: IdentityConfig,
    private val providers: SamlFederationProviderRegistry,
    private val callbackStates: FederationCallbackStateStore<SamlServerCallbackState>,
    private val sessions: FederatedIdentitySessionCreator,
    private val config: SamlFederationHttpConfig = SamlFederationHttpConfig()
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
            fail(exchange, 400, SamlFederationHttpErrorCode.REQUEST_INVALID, requestId)
            return@middleware
        }
        try {
            val route = parseRoute(exchange.request.path)
            if (route == null) {
                fail(exchange, 404, SamlFederationHttpErrorCode.PROVIDER_NOT_FOUND, requestId)
                return@middleware
            }
            val resolution = try {
                providers.resolve(route.tenantId, route.providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                SamlFederationProviderResolution.Unavailable
            }
            val registration = when (resolution) {
                is SamlFederationProviderResolution.Found -> resolution.registration
                SamlFederationProviderResolution.NotOwned -> {
                    next()
                    return@middleware
                }
                SamlFederationProviderResolution.Missing -> {
                    fail(exchange, 404, SamlFederationHttpErrorCode.PROVIDER_NOT_FOUND, requestId)
                    return@middleware
                }
                SamlFederationProviderResolution.Unavailable -> {
                    fail(exchange, 503, SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
                    return@middleware
                }
            }
            if (registration.provider.configuredTenantId != route.tenantId ||
                registration.provider.configuredProviderId != route.providerId
            ) {
                fail(exchange, 404, SamlFederationHttpErrorCode.PROVIDER_NOT_FOUND, requestId)
                return@middleware
            }

            when (route.action) {
                FederationAction.START -> handleStart(exchange, registration, route, requestId)
                FederationAction.CALLBACK -> handleCallback(exchange, registration, route, requestId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail(exchange, 503, SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
        }
    }

    private suspend fun handleStart(
        exchange: Exchange,
        registration: SamlFederationProviderRegistration,
        route: FederationRoute,
        requestId: String
    ) {
        if (exchange.request.method != HttpMethod.GET) {
            methodNotAllowed(exchange, requestId, "GET")
            return
        }
        if (!exchange.request.query.isNullOrEmpty() || !hasNoBody(exchange)) {
            fail(exchange, 400, SamlFederationHttpErrorCode.REQUEST_INVALID, requestId)
            return
        }
        previousStateSelector(exchange)?.let { selector ->
            try {
                val stale = callbackStates.consume(selector)
                if (stale is FederationCallbackStateConsumeResult.Consumed) stale.state.destroy()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Best-effort stale-state cleanup must not reveal store state.
            }
        }

        val started = registration.provider.beginAuthentication(SamlAuthenticationRequest())
        val value = when (started) {
            is SamlResult.Success -> started.value
            is SamlResult.Failure -> {
                val disabled = started.error.code == SamlErrorCode.PROVIDER_DISABLED
                fail(
                    exchange,
                    if (disabled) 404 else if (started.error.retryable) 503 else 400,
                    if (disabled) SamlFederationHttpErrorCode.PROVIDER_NOT_FOUND
                    else if (started.error.retryable) SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE
                    else SamlFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID,
                    requestId
                )
                return
            }
        }
        if (!registration.allowsSsoRedirect(value.redirectUrl)) {
            value.state.destroy()
            fail(exchange, 503, SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
            return
        }
        if (value.state.providerLease.organizationId != route.tenantId ||
            value.state.providerLease.providerId != route.providerId ||
            value.state.providerLease.kind != FederationProviderKind.SAML
        ) {
            value.state.destroy()
            fail(exchange, 404, SamlFederationHttpErrorCode.PROVIDER_NOT_FOUND, requestId)
            return
        }
        val predecessor = exchange.identityContext.session
        val state = SamlServerCallbackState(
            providerLease = value.state.providerLease,
            authenticationState = value.state,
            predecessorSessionId = predecessor?.id,
            expectedPredecessorVersion = predecessor?.version,
            expiresAt = value.expiresAt
        )
        val selector = storeState(state)
        if (selector == null) {
            state.destroy()
            fail(exchange, 503, SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
            return
        }
        val lifetime = (value.expiresAt.toEpochMilliseconds() - runtime.clock.now().toEpochMilliseconds()) / 1_000
        if (lifetime <= 0) {
            callbackStates.consume(selector)
            state.destroy()
            fail(exchange, 400, SamlFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
            return
        }
        exchange.response.setCookie(stateCookie(selector, lifetime))
        redirect(exchange, value.redirectUrl, 302)
    }

    private suspend fun handleCallback(
        exchange: Exchange,
        registration: SamlFederationProviderRegistration,
        route: FederationRoute,
        requestId: String
    ) {
        exchange.response.setCookie(clearStateCookie())
        if (exchange.request.method != HttpMethod.POST) {
            methodNotAllowed(exchange, requestId, "POST")
            return
        }
        val selector = stateSelector(exchange)
        val form = decodeCallbackForm(exchange)
        if (selector == null || form == null) {
            fail(exchange, 400, SamlFederationHttpErrorCode.REQUEST_INVALID, requestId)
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
                fail(exchange, 400, SamlFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
                return
            }
            FederationCallbackStateConsumeResult.Unavailable -> {
                fail(exchange, 503, SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE, requestId)
                return
            }
        }
        try {
            if (state.tenantId != route.tenantId || state.providerId != route.providerId ||
                state.expiresAt <= runtime.clock.now()
            ) {
                fail(exchange, 400, SamlFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
                return
            }

            val audit = auditRequest(exchange, requestId)
            val completed = try {
                registration.provider.completeAuthentication(
                    SamlPostResponseRequest(
                        samlResponse = form.getValue("SAMLResponse"),
                        relayState = form.getValue("RelayState"),
                        state = state.authenticationState,
                        auditRequest = audit
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                SamlResult.Failure(SamlError(SamlErrorCode.STORE_UNAVAILABLE))
            }
            val authenticated = when (completed) {
                is SamlResult.Success -> completed.value
                is SamlResult.Failure -> {
                    val disabled = completed.error.code == SamlErrorCode.PROVIDER_DISABLED
                    fail(
                        exchange,
                        if (disabled) 404 else if (completed.error.retryable) 503 else 400,
                        if (disabled) SamlFederationHttpErrorCode.PROVIDER_NOT_FOUND
                        else if (completed.error.retryable) SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE
                        else SamlFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID,
                        requestId
                    )
                    return
                }
            }
            if (authenticated.providerLease != state.providerLease ||
                authenticated.providerLease.organizationId != route.tenantId ||
                authenticated.providerLease.providerId != route.providerId ||
                authenticated.authenticationMethod != codes.yousef.aether.auth.SessionAuthenticationMethod.SAML ||
                authenticated.assurance != codes.yousef.aether.auth.AuthenticationAssurance.SESSION ||
                !authenticated.passkeyStepUpRequiredForSensitiveActions
            ) {
                fail(exchange, 400, SamlFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
                return
            }
            val currentSession = exchange.identityContext.session
            if (state.predecessorSessionId != null && currentSession != null &&
                state.predecessorSessionId != currentSession.id
            ) {
                fail(exchange, 400, SamlFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID, requestId)
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
                        if (unavailable) SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE
                        else SamlFederationHttpErrorCode.IDENTITY_RESPONSE_INVALID,
                        requestId
                    )
                    return
                }
            }
            exchange.response.setCookie(identitySessionCookie(issued.cookieValue()))
            exchange.response.setCookie(csrfHandoffCookie(issued.csrfToken()))
            redirect(exchange, registration.successRedirectUrl, 303)
        } finally {
            state.destroy()
        }
    }

    private suspend fun storeState(state: SamlServerCallbackState): String? {
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

    private suspend fun decodeCallbackForm(exchange: Exchange): Map<String, String>? {
        if (!hasFormContentType(exchange)) return null
        if (exchange.request.headers.getAll("Transfer-Encoding").isNotEmpty()) return null
        val lengthValues = exchange.request.headers.getAll("Content-Length")
        if (lengthValues.size > 1) return null
        val declaredLength = lengthValues.singleOrNull()?.let { value ->
            if (value.isEmpty() || value.length > 20 || value.any { !it.isDigit() }) return null
            value.toLongOrNull() ?: return null
        }
        if (declaredLength != null && (declaredLength <= 0 || declaredLength > config.maximumBodyBytes)) return null
        val bytes = exchange.request.bodyBytes()
        if (bytes.isEmpty() || bytes.size > config.maximumBodyBytes ||
            (declaredLength != null && declaredLength != bytes.size.toLong())
        ) {
            bytes.fill(0)
            return null
        }
        return try {
            parseCallbackForm(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun parseCallbackForm(encoded: String): Map<String, String>? {
        val fields = encoded.split('&')
        if (fields.size != 2) return null
        val result = linkedMapOf<String, String>()
        for (field in fields) {
            val separator = field.indexOf('=')
            if (separator <= 0) return null
            val name = decodeFormComponent(field.substring(0, separator))
            if (name !in CALLBACK_FORM_FIELDS || result.containsKey(name)) return null
            val value = decodeFormComponent(field.substring(separator + 1))
            if (value.any(::isProtocolControl)) return null
            result[name] = value
        }
        val samlResponse = result["SAMLResponse"] ?: return null
        val relayState = result["RelayState"] ?: return null
        if (samlResponse.isEmpty() || samlResponse.length > config.maximumEncodedResponseCharacters ||
            relayState.length !in 16..80 || relayState.any(Char::isWhitespace)
        ) return null
        return result
    }

    private fun hasFormContentType(exchange: Exchange): Boolean {
        val values = exchange.request.headers.getAll("Content-Type")
        if (values.size != 1) return false
        val value = values.single()
        if (value.length > 256 || value.any(::isProtocolControl)) return false
        val parts = value.split(';').map(String::trim)
        if (!parts.first().equals("application/x-www-form-urlencoded", ignoreCase = true)) return false
        return parts.drop(1).all { it.equals("charset=utf-8", ignoreCase = true) }
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
        exchange.request.headers.getAll(config.requestIdHeader)
            .singleOrNull()
            ?.takeIf(REQUEST_ID::matches)
            ?.let { return it }
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
        return values.singleOrNull()?.takeIf { it.length <= 8_192 && it.none(::isProtocolControl) }
    }

    private suspend fun methodNotAllowed(exchange: Exchange, requestId: String, allow: String) {
        exchange.response.setHeader("Allow", allow)
        fail(exchange, 405, SamlFederationHttpErrorCode.REQUEST_INVALID, requestId)
    }

    private suspend fun fail(
        exchange: Exchange,
        status: Int,
        code: SamlFederationHttpErrorCode,
        requestId: String
    ) {
        if (exchange.response.statusCode in 300..599) return
        secureResponse(exchange)
        val error = SamlFederationHttpError(code = code, requestId = requestId)
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
        // SAML HTTP-POST is cross-site. Explicit Lax cookies are omitted by browsers here; this
        // short-lived selector is not session authority and must use None to preserve login-CSRF
        // binding. The resulting identity session cookie remains Lax.
        sameSite = Cookie.SameSite.NONE
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
        const val REQUEST_ID_BYTES = 12
        const val MAX_SELECTOR_ATTEMPTS = 3
        const val MAXIMUM_COOKIE_HEADER = 8_192
        const val MAXIMUM_COOKIE_FIELDS = 64
        val CALLBACK_FORM_FIELDS = setOf("SAMLResponse", "RelayState")
        val PROVIDER_ID = Regex("[a-z0-9][a-z0-9_-]{0,62}")
        val STATE_SELECTOR = Regex("[A-Za-z0-9_-]{43}")
        val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,254}")
        val ERROR_JSON = Json { encodeDefaults = true; explicitNulls = false }
    }
}

private fun SamlFederationProviderRegistration.allowsSsoRedirect(value: String): Boolean {
    if (value.length > 262_144 || '#' in value || isProtocolControlIn(value)) return false
    return allowedSsoRedirectEndpoints.any { endpoint ->
        value.startsWith("$endpoint?") && value.length > endpoint.length + 1
    }
}

private fun requireRedirectEndpoint(value: String) {
    require(value.length in 8..4_096 && '?' !in value && '#' !in value && !isProtocolControlIn(value)) {
        "Invalid SAML SSO endpoint"
    }
    IdentityHttpRequest(IdentityHttpMethod.GET, value)
}

private fun requireSafeRedirect(value: String) {
    require(value.length in 8..4_096 && '#' !in value && !isProtocolControlIn(value)) {
        "Invalid SAML success redirect"
    }
    IdentityHttpRequest(IdentityHttpMethod.GET, value)
}

private fun decodeFormComponent(value: String): String {
    val output = ByteArray(value.length)
    var inputIndex = 0
    var outputIndex = 0
    while (inputIndex < value.length) {
        when (val character = value[inputIndex]) {
            '%' -> {
                require(inputIndex + 2 < value.length)
                val high = value[inputIndex + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException()
                val low = value[inputIndex + 2].digitToIntOrNull(16) ?: throw IllegalArgumentException()
                output[outputIndex++] = ((high shl 4) or low).toByte()
                inputIndex += 3
            }
            '+' -> {
                output[outputIndex++] = ' '.code.toByte()
                inputIndex += 1
            }
            else -> {
                require(character.code in 0..0x7f) { "Non-ASCII form input must be percent encoded" }
                output[outputIndex++] = character.code.toByte()
                inputIndex += 1
            }
        }
    }
    val exact = output.copyOf(outputIndex)
    return try {
        exact.decodeToString(throwOnInvalidSequence = true)
    } finally {
        exact.fill(0)
        output.fill(0)
    }
}

private fun isProtocolControl(character: Char): Boolean = character.code < 0x20 || character.code == 0x7f
private fun isProtocolControlIn(value: String): Boolean = value.any(::isProtocolControl)

private fun SameSitePolicy.toCoreSameSite(): Cookie.SameSite = when (this) {
    SameSitePolicy.STRICT -> Cookie.SameSite.STRICT
    SameSitePolicy.LAX -> Cookie.SameSite.LAX
    SameSitePolicy.NONE -> Cookie.SameSite.NONE
}
