package codes.yousef.aether.core.security

import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.core.pipeline.Pipeline

/**
 * Application-supplied middleware for one fail-closed private-route pipeline.
 *
 * The profile fixes registration order without assigning product semantics to Aether. Transport
 * adapters remain responsible for enforcing decoded body limits before this pipeline is entered.
 * [safeErrorBoundary] surrounds authentication and every later stage. [redactedAudit] is the final
 * middleware around the use-case handler, so its `finally` block observes the use-case outcome.
 */
data class SecureApplicationPipelineProfile(
    val requestIdentity: Middleware,
    val limitsAndTypeValidation: Middleware,
    val safeErrorBoundary: Middleware,
    val authentication: Middleware,
    val originAndCsrf: Middleware,
    val tenantOrDeviceGrant: Middleware,
    val objectActionAuthorization: Middleware,
    val idempotency: Middleware,
    val redactedAudit: Middleware
)

/** Builds a pipeline in the security-significant order declared by [profile]. */
fun secureApplicationPipeline(profile: SecureApplicationPipelineProfile): Pipeline = Pipeline().apply {
    use(profile.requestIdentity)
    use(profile.limitsAndTypeValidation)
    use(profile.safeErrorBoundary)
    use(profile.authentication)
    use(profile.originAndCsrf)
    use(profile.tenantOrDeviceGrant)
    use(profile.objectActionAuthorization)
    use(profile.idempotency)
    use(profile.redactedAudit)
}
