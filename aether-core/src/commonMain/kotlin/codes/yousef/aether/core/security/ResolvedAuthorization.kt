package codes.yousef.aether.core.security

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.Middleware
import kotlin.time.Clock
import kotlin.jvm.JvmInline

/** Stable denial categories suitable for translation by an application error adapter. */
enum class AuthorizationDenial(val statusCode: Int, val wireName: String) {
    AUTHENTICATION_REQUIRED(401, "authentication_required"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not_found"),
    FEATURE_DISABLED(404, "feature_disabled"),
    EXPIRED(403, "expired"),
    STALE_GRANT(403, "stale_grant")
}

sealed interface AuthorizationDecision {
    data object Allow : AuthorizationDecision
    data class Deny(val reason: AuthorizationDenial) : AuthorizationDecision
}

/** Input whose actor and resource were installed by trusted upstream resolvers, not request fields. */
data class ResolvedAuthorizationInput<Actor : Any, Resource : Any, Action : Any>(
    val actor: Actor,
    val resource: Resource,
    val action: Action,
    /** Captured immediately before policy evaluation so expiry checks use request-time state. */
    val evaluatedAtEpochMilliseconds: Long
)

fun interface ResolvedAuthorizationPolicy<Actor : Any, Resource : Any, Action : Any> {
    suspend fun evaluate(input: ResolvedAuthorizationInput<Actor, Resource, Action>): AuthorizationDecision
}
fun interface AuthorizationTimeSource {
    fun nowEpochMilliseconds(): Long
}

data class AuthorizationValidity(
    val grantedEpoch: Long,
    val currentEpoch: Long,
    val expiresAtEpochMilliseconds: Long? = null
) {
    init {
        require(grantedEpoch >= 0 && currentEpoch >= 0) { "Authorization epochs must be non-negative" }
        require(expiresAtEpochMilliseconds == null || expiresAtEpochMilliseconds >= 0) {
            "Authorization expiry must be non-negative"
        }
    }

    fun denialAt(evaluatedAtEpochMilliseconds: Long): AuthorizationDenial? = when {
        grantedEpoch != currentEpoch -> AuthorizationDenial.STALE_GRANT
        expiresAtEpochMilliseconds != null &&
            evaluatedAtEpochMilliseconds >= expiresAtEpochMilliseconds -> AuthorizationDenial.EXPIRED
        else -> null
    }
}

fun interface AuthorizationValidityResolver<Resource : Any> {
    suspend fun resolve(resource: Resource): AuthorizationValidity
}

typealias AuthorizationDenialHandler = suspend (Exchange, AuthorizationDenial) -> Unit

/**
 * Evaluates a policy only from typed server-installed attributes and a server-selected action.
 * Request query, path, headers, and body cannot replace [actorKey], [resourceKey], or [action].
 */
fun <Actor : Any, Resource : Any, Action : Any> requireResolvedAuthorization(
    actorKey: AttributeKey<Actor>,
    resourceKey: AttributeKey<Resource>,
    action: Action,
    policy: ResolvedAuthorizationPolicy<Actor, Resource, Action>,
    denialHandler: AuthorizationDenialHandler = ::respondAuthorizationDenial,
    timeSource: AuthorizationTimeSource = AuthorizationTimeSource {
        Clock.System.now().toEpochMilliseconds()
    }
): Middleware = resolvedAuthorization(
    actorKey = actorKey,
    resourceKey = resourceKey,
    action = action,
    policy = policy,
    denialHandler = denialHandler,
    timeSource = timeSource,
    validityResolver = null
)

/**
 * Versioned/leased policy variant. Freshness is resolved at execution and checked before policy
 * evaluation, so a stale or expired grant cannot authorize a read or write.
 */
fun <Actor : Any, Resource : Any, Action : Any> requireCurrentResolvedAuthorization(
    actorKey: AttributeKey<Actor>,
    resourceKey: AttributeKey<Resource>,
    action: Action,
    validityResolver: AuthorizationValidityResolver<Resource>,
    policy: ResolvedAuthorizationPolicy<Actor, Resource, Action>,
    denialHandler: AuthorizationDenialHandler = ::respondAuthorizationDenial,
    timeSource: AuthorizationTimeSource = AuthorizationTimeSource {
        Clock.System.now().toEpochMilliseconds()
    }
): Middleware = resolvedAuthorization(
    actorKey = actorKey,
    resourceKey = resourceKey,
    action = action,
    policy = policy,
    denialHandler = denialHandler,
    timeSource = timeSource,
    validityResolver = validityResolver
)

private fun <Actor : Any, Resource : Any, Action : Any> resolvedAuthorization(
    actorKey: AttributeKey<Actor>,
    resourceKey: AttributeKey<Resource>,
    action: Action,
    policy: ResolvedAuthorizationPolicy<Actor, Resource, Action>,
    denialHandler: AuthorizationDenialHandler,
    timeSource: AuthorizationTimeSource,
    validityResolver: AuthorizationValidityResolver<Resource>?
): Middleware = middleware@{ exchange, next ->
    val actor = exchange.attributes.get(actorKey)
    if (actor == null) {
        denialHandler(exchange, AuthorizationDenial.AUTHENTICATION_REQUIRED)
        return@middleware
    }
    val resource = exchange.attributes.get(resourceKey)
    if (resource == null) {
        denialHandler(exchange, AuthorizationDenial.NOT_FOUND)
        return@middleware
    }
    val evaluatedAt = timeSource.nowEpochMilliseconds()
    val validityDenial = validityResolver?.resolve(resource)?.denialAt(evaluatedAt)
    if (validityDenial != null) {
        denialHandler(exchange, validityDenial)
        return@middleware
    }
    when (val decision = policy.evaluate(ResolvedAuthorizationInput(actor, resource, action, evaluatedAt))) {
        AuthorizationDecision.Allow -> next()
        is AuthorizationDecision.Deny -> denialHandler(exchange, decision.reason)
    }
}

@JvmInline
value class FeatureId(val value: String) {
    init {
        require(Regex("[a-z][a-z0-9_.-]{0,63}").matches(value)) { "Invalid feature ID" }
    }

    override fun toString(): String = value
}

/** Shared or persistent feature state keyed only by a server-declared [FeatureId]. */
fun interface FeatureStateProvider {
    suspend fun isEnabled(feature: FeatureId): Boolean
}

fun requireFeature(
    feature: FeatureId,
    provider: FeatureStateProvider,
    denialHandler: AuthorizationDenialHandler = ::respondAuthorizationDenial
): Middleware = middleware@{ exchange, next ->
    if (!provider.isEnabled(feature)) {
        denialHandler(exchange, AuthorizationDenial.FEATURE_DISABLED)
        return@middleware
    }
    next()
}

private suspend fun respondAuthorizationDenial(exchange: Exchange, denial: AuthorizationDenial) {
    exchange.response.statusCode = denial.statusCode
    exchange.response.setHeader("Content-Type", "text/plain; charset=utf-8")
    exchange.response.setHeader("Cache-Control", "no-store")
    exchange.response.write(denial.wireName)
    exchange.response.end()
}
