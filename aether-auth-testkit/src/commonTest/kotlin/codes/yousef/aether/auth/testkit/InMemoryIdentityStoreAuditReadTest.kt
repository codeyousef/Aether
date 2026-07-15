package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryIdentityStoreAuditReadTest {
    @Test
    fun `tenant audit pagination is newest first stable and isolated`() = runTest {
        val organizationId = IdentityFixtures.organizationId("organization-audit")
        val otherOrganizationId = IdentityFixtures.organizationId("organization-other")
        val initial = listOf(
            audit("audit-3000", organizationId, 3_000),
            audit("audit-2000-b", organizationId, 2_000),
            audit("audit-2000-a", organizationId, 2_000),
            audit("audit-1000", organizationId, 1_000),
            audit("audit-other", otherOrganizationId, 4_000)
        )
        val store = InMemoryIdentityStore(InMemoryIdentityStoreSeed(auditEvents = initial))

        val first = store.listAuditEventsForOrganization(
            OrganizationAuditEventPageRequest(organizationId, limit = 2)
        ).expectAuditReadSuccess()
        assertEquals(
            listOf(
                IdentityFixtures.auditEventId("audit-3000").value,
                IdentityFixtures.auditEventId("audit-2000-b").value
            ),
            first.events.map { it.id.value }
        )
        assertEquals(first.events.last().toOrganizationAuditCursor(), first.nextCursor)

        // A newly committed event above the keyset boundary cannot shift or repeat the next page.
        store.appendAuditEvent(audit("audit-4000-new", organizationId, 4_000)).expectAuditReadSuccess()
        val second = store.listAuditEventsForOrganization(
            OrganizationAuditEventPageRequest(organizationId, cursor = first.nextCursor, limit = 2)
        ).expectAuditReadSuccess()
        assertEquals(
            listOf(
                IdentityFixtures.auditEventId("audit-2000-a").value,
                IdentityFixtures.auditEventId("audit-1000").value
            ),
            second.events.map { it.id.value }
        )
        assertNull(second.nextCursor)
        assertEquals(organizationId, second.organizationId)
    }

    @Test
    fun `retention purge is bounded strict and idempotent`() = runTest {
        val organizationId = IdentityFixtures.organizationId("organization-retention")
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                auditEvents = listOf(
                    audit("audit-oldest", organizationId, 1_000),
                    audit("audit-old", organizationId, 2_000),
                    audit("audit-cutoff", organizationId, 3_000),
                    audit("audit-new", organizationId, 4_000)
                )
            )
        )
        val command = PurgeAuditEventsCommand(
            occurredBefore = IdentityFixtures.instant(3_000),
            maximumEvents = 1
        )

        assertEquals(
            PurgeAuditEventsCommit(deletedCount = 1, hasMore = true),
            store.purgeAuditEvents(command).expectAuditReadSuccess()
        )
        assertEquals(
            PurgeAuditEventsCommit(deletedCount = 1, hasMore = false),
            store.purgeAuditEvents(command).expectAuditReadSuccess()
        )
        assertEquals(
            PurgeAuditEventsCommit(deletedCount = 0, hasMore = false),
            store.purgeAuditEvents(command).expectAuditReadSuccess()
        )
        assertEquals(
            listOf(
                IdentityFixtures.auditEventId("audit-new").value,
                IdentityFixtures.auditEventId("audit-cutoff").value
            ),
            store.snapshot().auditEvents.sortedByDescending { it.occurredAt }.map { it.id.value }
        )
    }

    private fun audit(id: String, organizationId: OrganizationId, milliseconds: Long): AuditEvent =
        IdentityFixtures.auditEvent(IdentityFixtures.auditEventId(id)).copy(
            organizationId = organizationId,
            occurredAt = IdentityFixtures.instant(milliseconds)
        )
}

private fun <T> StoreResult<T>.expectAuditReadSuccess(): T = when (this) {
    is StoreResult.Success -> value
    is StoreResult.Failure -> error("Expected success, got ${error.code}")
}
