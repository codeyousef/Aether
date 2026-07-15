package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.IdentityEnvironment
import codes.yousef.aether.auth.IdentityCryptoCapability
import codes.yousef.aether.auth.IdentityHttpMethod
import codes.yousef.aether.auth.IdentityHttpRequest
import codes.yousef.aether.auth.IdentityHttpResponse
import codes.yousef.aether.auth.SecretReference
import codes.yousef.aether.auth.RsaPublicKey
import codes.yousef.aether.auth.RsaSha256Signature
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeterministicIdentityRuntimeTest {
    @Test
    fun `clock and random source are deterministic`() = runTest {
        val first = DeterministicIdentitySecureRandom(seed = 7)
        val second = DeterministicIdentitySecureRandom(seed = 7)
        assertContentEquals(first.nextBytes(64), second.nextBytes(64))
        assertEquals(64L, first.bytesIssued())

        val clock = DeterministicIdentityClock()
        val before = clock.now()
        assertEquals(before.toEpochMilliseconds() + 1_500, clock.advanceMilliseconds(1_500).toEpochMilliseconds())
    }

    @Test
    fun `random calls reserve disjoint ranges under concurrency`() = runTest {
        val random = DeterministicIdentitySecureRandom(seed = 99)
        val outputs = coroutineScope {
            List(32) { async { random.nextBytes(32) } }.awaitAll()
        }
        assertEquals(1_024L, random.bytesIssued())
        assertEquals(outputs.size, outputs.map { it.toList() }.toSet().size)
    }

    @Test
    fun `http client is FIFO and records requests`() = runTest {
        val client = DeterministicIdentityHttpClient(
            listOf(
                IdentityHttpResponse(200, body = "first".encodeToByteArray()),
                IdentityHttpResponse(204)
            )
        )
        val firstRequest = IdentityHttpRequest(IdentityHttpMethod.GET, "https://identity.example.test/one")
        val secondRequest = IdentityHttpRequest(IdentityHttpMethod.POST, "https://identity.example.test/two")

        assertEquals("first", client.execute(firstRequest).bodyBytes().decodeToString())
        assertEquals(204, client.execute(secondRequest).statusCode)
        assertEquals(listOf(firstRequest, secondRequest), client.recordedRequests())
        assertEquals(0, client.remainingResponses())
    }

    @Test
    fun `secret resolver copies registered key material`() = runTest {
        val reference = SecretReference("test", "session-pepper", "v1", IdentityEnvironment.TEST)
        val original = byteArrayOf(1, 2, 3)
        val resolver = DeterministicIdentitySecretResolver(mapOf(reference to original))
        original.fill(9)

        val resolved = resolver.resolve(reference)
        resolved.useBytes { assertContentEquals(byteArrayOf(1, 2, 3), it) }
    }

    @Test
    fun `deterministic crypto is stable and constant-time comparator is exact`() = runTest {
        val crypto = DeterministicIdentityCrypto()
        assertContentEquals(crypto.sha256("value".encodeToByteArray()), crypto.sha256("value".encodeToByteArray()))
        assertFalse(crypto.sha256("value".encodeToByteArray()).contentEquals(crypto.sha256("other".encodeToByteArray())))
        assertTrue(crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 0)))
        assertNotEquals(crypto.sha256(byteArrayOf(1)).toList(), crypto.sha256(byteArrayOf(2)).toList())
        assertTrue(IdentityCryptoCapability.RSA_SHA256_VERIFY in crypto.capabilities)
        assertTrue(
            crypto.verifyRsaSha256(
                RsaPublicKey(ByteArray(256) { 1 }),
                "signed".encodeToByteArray(),
                RsaSha256Signature(ByteArray(256) { 2 })
            )
        )
    }

    @Test
    fun `fixture builders construct every persisted model`() {
        val fixtures = listOf(
            IdentityFixtures.user(),
            IdentityFixtures.credential(),
            IdentityFixtures.session(),
            IdentityFixtures.organization(),
            IdentityFixtures.membership(),
            IdentityFixtures.invitation(),
            IdentityFixtures.serviceIdentity(),
            IdentityFixtures.serviceCredential(),
            IdentityFixtures.deviceGrant(),
            IdentityFixtures.challenge(),
            IdentityFixtures.recoveryCode(),
            IdentityFixtures.externalIdentity(),
            IdentityFixtures.federationProviderControl(),
            IdentityFixtures.replayReceipt(),
            IdentityFixtures.auditEvent(),
            IdentityFixtures.scimMutation()
        )
        assertEquals(16, fixtures.size)
    }
}
