package codes.yousef.aether.auth

import kotlin.time.Duration.Companion.seconds

/**
 * Applies the configured audit privacy policy before request metadata reaches persistence.
 * Pseudonyms are keyed and domain separated so values cannot be correlated across attribute types.
 */
class IdentityAuditRedactor(
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig
) {
    suspend fun userAgent(value: String?): PseudonymousValue? = when (config.audit.userAgentPolicy) {
        AuditUserAgentPolicy.OMIT -> null
        AuditUserAgentPolicy.PSEUDONYMIZE -> value
            ?.take(MAXIMUM_USER_AGENT_CHARACTERS)
            ?.let { pseudonymize("aether-audit-user-agent-v1", it) }
    }

    suspend fun clientAddress(value: String?): PseudonymousValue? =
        value?.let { pseudonymize("aether-audit-client-v1", it) }

    private suspend fun pseudonymize(domain: String, value: String): PseudonymousValue {
        val material = "$domain\u0000$value".encodeToByteArray()
        return try {
            val digest = runtime.crypto.hmacSha256(
                runtime.secrets.resolve(config.keys.auditPseudonymizationKey),
                material
            )
            try {
                require(digest.size == 32) { "Invalid audit pseudonym digest" }
                PseudonymousValue("v1.${Base64Url.encode(digest)}")
            } finally {
                digest.fill(0)
            }
        } finally {
            material.fill(0)
        }
    }

    private companion object {
        const val MAXIMUM_USER_AGENT_CHARACTERS: Int = 2_048
    }
}

/** Host-scheduled retention worker. Calls are bounded and safe to repeat until [hasMore] is false. */
class IdentityAuditRetentionService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig
) {
    suspend fun purgeExpired(maximumEvents: Int = PurgeAuditEventsCommand.DEFAULT_MAXIMUM_EVENTS): StoreResult<PurgeAuditEventsCommit> =
        store.purgeAuditEvents(
            PurgeAuditEventsCommand(
                occurredBefore = runtime.clock.now() - config.audit.retention.seconds.seconds,
                maximumEvents = maximumEvents
            )
        )
}
