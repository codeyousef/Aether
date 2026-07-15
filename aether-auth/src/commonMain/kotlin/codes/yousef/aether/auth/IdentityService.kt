package codes.yousef.aether.auth

import codes.yousef.aether.core.Exchange

/** Stable entry point for identity-authority startup and request identity resolution. */
interface IdentityService {
    val config: IdentityConfig

    /** Runs hard runtime and storage isolation gates before routes accept traffic. */
    suspend fun start()

    /** Resolves cookie-backed identity without creating an anonymous session. */
    suspend fun resolve(exchange: Exchange): IdentityResolutionResult
}

class IdentityStorageUnavailableException : IllegalStateException(
    "Identity storage failed its startup isolation check"
)

/**
 * Storage-neutral service shell. Protocol services compose this entry point while adapters supply
 * their fail-closed environment-marker initializer.
 */
class DefaultIdentityService(
    override val config: IdentityConfig,
    private val runtime: IdentityRuntime,
    store: IdentityStore,
    private val initializeStorage: suspend () -> StoreResult<Unit>,
    organizationSelector: (Exchange) -> OrganizationId? = { null }
) : IdentityService {
    private val resolver = StoreBackedIdentityContextResolver(
        store = store,
        runtime = runtime,
        config = config,
        organizationSelector = organizationSelector
    )

    override suspend fun start() {
        runtime.requireReady(config)
        when (initializeStorage()) {
            is StoreResult.Success -> Unit
            is StoreResult.Failure -> throw IdentityStorageUnavailableException()
        }
    }

    override suspend fun resolve(exchange: Exchange): IdentityResolutionResult = resolver.resolve(exchange)
}
