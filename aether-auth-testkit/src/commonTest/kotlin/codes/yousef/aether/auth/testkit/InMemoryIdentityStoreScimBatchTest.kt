package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class InMemoryIdentityStoreScimBatchTest {
    @Test
    fun `group fanout group aggregate audits and receipt commit atomically`() = runTest {
        val organization = IdentityFixtures.organization()
        val owner = IdentityFixtures.user(UserId("owner"))
        val member = IdentityFixtures.user(UserId("member"))
        val ownerMembership = IdentityFixtures.membership(
            id = MembershipId("owner-membership"),
            userId = owner.id
        )
        val membership = IdentityFixtures.membership(
            id = MembershipId("member-membership"),
            userId = member.id,
            role = OrganizationRole.VIEWER
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(owner, member),
                organizations = listOf(organization),
                memberships = listOf(ownerMembership, membership)
            )
        )
        val desiredMembership = membership.copy(
            role = OrganizationRole.ADMIN,
            version = 1,
            updatedAt = IdentityFixtures.instant(1_000)
        )
        val command = batch(
            operationId = "group-operation",
            mutations = listOf(membershipMutation(desiredMembership, "member-change")),
            group = ScimGroup(
                id = "directory-admins",
                organizationId = organization.id,
                provider = PROVIDER,
                externalId = "admins",
                displayName = "Directory admins",
                memberUserIds = setOf(member.id),
                version = 1,
                createdAt = IdentityFixtures.instant(),
                updatedAt = IdentityFixtures.instant(1_000)
            ),
            expectedGroupVersion = 0
        )

        val first = store.applyScimBatch(command).expectSuccess()
        assertEquals(false, first.alreadyApplied)
        assertEquals(OrganizationRole.ADMIN, store.findMembership(membership.id).expectSuccess()?.role)
        assertEquals(command.group, store.findScimGroup(PROVIDER, organization.id, "directory-admins").expectSuccess())
        assertTrue(store.snapshot().auditEvents.any { it.action == AuditAction.SCIM_GROUP_CHANGED })

        val retry = store.applyScimBatch(command).expectSuccess()
        assertTrue(retry.alreadyApplied)
        assertNull(retry.auditEvent)
        assertTrue(retry.mutationCommits.all { it.alreadyApplied && it.auditEvent == null })
        assertEquals(2, store.snapshot().auditEvents.size)

        store.applyScimBatch(
            command.copy(group = requireNotNull(command.group).copy(displayName = "Changed payload"))
        ).expectFailure(IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT)
    }

    @Test
    fun `failed group fanout rolls back every child receipt mutation group and audit`() = runTest {
        val organization = IdentityFixtures.organization()
        val owner = IdentityFixtures.user(UserId("owner"))
        val firstUser = IdentityFixtures.user(UserId("first"))
        val secondUser = IdentityFixtures.user(UserId("second"))
        val ownerMembership = IdentityFixtures.membership(MembershipId("owner-membership"), userId = owner.id)
        val first = IdentityFixtures.membership(
            MembershipId("first-membership"),
            userId = firstUser.id,
            role = OrganizationRole.VIEWER
        )
        val second = IdentityFixtures.membership(
            MembershipId("second-membership"),
            userId = secondUser.id,
            role = OrganizationRole.VIEWER
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(owner, firstUser, secondUser),
                organizations = listOf(organization),
                memberships = listOf(ownerMembership, first, second)
            )
        )
        val command = batch(
            operationId = "rollback-operation",
            mutations = listOf(
                membershipMutation(first.copy(role = OrganizationRole.ADMIN, version = 1), "first-change"),
                membershipMutation(second.copy(role = OrganizationRole.ADMIN, version = 7), "second-change")
            ),
            group = ScimGroup(
                id = "rollback-group",
                organizationId = organization.id,
                provider = PROVIDER,
                displayName = "Rollback group",
                memberUserIds = setOf(firstUser.id, secondUser.id),
                version = 1,
                createdAt = IdentityFixtures.instant(),
                updatedAt = IdentityFixtures.instant(1_000)
            ),
            expectedGroupVersion = 0
        )

        store.applyScimBatch(command).expectFailure(IdentityStoreErrorCode.VERSION_CONFLICT)
        val snapshot = store.snapshot()
        assertEquals(OrganizationRole.VIEWER, snapshot.memberships.single { it.id == first.id }.role)
        assertEquals(OrganizationRole.VIEWER, snapshot.memberships.single { it.id == second.id }.role)
        assertTrue(snapshot.scimGroups.isEmpty())
        assertTrue(snapshot.auditEvents.isEmpty())
        assertTrue(snapshot.appliedScimOperationIds.isEmpty())
        assertTrue(snapshot.appliedScimBatchOperationIds.isEmpty())
    }

    @Test
    fun `deprovision revokes only tenant federation sessions and tenant device token families`() = runTest {
        val tenant = IdentityFixtures.organization(OrganizationId("tenant-one"), slug = "tenant-one")
        val otherTenant = IdentityFixtures.organization(OrganizationId("tenant-two"), slug = "tenant-two")
        val owner = IdentityFixtures.user(UserId("owner"))
        val user = IdentityFixtures.user(UserId("provisioned-user"))
        val ownerMembership = IdentityFixtures.membership(
            MembershipId("owner-membership"),
            organizationId = tenant.id,
            userId = owner.id
        )
        val tenantMembership = IdentityFixtures.membership(
            MembershipId("tenant-membership"),
            organizationId = tenant.id,
            userId = user.id,
            role = OrganizationRole.VIEWER
        )
        val otherMembership = IdentityFixtures.membership(
            MembershipId("other-membership"),
            organizationId = otherTenant.id,
            userId = user.id,
            role = OrganizationRole.ADMIN
        )
        val tenantSession = federatedSession("tenant-session", user.id, tenant.id, SessionAuthenticationMethod.OIDC)
        val otherSession = federatedSession("other-session", user.id, otherTenant.id, SessionAuthenticationMethod.SAML)
        val globalSession = IdentityFixtures.session(SessionId("global-session"), userId = user.id)
        val tenantFamily = tokenFamily("tenant-family", user.id, tenant.id, tenantMembership)
        val otherFamily = tokenFamily("other-family", user.id, otherTenant.id, otherMembership)
        val tenantAccess = accessToken("tenant-access", tenantFamily.id)
        val otherAccess = accessToken("other-access", otherFamily.id)
        val tenantRefresh = refreshToken("tenant-refresh", tenantFamily.id)
        val otherRefresh = refreshToken("other-refresh", otherFamily.id)
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(owner, user),
                organizations = listOf(tenant, otherTenant),
                memberships = listOf(ownerMembership, tenantMembership, otherMembership),
                sessions = listOf(tenantSession, otherSession, globalSession),
                deviceTokenFamilies = listOf(tenantFamily, otherFamily),
                deviceAccessTokens = listOf(tenantAccess, otherAccess),
                deviceRefreshTokens = listOf(tenantRefresh, otherRefresh)
            )
        )
        val removed = tenantMembership.copy(
            state = MembershipState.REMOVED,
            version = 1,
            updatedAt = IdentityFixtures.instant(1_000),
            removedAt = IdentityFixtures.instant(1_000)
        )
        val command = batch(
            operationId = "deprovision-operation",
            organizationId = tenant.id,
            mutations = listOf(membershipMutation(removed, "deprovision-membership")),
            revocations = listOf(ScimTenantRevocation(user.id, reasonCode = "scim_user_deprovisioned")),
            targetUserId = user.id
        )

        val commit = store.applyScimBatch(command).expectSuccess()
        val snapshot = store.snapshot()
        assertEquals(UserState.ACTIVE, snapshot.users.single { it.id == user.id }.state)
        assertEquals(MembershipState.REMOVED, snapshot.memberships.single { it.id == tenantMembership.id }.state)
        assertEquals(MembershipState.ACTIVE, snapshot.memberships.single { it.id == otherMembership.id }.state)
        assertEquals(SessionState.REVOKED, snapshot.sessions.single { it.id == tenantSession.id }.state)
        assertEquals(SessionState.ACTIVE, snapshot.sessions.single { it.id == otherSession.id }.state)
        assertEquals(SessionState.ACTIVE, snapshot.sessions.single { it.id == globalSession.id }.state)
        assertEquals(DeviceTokenFamilyState.REVOKED, snapshot.deviceTokenFamilies.single { it.id == tenantFamily.id }.state)
        assertEquals(DeviceTokenFamilyState.ACTIVE, snapshot.deviceTokenFamilies.single { it.id == otherFamily.id }.state)
        assertEquals(DeviceAccessTokenState.REVOKED, snapshot.deviceAccessTokens.single { it.id == tenantAccess.id }.state)
        assertEquals(DeviceAccessTokenState.ACTIVE, snapshot.deviceAccessTokens.single { it.id == otherAccess.id }.state)
        assertEquals(DeviceRefreshTokenState.REVOKED, snapshot.deviceRefreshTokens.single { it.id == tenantRefresh.id }.state)
        assertEquals(DeviceRefreshTokenState.ACTIVE, snapshot.deviceRefreshTokens.single { it.id == otherRefresh.id }.state)
        assertEquals(listOf(tenantSession.id), commit.revokedSessionIds)
        assertEquals(listOf(tenantFamily.id), commit.revokedDeviceTokenFamilyIds)
        assertEquals(0, snapshot.users.single { it.id == user.id }.sessionEpoch)
    }

    private fun batch(
        operationId: String,
        organizationId: OrganizationId = IdentityFixtures.organizationId(),
        mutations: List<ApplyScimMutationCommand>,
        group: ScimGroup? = null,
        expectedGroupVersion: Long? = null,
        revocations: List<ScimTenantRevocation> = emptyList(),
        targetUserId: UserId = IdentityFixtures.userId("member")
    ): ApplyScimBatchCommand = ApplyScimBatchCommand(
        operationId = IdentityFixtures.scimOperationId(operationId),
        organizationId = organizationId,
        provider = PROVIDER,
        mutations = mutations,
        group = group,
        expectedGroupVersion = expectedGroupVersion,
        revocations = revocations,
        auditEvent = AuditEvent(
            id = IdentityFixtures.auditEventId("audit-$operationId"),
            actor = AuditActor(AuditActorType.SYSTEM),
            organizationId = organizationId,
            action = if (group == null) AuditAction.SCIM_MUTATION_APPLIED else AuditAction.SCIM_GROUP_CHANGED,
            target = if (group == null) {
                AuditTarget(AuditTargetType.USER, targetUserId.value)
            } else {
                AuditTarget(AuditTargetType.SCIM_GROUP, group.id)
            },
            outcome = AuditOutcome.SUCCEEDED,
            occurredAt = IdentityFixtures.instant(1_000)
        )
    )

    private fun membershipMutation(membership: Membership, suffix: String): ApplyScimMutationCommand =
        ApplyScimMutationCommand(
            mutation = ScimMutation(
                operationId = IdentityFixtures.scimOperationId("operation-$suffix"),
                provider = PROVIDER,
                type = if (membership.state == MembershipState.REMOVED) {
                    ScimMutationType.REMOVE_MEMBERSHIP
                } else {
                    ScimMutationType.UPSERT_MEMBERSHIP
                },
                externalSubject = ExternalSubject("subject-${membership.userId.value}"),
                membership = membership,
                occurredAt = IdentityFixtures.instant(1_000)
            ),
            auditEvent = AuditEvent(
                id = IdentityFixtures.auditEventId("audit-$suffix"),
                actor = AuditActor(AuditActorType.SYSTEM),
                organizationId = membership.organizationId,
                action = AuditAction.SCIM_MUTATION_APPLIED,
                target = AuditTarget(AuditTargetType.MEMBERSHIP, membership.id.value),
                outcome = AuditOutcome.SUCCEEDED,
                occurredAt = IdentityFixtures.instant(1_000)
            )
        )

    private fun federatedSession(
        id: String,
        userId: UserId,
        organizationId: OrganizationId,
        method: SessionAuthenticationMethod
    ): IdentitySession = IdentityFixtures.session(
        id = SessionId(id),
        userId = userId,
        assurance = AuthenticationAssurance.SESSION,
        authenticationMethod = method,
        federationOrganizationId = organizationId,
        federationProviderKey = IdentityFixtures.federationProviderStorageKey(
            if (method == SessionAuthenticationMethod.OIDC) {
                FederationProviderKind.OIDC
            } else {
                FederationProviderKind.SAML
            },
            "scim-${organizationId.value}"
        ),
        externalIdentityId = ExternalIdentityId("external-$id")
    )

    private fun tokenFamily(
        id: String,
        userId: UserId,
        organizationId: OrganizationId,
        membership: Membership
    ) = DeviceTokenFamily(
        id = DeviceTokenFamilyId(id),
        deviceGrantId = DeviceGrantId("grant-$id"),
        clientId = "aether-scim-test-client",
        userId = userId,
        organizationId = organizationId,
        membershipId = membership.id,
        membershipVersion = membership.version,
        capabilities = setOf(Capability.CONTENT_READ),
        createdAt = IdentityFixtures.instant(),
        expiresAt = IdentityFixtures.instant(86_400_000)
    )

    private fun accessToken(id: String, familyId: DeviceTokenFamilyId) = DeviceAccessToken(
        id = DeviceAccessTokenId(id),
        familyId = familyId,
        publicSelector = "selector_$id",
        secretDigest = IdentityFixtures.digest("digest-$id"),
        createdAt = IdentityFixtures.instant(),
        expiresAt = IdentityFixtures.instant(900_000)
    )

    private fun refreshToken(id: String, familyId: DeviceTokenFamilyId) = DeviceRefreshToken(
        id = DeviceRefreshTokenId(id),
        familyId = familyId,
        publicSelector = "selector_$id",
        secretDigest = IdentityFixtures.digest("digest-$id"),
        rotationCounter = 0,
        createdAt = IdentityFixtures.instant(),
        expiresAt = IdentityFixtures.instant(86_400_000)
    )

    private companion object {
        const val PROVIDER = "test-directory:organization-1"
    }
}
