package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryIdentityStoreReadTest {
    @Test
    fun `all reads return seeded values and stable ordering`() = runTest {
        val user = IdentityFixtures.user()
        val credential1 = IdentityFixtures.credential()
        val credential2 = IdentityFixtures.credential(CredentialId("credential-2"))
        val session1 = IdentityFixtures.session()
        val session2 = IdentityFixtures.session(SessionId("session-2"))
        val organization = IdentityFixtures.organization()
        val membership = IdentityFixtures.membership()
        val invitation = IdentityFixtures.invitation()
        val serviceIdentity = IdentityFixtures.serviceIdentity()
        val serviceCredential = IdentityFixtures.serviceCredential()
        val externalIdentity = IdentityFixtures.externalIdentity()
        val challenge = IdentityFixtures.challenge()
        val recoveryCode = IdentityFixtures.recoveryCode()
        val deviceGrant = IdentityFixtures.deviceGrant()
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                credentials = listOf(credential2, credential1),
                sessions = listOf(session2, session1),
                organizations = listOf(organization),
                memberships = listOf(membership),
                invitations = listOf(invitation),
                serviceIdentities = listOf(serviceIdentity),
                serviceCredentials = listOf(serviceCredential),
                externalIdentities = listOf(externalIdentity),
                challenges = listOf(challenge),
                recoveryCodes = listOf(recoveryCode),
                deviceGrants = listOf(deviceGrant)
            )
        )

        assertEquals(user, store.findUser(user.id).expectSuccess())
        assertEquals(user, store.findUserByEmail(user.primaryEmail!!).expectSuccess())
        assertEquals(credential1, store.findCredential(credential1.id).expectSuccess())
        assertEquals(credential1, store.findCredentialByWebAuthnId(credential1.webAuthnId).expectSuccess())
        assertEquals(listOf(credential1, credential2), store.listCredentialsForUser(user.id).expectSuccess())
        assertEquals(session1, store.findSession(session1.id).expectSuccess())
        assertEquals(listOf(session1, session2), store.listSessionsForUser(user.id).expectSuccess())
        assertEquals(organization, store.findOrganization(organization.id).expectSuccess())
        assertEquals(membership, store.findMembership(membership.id).expectSuccess())
        assertEquals(membership, store.findMembershipForUser(user.id, organization.id).expectSuccess())
        assertEquals(invitation, store.findInvitation(invitation.id).expectSuccess())
        assertEquals(serviceIdentity, store.findServiceIdentity(serviceIdentity.id).expectSuccess())
        assertEquals(serviceCredential, store.findServiceCredentialByPrefix(serviceCredential.publicPrefix).expectSuccess())
        assertEquals(
            externalIdentity,
            store.findExternalIdentity(externalIdentity.provider, externalIdentity.subject).expectSuccess()
        )
        assertEquals(challenge, store.findChallenge(challenge.id).expectSuccess())
        assertEquals(recoveryCode, store.findRecoveryCodeBySelector(recoveryCode.publicSelector).expectSuccess())
        assertEquals(deviceGrant, store.findDeviceGrant(deviceGrant.id).expectSuccess())
    }

    @Test
    fun `missing reads succeed with null rather than leaking adapter errors`() = runTest {
        val store = InMemoryIdentityStore()
        assertEquals(null, store.findUser(UserId("missing-user")).expectSuccess())
        assertEquals(null, store.findSession(SessionId("missing-session")).expectSuccess())
        assertEquals(emptyList(), store.listCredentialsForUser(UserId("missing-user")).expectSuccess())
    }
}
