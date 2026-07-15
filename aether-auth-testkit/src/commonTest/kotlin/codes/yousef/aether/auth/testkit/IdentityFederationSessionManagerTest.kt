package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdentityFederationProviderManagerTest {
    @Test
    fun `disable and re-enable retain rows while old federated sessions remain invalid`() = runTest {
        val runtime = DeterministicIdentityRuntime()
        val user = IdentityFixtures.user()
        val organization = IdentityFixtures.organization()
        val control = IdentityFixtures.federationProviderControl(organizationId = organization.id)
        val lease = IdentityFixtures.federationProviderLease(control)
        val session = IdentityFixtures.session(
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = organization.id,
            federationProviderKey = control.storageKey,
            federationProviderSessionEpoch = lease.sessionEpoch,
            externalIdentityId = IdentityFixtures.externalIdentityId(),
            userId = user.id
        )
        val externalIdentity = IdentityFixtures.externalIdentity(
            userId = user.id,
            provider = control.storageKey
        )
        val receipt = IdentityFixtures.replayReceipt(provider = control.storageKey)
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                organizations = listOf(organization),
                sessions = listOf(session),
                externalIdentities = listOf(externalIdentity),
                federationProviderControls = listOf(control),
                replayReceipts = listOf(receipt)
            )
        )
        val manager = IdentityFederationProviderManager(store, runtime.runtime)

        val disabled = assertIs<IdentityOperationResult.Success<FederationProviderControl>>(
            manager.disableProvider(
                organization.id,
                control.kind,
                control.providerId,
                control.storageKey
            )
        ).value
        assertEquals(FederationProviderState.DISABLED, disabled.state)
        assertEquals(1L, disabled.sessionEpoch)
        assertEquals(
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
            assertIs<StoreResult.Failure>(store.validateFederationProviderLease(lease)).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
            assertIs<StoreResult.Failure>(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        session.id,
                        session.version,
                        IdentityFixtures.instant(1),
                        session.idleExpiresAt
                    )
                )
            ).error.code
        )

        val disabledSnapshot = store.snapshot()
        assertEquals(SessionState.ACTIVE, disabledSnapshot.sessions.single().state)
        assertEquals(listOf(externalIdentity), disabledSnapshot.externalIdentities)
        assertEquals(listOf(receipt), disabledSnapshot.replayReceipts)

        val enabled = assertIs<IdentityOperationResult.Success<FederationProviderControl>>(
            manager.enableProvider(
                organization.id,
                control.kind,
                control.providerId,
                control.storageKey
            )
        ).value
        assertEquals(FederationProviderState.ENABLED, enabled.state)
        assertEquals(1L, enabled.sessionEpoch)
        assertEquals(2L, enabled.version)
        assertEquals(
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
            assertIs<StoreResult.Failure>(store.validateFederationProviderLease(lease)).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
            assertIs<StoreResult.Failure>(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        session.id,
                        session.version,
                        IdentityFixtures.instant(2),
                        session.idleExpiresAt
                    )
                )
            ).error.code
        )

        val currentLease = assertIs<StoreResult.Success<FederationProviderLease>>(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organization.id,
                    control.kind,
                    control.providerId,
                    control.storageKey,
                    IdentityFixtures.instant(3)
                )
            )
        ).value
        val currentSession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("current-provider-epoch"),
            userId = user.id,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = organization.id,
            federationProviderKey = control.storageKey,
            federationProviderSessionEpoch = currentLease.sessionEpoch,
            externalIdentityId = IdentityFixtures.externalIdentityId("current-provider-epoch")
        )
        assertIs<StoreResult.Success<IdentitySession>>(
            store.createSession(
                CreateSessionCommand(
                    currentSession,
                    providerSessionAudit(
                        IdentityFixtures.auditEventId("current-provider-epoch"),
                        organization.id,
                        currentSession.id
                    )
                )
            )
        )
        assertEquals(
            setOf(AuditAction.FEDERATION_PROVIDER_DISABLED, AuditAction.FEDERATION_PROVIDER_ENABLED),
            store.snapshot().auditEvents.map { it.action }.filter {
                it == AuditAction.FEDERATION_PROVIDER_DISABLED || it == AuditAction.FEDERATION_PROVIDER_ENABLED
            }.toSet()
        )
    }

    @Test
    fun `initial start racing disable leaves one disabled provider and no usable lease`() = runTest {
        val runtime = DeterministicIdentityRuntime()
        val organization = IdentityFixtures.organization()
        val control = IdentityFixtures.federationProviderControl(organizationId = organization.id)
        val store = InMemoryIdentityStore(InMemoryIdentityStoreSeed(organizations = listOf(organization)))
        val manager = IdentityFederationProviderManager(store, runtime.runtime)

        val acquire = async {
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organization.id,
                    control.kind,
                    control.providerId,
                    control.storageKey,
                    IdentityFixtures.instant()
                )
            )
        }
        val disable = async {
            manager.disableProvider(
                organization.id,
                control.kind,
                control.providerId,
                control.storageKey
            )
        }
        val acquired = acquire.await()
        assertIs<IdentityOperationResult.Success<FederationProviderControl>>(disable.await())

        assertEquals(
            FederationProviderState.DISABLED,
            store.snapshot().federationProviderControls.single().state
        )
        if (acquired is StoreResult.Success) {
            assertEquals(
                IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
                assertIs<StoreResult.Failure>(store.validateFederationProviderLease(acquired.value)).error.code
            )
        } else {
            assertEquals(
                IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
                assertIs<StoreResult.Failure>(acquired).error.code
            )
        }
        assertEquals(
            listOf(AuditAction.FEDERATION_PROVIDER_DISABLED),
            store.snapshot().auditEvents.map { it.action }
        )
    }

    private fun providerSessionAudit(
        id: AuditEventId,
        organizationId: OrganizationId,
        sessionId: SessionId
    ): AuditEvent = AuditEvent(
        id = id,
        actor = AuditActor(AuditActorType.SYSTEM),
        organizationId = organizationId,
        action = AuditAction.SESSION_CREATED,
        target = AuditTarget(AuditTargetType.SESSION, sessionId.value),
        outcome = AuditOutcome.SUCCEEDED,
        occurredAt = IdentityFixtures.instant()
    )
}
