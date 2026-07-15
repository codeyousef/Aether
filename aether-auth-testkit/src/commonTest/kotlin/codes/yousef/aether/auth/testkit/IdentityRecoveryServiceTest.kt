package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityRecoveryServiceTest {
    @Test
    fun `ten 128-bit codes are shown once and only keyed digests are persisted`() = runTest {
        val fixture = RecoveryFixture()

        val issued = fixture.service.replaceCodes(fixture.user.id, null).expectRecoverySuccess()

        assertEquals(0, issued.generation)
        assertEquals(10, issued.codes.size)
        val revealed = issued.codes.map { it.reveal() }
        assertEquals(10, revealed.toSet().size)
        revealed.forEach { value ->
            assertEquals(16, Base64Url.decode(value.substringAfter('.')).size)
            assertFalse(value in fixture.store.snapshot().recoveryCodes.joinToString())
        }
        val persisted = fixture.store.snapshot().recoveryCodes
        assertEquals(10, persisted.size)
        assertTrue(persisted.all { it.secretDigest.algorithm == DigestAlgorithm.HMAC_SHA256 })
        assertTrue(persisted.all { it.secretDigest.keyVersion == fixture.config.keys.recoveryPepper.version })
        assertTrue(persisted.none { stored -> revealed.any { it.contains(stored.secretDigest.encoded) } })
    }

    @Test
    fun `successful recovery is single-use and creates only a fifteen-minute restricted session`() = runTest {
        val fixture = RecoveryFixture()
        val code = fixture.service.replaceCodes(fixture.user.id, null)
            .expectRecoverySuccess().codes.first().reveal()

        val completed = fixture.service.recover(code).expectRecoverySuccess()

        val session = completed.issuedSession.session
        assertEquals(AuthenticationAssurance.RECOVERY, session.assurance)
        assertEquals(15 * 60, (session.absoluteExpiresAt - session.createdAt).inWholeSeconds)
        assertEquals(session.absoluteExpiresAt, session.idleExpiresAt)
        assertEquals(RecoveryCodeState.CONSUMED, fixture.store.snapshot().recoveryCodes.single {
            it.publicSelector == code.substringBefore('.')
        }.state)
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            (fixture.service.recover(code) as IdentityOperationResult.Failure).code
        )

        val organization = IdentityFixtures.organization()
        val membership = IdentityFixtures.membership(userId = fixture.user.id)
        val context = IdentityContext(
            principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = fixture.user.id,
                displayName = fixture.user.displayName,
                assurance = AuthenticationAssurance.RECOVERY,
                authenticatedAt = session.authenticatedAt,
                sessionId = session.id
            ),
            session = session,
            organization = organization,
            membership = membership
        )
        assertFalse(context.hasRole(OrganizationRole.OWNER))
        assertFalse(context.hasCapability(Capability.ORGANIZATION_DELETE))
    }

    @Test
    fun `concurrent recovery use has exactly one winner`() = runTest {
        val fixture = RecoveryFixture()
        val code = fixture.service.replaceCodes(fixture.user.id, null)
            .expectRecoverySuccess().codes.first().reveal()

        val results = List(2) { async { fixture.service.recover(code) } }.awaitAll()

        assertEquals(1, results.count { it is IdentityOperationResult.Success })
        assertEquals(1, results.count { it is IdentityOperationResult.Failure })
        assertEquals(1, fixture.store.snapshot().sessions.size)
    }

    @Test
    fun `wrong recovery secret is generic and does not consume the code`() = runTest {
        val fixture = RecoveryFixture()
        val code = fixture.service.replaceCodes(fixture.user.id, null)
            .expectRecoverySuccess().codes.first().reveal()
        val replacementSecret = Base64Url.encode(ByteArray(16) { 0x55 })
        assertNotEquals(code.substringAfter('.'), replacementSecret)

        val result = fixture.service.recover("${code.substringBefore('.')}.$replacementSecret")

        assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, (result as IdentityOperationResult.Failure).code)
        assertEquals(RecoveryCodeState.ACTIVE, fixture.store.snapshot().recoveryCodes.single {
            it.publicSelector == code.substringBefore('.')
        }.state)
        assertTrue(fixture.store.snapshot().sessions.isEmpty())
    }
}

private class RecoveryFixture {
    val user = IdentityFixtures.user()
    val config = recoveryConfig()
    val deterministic = DeterministicIdentityRuntime(
        deterministicSecrets = DeterministicIdentitySecretResolver(
            mapOf(
                config.keys.sessionPepper to ByteArray(32) { 0x41 },
                config.keys.recoveryPepper to ByteArray(32) { 0x42 }
            )
        )
    )
    val store = InMemoryIdentityStore(InMemoryIdentityStoreSeed(users = listOf(user)))
    val service = IdentityRecoveryService(store, deterministic.runtime, config)
}

private fun recoveryConfig(): IdentityConfig {
    fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
    return IdentityConfig(
        environment = IdentityEnvironment.TEST,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig("localhost", "Aether Test", setOf("http://localhost:8080")),
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

private fun <T> IdentityOperationResult<T>.expectRecoverySuccess(): T = when (this) {
    is IdentityOperationResult.Success -> value
    is IdentityOperationResult.Failure -> error("Expected recovery success, got $code")
}
