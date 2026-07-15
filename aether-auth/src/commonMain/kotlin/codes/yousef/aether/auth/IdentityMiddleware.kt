package codes.yousef.aether.auth

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.auth.PrincipalAttributeKey
import codes.yousef.aether.core.pipeline.Middleware
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

val IdentityContextAttributeKey = AttributeKey("aether.identity.context", IdentityContext::class)
val IdentityRequestIdAttributeKey = AttributeKey("aether.identity.request-id", String::class)

val Exchange.identityContext: IdentityContext
    get() = attributes.get(IdentityContextAttributeKey) ?: IdentityContext.Anonymous

sealed interface IdentityResolutionResult {
    data object Anonymous : IdentityResolutionResult
    data class Authenticated(val context: IdentityContext) : IdentityResolutionResult {
        init { require(context.isAuthenticated) { "Authenticated resolution requires a principal" } }
    }
    data class Rejected(val code: IdentityErrorCode) : IdentityResolutionResult
}

fun interface IdentityContextResolver {
    suspend fun resolve(exchange: Exchange): IdentityResolutionResult
}

/**
 * Explicit downstream allowlist for a recovery/bootstrap/invitation enrollment session.
 * The secure default permits only passkey enrollment and logout HTTP calls.
 */
fun interface RestrictedEnrollmentRoutePolicy {
    fun allows(method: HttpMethod, path: String): Boolean

    companion object {
        val PASSKEY_ENROLLMENT_ONLY: RestrictedEnrollmentRoutePolicy =
            RestrictedEnrollmentRoutePolicy { method, path ->
                method == HttpMethod.POST && path in setOf(
                    IdentityHttpApi.REGISTRATION_START,
                    IdentityHttpApi.REGISTRATION_FINISH,
                    IdentityHttpApi.LOGOUT
                )
            }
    }
}

/** Resolves an optional or required identity and installs its request-scoped context. */
class IdentityMiddleware(
    private val resolver: IdentityContextResolver,
    private val clock: IdentityClock,
    private val secureRandom: IdentitySecureRandom,
    private val requestIdHeader: String = DEFAULT_IDENTITY_REQUEST_ID_HEADER,
    private val restrictedEnrollmentRoutePolicy: RestrictedEnrollmentRoutePolicy =
        RestrictedEnrollmentRoutePolicy.PASSKEY_ENROLLMENT_ONLY,
    private val required: Boolean = false
) {
    init {
        require(requestIdHeader.matches(IDENTITY_HTTP_TOKEN)) { "Invalid request-ID header name" }
    }

    fun asMiddleware(): Middleware = middleware@{ exchange, next ->
        exchange.ensureIdentityRequestId(secureRandom, requestIdHeader)
        val result = try {
            resolver.resolve(exchange)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            exchange.respondIdentityError(IdentityErrorCode.SERVICE_UNAVAILABLE)
            return@middleware
        }
        when (result) {
            IdentityResolutionResult.Anonymous -> {
                exchange.attributes.put(IdentityContextAttributeKey, IdentityContext.Anonymous)
                if (required) exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED) else next()
            }
            is IdentityResolutionResult.Rejected -> exchange.respondIdentityError(result.code)
            is IdentityResolutionResult.Authenticated -> {
                val context = result.context
                val session = context.session
                if (session?.state == SessionState.REVOKED || session?.state == SessionState.ROTATED) {
                    exchange.respondIdentityError(IdentityErrorCode.SESSION_REVOKED)
                } else {
                    val now = try {
                        clock.now()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        exchange.respondIdentityError(IdentityErrorCode.SERVICE_UNAVAILABLE)
                        return@middleware
                    }
                    if (!context.isSessionUsableAt(now)) {
                        exchange.respondIdentityError(IdentityErrorCode.SESSION_EXPIRED)
                    } else if (session?.assurance == AuthenticationAssurance.RECOVERY &&
                        !restrictedEnrollmentRoutePolicy.allows(exchange.request.method, exchange.request.path)
                    ) {
                        exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED)
                    } else {
                        exchange.attributes.put(IdentityContextAttributeKey, context)
                        if (session?.assurance != AuthenticationAssurance.RECOVERY) {
                            context.principal?.let { exchange.attributes.put(PrincipalAttributeKey, it) }
                        }
                        next()
                    }
                }
            }
        }
    }
}

/**
 * Establishes one validated correlation ID before any identity guard can reject a request.
 * Install this before standalone guards when [IdentityMiddleware] is not used.
 */
fun identityRequestId(
    secureRandom: IdentitySecureRandom,
    headerName: String = DEFAULT_IDENTITY_REQUEST_ID_HEADER
): Middleware {
    require(headerName.matches(IDENTITY_HTTP_TOKEN)) { "Invalid request-ID header name" }
    return { exchange, next ->
        exchange.ensureIdentityRequestId(secureRandom, headerName)
        next()
    }
}

