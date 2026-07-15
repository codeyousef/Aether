package codes.yousef.aether.example

import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.CapabilityResolver
import codes.yousef.aether.auth.CompositeIdentityContextResolver
import codes.yousef.aether.auth.DefaultIdentityService
import codes.yousef.aether.auth.IdentityAccountManagementService
import codes.yousef.aether.auth.IdentityBootstrapService
import codes.yousef.aether.auth.IdentityBearerAuthenticator
import codes.yousef.aether.auth.IdentityConfig
import codes.yousef.aether.auth.IdentityContextResolver
import codes.yousef.aether.auth.IdentityDeviceAuthorizationService
import codes.yousef.aether.auth.IdentityEnvironment
import codes.yousef.aether.auth.IdentityHttpApi
import codes.yousef.aether.auth.IdentityHttpManagementServices
import codes.yousef.aether.auth.IdentityKeyConfig
import codes.yousef.aether.auth.IdentityMiddleware
import codes.yousef.aether.auth.IdentityOrganizationService
import codes.yousef.aether.auth.IdentityRecoveryService
import codes.yousef.aether.auth.IdentityRecoveryAttempt
import codes.yousef.aether.auth.IdentityRecoveryAttemptKey
import codes.yousef.aether.auth.IdentityRecoveryAttemptLimiter
import codes.yousef.aether.auth.IdentityRuntime
import codes.yousef.aether.auth.IdentitySecret
import codes.yousef.aether.auth.IdentitySecretResolver
import codes.yousef.aether.auth.IdentityServiceIdentityService
import codes.yousef.aether.auth.JvmIdentitySecureRandom
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.RegistrationPolicy
import codes.yousef.aether.auth.RelyingPartyConfig
import codes.yousef.aether.auth.RestrictedEnrollmentRoutePolicy
import codes.yousef.aether.auth.SecretReference
import codes.yousef.aether.auth.StoreResult
import codes.yousef.aether.auth.jvmIdentityRuntime
import codes.yousef.aether.auth.testkit.InMemoryIdentityStore
import codes.yousef.aether.auth.webauthn.WebAuthnService
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.pipeline.Middleware
import java.util.concurrent.ConcurrentHashMap

/**
 * Development-only in-process authority used by the executable example.
 *
 * The store and all peppers disappear when the process exits. This is deliberately unsuitable for
 * production, clustered deployments, migration testing, or durable account data.
 */
