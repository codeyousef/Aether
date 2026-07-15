package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdentityAuditRetentionServiceTest {
    @Test
    fun `configured retention derives a strict wall-clock cutoff`() = runTest {
        val organizationId = IdentityFixtures.organizationId("retention-service-organization")
        val cutoff = IdentityFixtures.instant(-86_400_000)
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                auditEvents = listOf(
                    event("retention-expired", organizationId, cutoff.toEpochMilliseconds() - 1),
                    event("retention-cutoff", organizationId, cutoff.toEpochMilliseconds()),
                    event("retention-current", organizationId, IdentityFixtures.baseInstant.toEpochMilliseconds())
                )
            )
        )
        val deterministic = DeterministicIdentityRuntime()
        val service = IdentityAuditRetentionService(store, deterministic.runtime, config())

        val result = assertIs<StoreResult.Success<PurgeAuditEventsCommit>>(service.purgeExpired()).value

        assertEquals(PurgeAuditEventsCommit(deletedCount = 1, hasMore = false), result)
        assertEquals(
            listOf(
                IdentityFixtures.auditEventId("retention-cutoff").value,
                IdentityFixtures.auditEventId("retention-current").value
            ),
            store.snapshot().auditEvents.sortedBy { it.occurredAt }.map { it.id.value }
        )
    }

    private fun event(
        id: String,
        organizationId: OrganizationId,
        epochMilliseconds: Long
    ): AuditEvent = IdentityFixtures.auditEvent(IdentityFixtures.auditEventId(id)).copy(
        organizationId = organizationId,
        occurredAt = kotlin.time.Instant.fromEpochMilliseconds(epochMilliseconds)
    )

    private fun config(): IdentityConfig {
        fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
        return IdentityConfig(
            environment = IdentityEnvironment.TEST,
            publicBaseUrl = "http://localhost:8080",
            relyingParty = RelyingPartyConfig("localhost", "Audit retention test", setOf("http://localhost:8080")),
            keys = IdentityKeyConfig(
                sessionPepper = secret("session"),
                recoveryPepper = secret("recovery"),
                deviceTokenPepper = secret("device"),
                serviceCredentialPepper = secret("service"),
                auditPseudonymizationKey = secret("audit"),
                encryptionKey = secret("encryption"),
                signingKey = secret("signing")
            ),
            audit = IdentityAuditConfig(retention = IdentityDuration.days(1))
        )
    }
}
