package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdentityAdministrativeRecoveryServiceTest {
    @Test
    fun `service is disabled unless both independent integrations are configured`() = runTest {
        val fixture = AdministrativeRecoveryFixture()
        val disabled = IdentityAdministrativeRecoveryService(
            fixture.store,
            fixture.runtime.runtime,
            fixture.config,
            authorizer = null,
            notificationSink = fixture.sink
        )

        assertFalse(disabled.enabled)
        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            disabled.issueTicket(fixture.adminContext, fixture.target.id).expectAdministrativeFailure()
        )
        assertTrue(fixture.store.snapshot().challenges.isEmpty())
    }

    @Test
    fun `ticket creation delivery and redemption are audited and single use`() = runTest {
        val fixture = AdministrativeRecoveryFixture()

        val ticket = fixture.service.issueTicket(fixture.adminContext, fixture.target.id)
            .expectAdministrativeSuccess()
        val delivery = assertNotNull(fixture.sink.lastDelivery)
        val token = delivery.revealToken()
        assertEquals(ticket, delivery.ticket)
        assertEquals(32, Base64Url.decode(token.substringAfter('.')).size)
        assertFalse(delivery.toString().contains(token))

        val recovered = fixture.service.redeemTicket(token).expectAdministrativeSuccess()
        assertEquals(fixture.target.id, recovered.userId)
        assertEquals(AuthenticationAssurance.RECOVERY, recovered.issuedSession.session.assurance)
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            fixture.service.redeemTicket(token).expectAdministrativeFailure()
        )

        val snapshot = fixture.store.snapshot()
        assertEquals(ChallengeState.CONSUMED, snapshot.challenges.single().state)
        assertNotNull(snapshot.challenges.single().activatedAt)
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.RECOVERY_ADMIN_TICKET_CREATED })
        assertTrue(snapshot.auditEvents.any {
            it.action == AuditAction.RECOVERY_ADMIN_TICKET_DELIVERED && it.outcome == AuditOutcome.SUCCEEDED
        })
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.RECOVERY_ADMIN_TICKET_USED })
        assertFalse(snapshot.toString().contains(token))
    }

    @Test
    fun `delivery failure cancels the ticket and records the outcome`() = runTest {
        val fixture = AdministrativeRecoveryFixture(deliveryOutcome = RecoveryNotificationOutcome(false, "mailbox_unavailable"))

        assertEquals(
            IdentityErrorCode.SERVICE_UNAVAILABLE,
            fixture.service.issueTicket(fixture.adminContext, fixture.target.id).expectAdministrativeFailure()
        )

        val snapshot = fixture.store.snapshot()
        assertEquals(ChallengeState.FAILED, snapshot.challenges.single().state)
        assertEquals(null, snapshot.challenges.single().activatedAt)
        assertTrue(snapshot.auditEvents.any {
            it.action == AuditAction.RECOVERY_ADMIN_TICKET_DELIVERED &&
                it.outcome == AuditOutcome.FAILED && it.reasonCode == "mailbox_unavailable"
        })
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.RECOVERY_ADMIN_TICKET_CANCELLED })
    }

    @Test
    fun `ticket cannot be redeemed from inside notification delivery before durable activation`() = runTest {
        val fixture = AdministrativeRecoveryFixture()
        var deliveryRace: IdentityOperationResult<CompletedRecovery>? = null
        fixture.sink.duringDelivery = { delivery ->
            deliveryRace = fixture.service.redeemTicket(delivery.revealToken())
        }

        fixture.service.issueTicket(fixture.adminContext, fixture.target.id).expectAdministrativeSuccess()

        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            assertNotNull(deliveryRace).expectAdministrativeFailure()
        )
        val activated = fixture.store.snapshot().challenges.single()
        assertNotNull(activated.activatedAt)
        assertEquals(1L, activated.version)
        fixture.service.redeemTicket(assertNotNull(fixture.sink.lastDelivery).revealToken())
            .expectAdministrativeSuccess()
    }

    @Test
    fun `failed atomic delivery audit leaves no redeemable ticket`() = runTest {
        val fixture = AdministrativeRecoveryFixture()
        val unavailableActivationStore = object : IdentityStore by fixture.store {
            override suspend fun activateAdministrativeRecoveryTicket(
                command: ActivateAdministrativeRecoveryTicketCommand
            ): StoreResult<AdministrativeRecoveryTicketActivationCommit> = StoreResult.Failure(
                IdentityStoreError(IdentityStoreErrorCode.UNAVAILABLE, retryable = true)
            )
        }
        val service = fixture.serviceWith(unavailableActivationStore)

        assertEquals(
            IdentityErrorCode.SERVICE_UNAVAILABLE,
            service.issueTicket(fixture.adminContext, fixture.target.id).expectAdministrativeFailure()
        )

        val token = assertNotNull(fixture.sink.lastDelivery).revealToken()
        val snapshot = fixture.store.snapshot()
        assertEquals(ChallengeState.FAILED, snapshot.challenges.single().state)
        assertEquals(null, snapshot.challenges.single().activatedAt)
        assertFalse(snapshot.auditEvents.any {
            it.action == AuditAction.RECOVERY_ADMIN_TICKET_DELIVERED && it.outcome == AuditOutcome.SUCCEEDED
        })
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            service.redeemTicket(token).expectAdministrativeFailure()
        )
    }

    @Test
    fun `expired tickets transition once and cannot create a session`() = runTest {
        val fixture = AdministrativeRecoveryFixture()
        fixture.service.issueTicket(fixture.adminContext, fixture.target.id).expectAdministrativeSuccess()
        val token = assertNotNull(fixture.sink.lastDelivery).revealToken()
        fixture.runtime.deterministicClock.advanceMilliseconds(15 * 60 * 1_000L)

        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            fixture.service.redeemTicket(token).expectAdministrativeFailure()
        )

        val snapshot = fixture.store.snapshot()
        assertEquals(ChallengeState.EXPIRED, snapshot.challenges.single().state)
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.RECOVERY_ADMIN_TICKET_EXPIRED })
        assertTrue(snapshot.sessions.none { it.userId == fixture.target.id })
    }

    @Test
    fun `organization ownership alone never authorizes account recovery`() = runTest {
        val fixture = AdministrativeRecoveryFixture(authorizerAllows = false)

        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            fixture.service.issueTicket(fixture.adminContext, fixture.target.id).expectAdministrativeFailure()
        )
        assertTrue(fixture.store.snapshot().challenges.isEmpty())
    }
}

