package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryIdentityStoreCeremonyTest {
    @Test
    fun `challenge CAS race has exactly one winner`() = runTest {
        val challenge = IdentityFixtures.challenge()
        val store = InMemoryIdentityStore(InMemoryIdentityStoreSeed(challenges = listOf(challenge)))
        val commands = listOf("a", "b").map { suffix ->
            ConsumeChallengeCommand(
                challengeId = challenge.id,
                expectedVersion = 0,
                terminalState = ChallengeState.CONSUMED,
                consumedAt = IdentityFixtures.instant(1_000),
                auditEvent = IdentityFixtures.auditEvent(AuditEventId("audit-challenge-$suffix"))
            )
        }

        val results = commands.map { command -> async { store.consumeChallenge(command) } }.awaitAll()

        assertEquals(1, results.count { it is StoreResult.Success })
        assertEquals(1, results.count {
            it is StoreResult.Failure && it.error.code == IdentityStoreErrorCode.CHALLENGE_NOT_PENDING
        })
        val snapshot = store.snapshot()
        assertEquals(ChallengeState.CONSUMED, snapshot.challenges.single().state)
        assertEquals(1, snapshot.auditEvents.size)
    }

    @Test
    fun `credential registration consumes challenge and writes audit atomically`() = runTest {
        val user = IdentityFixtures.user()
        val challenge = IdentityFixtures.challenge(purpose = ChallengePurpose.WEBAUTHN_REGISTRATION)
        val credential = IdentityFixtures.credential(CredentialId("credential-new"))
        val audit = IdentityFixtures.auditEvent(
            AuditEventId("audit-registration"),
            AuditAction.CREDENTIAL_REGISTERED
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(users = listOf(user), challenges = listOf(challenge))
        )

        val commit = store.completeCredentialRegistration(
            CompleteCredentialRegistrationCommand(
                challengeId = challenge.id,
                expectedChallengeVersion = 0,
                credential = credential,
                auditEvent = audit,
                rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(challenge.id)
            )
        ).expectSuccess().completion!!

        assertEquals(ChallengeState.CONSUMED, commit.challenge.state)
        assertEquals(credential, store.findCredential(credential.id).expectSuccess())
        assertEquals(listOf(audit), store.snapshot().auditEvents)
    }

    @Test
    fun `failed composite registration rolls back credential and terminally rejects ceremony`() = runTest {
        val user = IdentityFixtures.user()
        val challenge = IdentityFixtures.challenge(purpose = ChallengePurpose.WEBAUTHN_REGISTRATION)
        val audit = IdentityFixtures.auditEvent(
            AuditEventId("audit-duplicate"),
            AuditAction.CREDENTIAL_REGISTERED
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                challenges = listOf(challenge),
                auditEvents = listOf(audit)
            )
        )
        val credential = IdentityFixtures.credential(CredentialId("credential-new"))

        store.completeCredentialRegistration(
            CompleteCredentialRegistrationCommand(
                challengeId = challenge.id,
                expectedChallengeVersion = 0,
                credential = credential,
                auditEvent = audit,
                rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(challenge.id)
            )
        ).expectSuccess().rejection!!.also {
            assertEquals(IdentityStoreErrorCode.ALREADY_EXISTS, it.error.code)
        }

        val snapshot = store.snapshot()
        assertEquals(ChallengeState.FAILED, snapshot.challenges.single().state)
        assertEquals(emptyList(), snapshot.credentials)
        assertEquals(2, snapshot.auditEvents.size)
        assertEquals(1, snapshot.auditEvents.count { it.action == AuditAction.WEBAUTHN_CEREMONY_REJECTED })
    }

    @Test
    fun `WebAuthn credential identifier is globally unique`() = runTest {
        val user = IdentityFixtures.user()
        val existing = IdentityFixtures.credential(CredentialId("credential-existing"))
        val challenge = IdentityFixtures.challenge(purpose = ChallengePurpose.WEBAUTHN_REGISTRATION)
        val duplicate = IdentityFixtures.credential(
            id = CredentialId("credential-duplicate"),
            webAuthnId = existing.webAuthnId
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                credentials = listOf(existing),
                challenges = listOf(challenge)
            )
        )

        val rejected = store.completeCredentialRegistration(
            CompleteCredentialRegistrationCommand(
                challengeId = challenge.id,
                expectedChallengeVersion = 0,
                credential = duplicate,
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-webauthn-duplicate"),
                    AuditAction.CREDENTIAL_REGISTERED
                ),
                rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(challenge.id)
            )
        ).expectSuccess().rejection!!

        assertEquals(IdentityStoreErrorCode.UNIQUE_CONSTRAINT, rejected.error.code)
        assertEquals(ChallengeState.FAILED, store.findChallenge(challenge.id).expectSuccess()?.state)
        assertEquals(null, store.findCredential(duplicate.id).expectSuccess())
    }

    @Test
    fun `credential authentication updates counter and creates session in one commit`() = runTest {
        val user = IdentityFixtures.user()
        val credential = IdentityFixtures.credential(signCount = 0)
        val challenge = IdentityFixtures.challenge()
        val session = IdentityFixtures.session(SessionId("session-authenticated"))
        val audit = IdentityFixtures.auditEvent(
            AuditEventId("audit-authentication"),
            AuditAction.CREDENTIAL_AUTHENTICATED
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                credentials = listOf(credential),
                challenges = listOf(challenge)
            )
        )

        val commit = store.completeCredentialAuthentication(
            CompleteCredentialAuthenticationCommand(
                challengeId = challenge.id,
                expectedChallengeVersion = 0,
                credentialId = credential.id,
                expectedCredentialVersion = 0,
                newSignCount = 1,
                backupEligible = false,
                backedUp = false,
                authenticatedAt = IdentityFixtures.instant(),
                session = session,
                auditEvent = audit,
                rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(challenge.id)
            )
        ).expectSuccess().completion!!

        assertEquals(1, commit.credential.signCount)
        assertEquals(1, commit.credential.version)
        assertEquals(session, store.findSession(session.id).expectSuccess())
        assertEquals(ChallengeState.CONSUMED, store.findChallenge(challenge.id).expectSuccess()?.state)
    }

    @Test
    fun `non-increasing nonzero signature counter terminally rejects without issuing session`() = runTest {
        val user = IdentityFixtures.user()
        val credential = IdentityFixtures.credential(signCount = 5)
        val challenge = IdentityFixtures.challenge()
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                credentials = listOf(credential),
                challenges = listOf(challenge)
            )
        )

        val result = store.completeCredentialAuthentication(
            CompleteCredentialAuthenticationCommand(
                challengeId = challenge.id,
                expectedChallengeVersion = 0,
                credentialId = credential.id,
                expectedCredentialVersion = 0,
                newSignCount = 5,
                backupEligible = false,
                backedUp = false,
                authenticatedAt = IdentityFixtures.instant(),
                session = IdentityFixtures.session(SessionId("session-rejected")),
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-rejected"),
                    AuditAction.CREDENTIAL_AUTHENTICATED
                ),
                rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(challenge.id)
            )
        )

        assertEquals(
            IdentityStoreErrorCode.INVALID_TRANSITION,
            result.expectSuccess().rejection?.error?.code
        )
        assertEquals(ChallengeState.FAILED, store.findChallenge(challenge.id).expectSuccess()?.state)
        assertEquals(emptyList(), store.snapshot().sessions)
    }
}