internal class ExampleIdentityAuthority private constructor(
    val config: IdentityConfig,
    val runtime: IdentityRuntime,
    val store: InMemoryIdentityStore,
    private val identityService: DefaultIdentityService,
    private val resolver: IdentityContextResolver,
    val httpApi: IdentityHttpApi
) {
    suspend fun start() = identityService.start()

    fun identityMiddleware(): Middleware = IdentityMiddleware(
        resolver = resolver,
        clock = runtime.clock,
        secureRandom = runtime.secureRandom,
        restrictedEnrollmentRoutePolicy = RestrictedEnrollmentRoutePolicy { method, path ->
            RestrictedEnrollmentRoutePolicy.PASSKEY_ENROLLMENT_ONLY.allows(method, path) ||
                method == HttpMethod.GET && (
                    path == "/identity" ||
                        path == "/identity/v1/client-config" ||
                        path.startsWith("/identity-client/")
                    )
        }
    ).asMiddleware()

    companion object {
        fun create(port: Int, bootstrapSecret: String): ExampleIdentityAuthority {
            require(port in 1..65_535) { "Example port must be in 1..65535" }
            require(bootstrapSecret.length in 16..512) {
                "$BOOTSTRAP_SECRET_ENV must contain 16..512 characters"
            }

            val environment = IdentityEnvironment.DEVELOPMENT
            fun reference(name: String) = SecretReference(
                provider = "example-ephemeral-memory",
                name = name,
                version = "process-v1",
                environment = environment
            )

            val sessionPepper = reference("session-pepper")
            val recoveryPepper = reference("recovery-pepper")
            val devicePepper = reference("device-token-pepper")
            val servicePepper = reference("service-credential-pepper")
            val auditKey = reference("audit-pseudonymization-key")
            val encryptionKey = reference("encryption-key")
            val signingKey = reference("signing-key")
            val bootstrapReference = reference("bootstrap-secret")
            val random = JvmIdentitySecureRandom()
            val ephemeralSecrets = listOf(
                sessionPepper,
                recoveryPepper,
                devicePepper,
                servicePepper,
                auditKey,
                encryptionKey,
                signingKey
            ).associateWith { IdentitySecret.fromBytes(random.nextBytes(32)) } +
                (bootstrapReference to IdentitySecret.fromUtf8(bootstrapSecret))
            val runtime = jvmIdentityRuntime(
                secrets = IdentitySecretResolver { reference ->
                    ephemeralSecrets[reference] ?: error("Unknown example identity secret reference")
                }
            )
            val publicBaseUrl = "http://localhost:$port"
            val config = IdentityConfig(
                environment = environment,
                publicBaseUrl = publicBaseUrl,
                relyingParty = RelyingPartyConfig(
                    id = "localhost",
                    name = "Aether identity example",
                    allowedOrigins = setOf(publicBaseUrl)
                ),
                keys = IdentityKeyConfig(
                    sessionPepper = sessionPepper,
                    recoveryPepper = recoveryPepper,
                    deviceTokenPepper = devicePepper,
                    serviceCredentialPepper = servicePepper,
                    auditPseudonymizationKey = auditKey,
                    encryptionKey = encryptionKey,
                    signingKey = signingKey
                ),
                storageNamespace = "aether_example_development",
                registrationPolicy = RegistrationPolicy.INVITATION_ONLY,
                bootstrapSecret = bootstrapReference
            )
            val store = InMemoryIdentityStore()
            val applicationPublish = Capability("package.publish")
            val serviceCredentialCapabilities = setOf(
                Capability.CONTENT_READ,
                Capability.CONTENT_PUBLISH,
                applicationPublish
            )
            val capabilityResolver = CapabilityResolver { context ->
                when (context.membership?.role) {
                    OrganizationRole.OWNER,
                    OrganizationRole.ADMIN,
                    OrganizationRole.PUBLISHER -> serviceCredentialCapabilities
                    OrganizationRole.VIEWER -> setOf(Capability.CONTENT_READ)
                    null -> emptySet()
                }
            }
            val allowedDeviceCapabilities = Capability.IDENTITY_MANAGEMENT + serviceCredentialCapabilities
            val webAuthn = WebAuthnService(store, runtime, config)
            val recovery = IdentityRecoveryService(store, runtime, config)
            val accounts = IdentityAccountManagementService(store, runtime)
            val organizations = IdentityOrganizationService(store, runtime, config, capabilityResolver)
            val devices = IdentityDeviceAuthorizationService(
                store,
                runtime,
                config,
                allowedCapabilities = allowedDeviceCapabilities,
                capabilityResolver = capabilityResolver
            )
            val serviceIdentities = IdentityServiceIdentityService(
                store,
                runtime,
                config,
                allowedCapabilities = serviceCredentialCapabilities,
                capabilityResolver = capabilityResolver
            )
            val bootstrap = IdentityBootstrapService(store, runtime, config)
            val identityService = DefaultIdentityService(
                config = config,
                runtime = runtime,
                store = store,
                initializeStorage = { StoreResult.Success(Unit) },
                organizationSelector = ::organizationFromExplicitRoute
            )
            val resolver = CompositeIdentityContextResolver(
                sessionResolver = IdentityContextResolver(identityService::resolve),
                config = config,
                deviceAuthenticator = IdentityBearerAuthenticator(devices::authenticateAccessToken),
                serviceAuthenticator = IdentityBearerAuthenticator(serviceIdentities::authenticate)
            )
            val httpApi = IdentityHttpApi(
                runtime = runtime,
                identityConfig = config,
                webAuthn = webAuthn,
                recovery = recovery,
                accounts = accounts,
                deviceAuthorization = devices,
                management = IdentityHttpManagementServices(
                    organizations = organizations,
                    serviceIdentities = serviceIdentities,
                    bootstrap = bootstrap
                ),
                recoveryAttemptLimiter = ExampleRecoveryAttemptLimiter()
            )
            return ExampleIdentityAuthority(config, runtime, store, identityService, resolver, httpApi)
        }
    }
}

/** Only `/identity/v1/organizations/{id}/...` selects tenant context. */
internal fun organizationFromExplicitRoute(exchange: Exchange): OrganizationId? {
    val segments = exchange.request.path.split('/').filter(String::isNotEmpty)
    if (segments.size < 4 || segments[0] != "identity" || segments[1] != "v1" ||
        segments[2] != "organizations"
    ) return null
    return OrganizationId.parseOrNull(segments[3])
}

internal const val BOOTSTRAP_SECRET_ENV: String = "AETHER_IDENTITY_BOOTSTRAP_SECRET"

/** Bounded process-local limiter for the disposable example; production uses a shared backend. */
private class ExampleRecoveryAttemptLimiter(
    private val maximumAttempts: Int = 5,
    private val windowSeconds: Long = 60,
    private val maximumKeys: Int = 10_000
) : IdentityRecoveryAttemptLimiter {
    private data class Window(val startedAtEpochSeconds: Long, val attempts: Int)

    private val windows = ConcurrentHashMap<IdentityRecoveryAttemptKey, Window>()

    override suspend fun allow(attempt: IdentityRecoveryAttempt): Boolean {
        val now = attempt.attemptedAt.epochSeconds
        if (windows.size >= maximumKeys && !windows.containsKey(attempt.key)) {
            windows.entries.removeIf { (_, window) -> now - window.startedAtEpochSeconds >= windowSeconds }
            if (windows.size >= maximumKeys) return false
        }
        var allowed = false
        windows.compute(attempt.key) { _, existing ->
            val current = existing?.takeIf { now - it.startedAtEpochSeconds < windowSeconds }
            val replacement = if (current == null) Window(now, 1) else current.copy(attempts = current.attempts + 1)
            allowed = replacement.attempts <= maximumAttempts
            replacement
        }
        return allowed
    }
}