private class CapturingRecoverySink(
    private val outcome: RecoveryNotificationOutcome
) : AdministrativeRecoveryNotificationSink {
    var lastDelivery: AdministrativeRecoveryTicketDelivery? = null
        private set
    var duringDelivery: suspend (AdministrativeRecoveryTicketDelivery) -> Unit = {}

    override suspend fun deliver(delivery: AdministrativeRecoveryTicketDelivery): RecoveryNotificationOutcome {
        lastDelivery = delivery
        duringDelivery(delivery)
        return outcome
    }
}

private class AdministrativeRecoveryFixture(
    deliveryOutcome: RecoveryNotificationOutcome = RecoveryNotificationOutcome(true),
    private val authorizerAllows: Boolean = true
) {
    val admin = IdentityFixtures.user()
    val target = IdentityFixtures.user(UserId("recovery-target"))
    private val session = IdentityFixtures.session()
    val config = administrativeRecoveryConfig()
    val runtime = DeterministicIdentityRuntime(
        deterministicSecrets = DeterministicIdentitySecretResolver(
            mapOf(
                config.keys.recoveryPepper to ByteArray(32) { 0x21 },
                config.keys.sessionPepper to ByteArray(32) { 0x32 }
            )
        )
    )
    val store = InMemoryIdentityStore(
        InMemoryIdentityStoreSeed(users = listOf(admin, target), sessions = listOf(session))
    )
    val sink = CapturingRecoverySink(deliveryOutcome)
    val service = IdentityAdministrativeRecoveryService(
        store,
        runtime.runtime,
        config,
        authorizer = AdministrativeRecoveryAuthorizer { actor, user ->
            authorizerAllows && actor.principal?.userId == admin.id && user.id == target.id
        },
        notificationSink = sink
    )

    fun serviceWith(identityStore: IdentityStore): IdentityAdministrativeRecoveryService =
        IdentityAdministrativeRecoveryService(
            identityStore,
            runtime.runtime,
            config,
            authorizer = AdministrativeRecoveryAuthorizer { actor, user ->
                authorizerAllows && actor.principal?.userId == admin.id && user.id == target.id
            },
            notificationSink = sink
        )

    val adminContext = IdentityContext(
        principal = IdentityPrincipal(
            kind = IdentityPrincipalKind.USER,
            userId = admin.id,
            displayName = admin.displayName,
            assurance = AuthenticationAssurance.PASSKEY,
            authenticatedAt = session.authenticatedAt,
            sessionId = session.id
        ),
        session = session
    )
}

private fun administrativeRecoveryConfig(): IdentityConfig {
    fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
    return IdentityConfig(
        environment = IdentityEnvironment.TEST,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig("localhost", "Aether Recovery Test", setOf("http://localhost:8080")),
        keys = IdentityKeyConfig(
            sessionPepper = secret("session"),
            recoveryPepper = secret("recovery"),
            deviceTokenPepper = secret("device"),
            serviceCredentialPepper = secret("service"),
            auditPseudonymizationKey = secret("audit"),
            encryptionKey = secret("encryption"),
            signingKey = secret("signing")
        )
    )
}

private fun <T> IdentityOperationResult<T>.expectAdministrativeSuccess(): T = when (this) {
    is IdentityOperationResult.Success -> value
    is IdentityOperationResult.Failure -> error("Expected administrative recovery success, got $code")
}

private fun IdentityOperationResult<*>.expectAdministrativeFailure(): IdentityErrorCode = when (this) {
    is IdentityOperationResult.Success -> error("Expected administrative recovery failure")
    is IdentityOperationResult.Failure -> code
}