internal fun Exchange.ensureIdentityRequestId(
    secureRandom: IdentitySecureRandom,
    headerName: String = DEFAULT_IDENTITY_REQUEST_ID_HEADER
): String {
    attributes.get(IdentityRequestIdAttributeKey)?.let { requestId ->
        response.setHeader(headerName, requestId)
        return requestId
    }
    val supplied = request.headers.getAll(headerName).singleOrNull()?.takeIf { candidate ->
        candidate.matches(IDENTITY_REQUEST_ID)
    }
    val requestId = supplied ?: secureRandom.nextBytes(IDENTITY_REQUEST_ID_BYTES).let { bytes ->
        try {
            "req_${Base64Url.encode(bytes)}"
        } finally {
            bytes.fill(0)
        }
    }
    attributes.put(IdentityRequestIdAttributeKey, requestId)
    response.setHeader(headerName, requestId)
    return requestId
}

fun requireIdentity(): Middleware = { exchange, next ->
    if (exchange.identityContext.isAuthenticated &&
        exchange.identityContext.session?.assurance != AuthenticationAssurance.RECOVERY
    ) next()
    else exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED)
}

/** Allows a restricted recovery session to reach passkey enrollment routes and nothing else. */
fun requireRecoveryEnrollmentSession(): Middleware = { exchange, next ->
    val context = exchange.identityContext
    val session = context.session
    if (context.principal?.kind == IdentityPrincipalKind.USER &&
        session?.state == SessionState.ACTIVE &&
        session.assurance == AuthenticationAssurance.RECOVERY &&
        context.principal.sessionId == session.id
    ) next() else exchange.respondIdentityError(IdentityErrorCode.AUTHENTICATION_REQUIRED)
}

/** Missing and unauthorized organizations intentionally share the same public result. */
fun requireOrganization(expected: OrganizationId? = null): Middleware = { exchange, next ->
    val context = exchange.identityContext
    val organization = context.organization
    val authorized = organization != null &&
        organization.state == OrganizationState.ACTIVE &&
        context.session?.assurance != AuthenticationAssurance.RECOVERY &&
        (expected == null || organization.id == expected) &&
        (context.principal?.kind == IdentityPrincipalKind.SERVICE ||
            context.principal?.kind == IdentityPrincipalKind.DEVICE ||
            context.membership?.state == MembershipState.ACTIVE)
    if (authorized) next() else exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND)
}

fun requireOrganizationRole(vararg accepted: OrganizationRole): Middleware {
    require(accepted.isNotEmpty()) { "At least one organization role is required" }
    val roles = accepted.toSet()
    return { exchange, next ->
        val context = exchange.identityContext
        if (context.principal?.kind == IdentityPrincipalKind.USER &&
            context.session?.state == SessionState.ACTIVE &&
            context.session.assurance != AuthenticationAssurance.RECOVERY &&
            context.organization?.state == OrganizationState.ACTIVE &&
            context.membership?.state == MembershipState.ACTIVE &&
            context.membership.role in roles
        ) {
            next()
        } else {
            exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND)
        }
    }
}

fun requireCapability(
    capability: Capability,
    resolver: CapabilityResolver = CapabilityResolver.NONE
): Middleware = { exchange, next ->
    if (exchange.identityContext.hasCapability(capability, resolver)) next()
    else exchange.respondIdentityError(IdentityErrorCode.NOT_FOUND)
}

fun requireRecentPasskey(maxAge: IdentityDuration = IdentityDuration.minutes(5)): Middleware = { exchange, next ->
    val principal = exchange.identityContext.principal
    val now = exchange.attributes.get(IdentityRequestTimeAttributeKey)
    val recent = principal != null &&
        principal.assurance.satisfies(AuthenticationAssurance.PASSKEY) &&
        now != null &&
        principal.authenticatedAt <= now &&
        now - principal.authenticatedAt <= maxAge.seconds.seconds
    if (recent) next() else exchange.respondIdentityError(IdentityErrorCode.STEP_UP_REQUIRED)
}

/**
 * Captures one request time so all downstream guards evaluate the same instant.
 * Install this before [requireRecentPasskey].
 */
val IdentityRequestTimeAttributeKey = AttributeKey("aether.identity.request-time", kotlin.time.Instant::class)

fun identityRequestTime(clock: IdentityClock): Middleware = { exchange, next ->
    exchange.attributes.put(IdentityRequestTimeAttributeKey, clock.now())
    next()
}

suspend fun Exchange.respondIdentityError(code: IdentityErrorCode, requestId: String? = null) {
    response.statusCode = code.httpStatus
    response.setHeader("Content-Type", "application/json; charset=utf-8")
    response.setHeader("Cache-Control", "no-store")
    response.write(Json.encodeToString(IdentityErrorEnvelope(
        IdentityErrorDto(code, requestId ?: attributes.get(IdentityRequestIdAttributeKey))
    )))
    response.end()
}

const val DEFAULT_IDENTITY_REQUEST_ID_HEADER = "X-Request-ID"
private val IDENTITY_HTTP_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
private val IDENTITY_REQUEST_ID = Regex("[A-Za-z0-9._:-]{1,255}")
private const val IDENTITY_REQUEST_ID_BYTES = 16
