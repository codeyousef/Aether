package codes.yousef.aether.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/**
 * Guest-side ABI wiring tests. The native OpenSSL host has its own C KAT suite; the release gate
 * must additionally execute these through the generated WIT/component binding.
 */
class WasmWasiIdentityRuntimeTest {
    @Test
    fun `host backed runtime wires every mandatory startup capability`() = runTest {
        val host = ContractHost()
        val config = config()
        val runtime = wasmWasiIdentityRuntime(host, secrets(config))

        runtime.requireReady(config)

        assertEquals(2, host.randomCalls)
        assertEquals(1, host.selfTestCalls)
        assertEquals(1, host.httpSelfTestCalls)
        assertContentEquals(
            "signed:signing-key".encodeToByteArray(),
            host.sign(
                WasmWasiSigningAlgorithm.ES256,
                "signing-key",
                "payload".encodeToByteArray()
            )
        )
        assertEquals(
            204,
            runtime.http.execute(
                IdentityHttpRequest(IdentityHttpMethod.GET, "https://identity-host.invalid/ready")
            ).statusCode
        )
    }

    @Test
    fun `startup fails closed when the host self test is unavailable`() = runTest {
        val config = config()
        val host = ContractHost(selfTestResult = false)

        assertFailsWith<IdentityRuntimeUnavailableException> {
            wasmWasiIdentityRuntime(host, secrets(config)).requireReady(config)
        }
    }

    @Test
    fun `startup fails closed when the host HTTP capability is unavailable`() = runTest {
        val config = config()
        val host = ContractHost(httpSelfTestResult = false)

        assertFailsWith<IdentityRuntimeUnavailableException> {
            wasmWasiIdentityRuntime(host, secrets(config)).requireReady(config)
        }
        assertEquals(1, host.httpSelfTestCalls)
    }

    private fun config(): IdentityConfig {
        fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.STAGING)
        return IdentityConfig(
            environment = IdentityEnvironment.STAGING,
            publicBaseUrl = "https://identity.example.test",
            relyingParty = RelyingPartyConfig(
                id = "identity.example.test",
                name = "Aether WASI test",
                allowedOrigins = setOf("https://identity.example.test")
            ),
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

    private fun secrets(config: IdentityConfig): IdentitySecretResolver {
        val references = setOf(
            config.keys.sessionPepper,
            config.keys.recoveryPepper,
            config.keys.deviceTokenPepper,
            config.keys.serviceCredentialPepper,
            config.keys.auditPseudonymizationKey,
            config.keys.encryptionKey,
            config.keys.signingKey
        )
        return IdentitySecretResolver { reference ->
            require(reference in references)
            IdentitySecret.fromBytes(ByteArray(32) { 0x5a })
        }
    }

    private class ContractHost(
        private val selfTestResult: Boolean = true,
        private val httpSelfTestResult: Boolean = true
    ) : WasmWasiIdentityHost {
        var randomCalls: Int = 0
        var selfTestCalls: Int = 0
        var httpSelfTestCalls: Int = 0

        override fun nowEpochMilliseconds(): Long = Instant.parse("2026-01-01T00:00:00Z").toEpochMilliseconds()

        override fun secureRandom(size: Int): ByteArray {
            randomCalls += 1
            return ByteArray(size) { index -> (index + randomCalls).toByte() }
        }

        override suspend fun sha256(input: ByteArray): ByteArray {
            require(input.contentEquals("abc".encodeToByteArray()))
            return hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        }

        override suspend fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
            require(key.contentEquals("key".encodeToByteArray()))
            require(input.contentEquals("The quick brown fox jumps over the lazy dog".encodeToByteArray()))
            return hex("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")
        }

        override suspend fun validateP256PublicKey(publicKey: ByteArray): Boolean =
            publicKey.size == 65 && publicKey.drop(1).any { it != 0.toByte() }

        override suspend fun verifyEs256(
            publicKey: ByteArray,
            signedData: ByteArray,
            signature: ByteArray
        ): Boolean = publicKey.size == 65 && signature.size == 64 &&
            signedData.contentEquals(SELF_TEST_MESSAGE.encodeToByteArray())

        override suspend fun verifyRsaSha256(
            publicKeyDer: ByteArray,
            signedData: ByteArray,
            signature: ByteArray
        ): Boolean = publicKeyDer.isNotEmpty() && signature.size >= 256 &&
            signedData.contentEquals(SELF_TEST_MESSAGE.encodeToByteArray())

        override suspend fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
            var difference = left.size xor right.size
            val maximum = maxOf(left.size, right.size)
            repeat(maximum) { index ->
                difference = difference or
                    ((left.getOrNull(index)?.toInt() ?: 0) xor (right.getOrNull(index)?.toInt() ?: 0))
            }
            return difference == 0
        }

        override suspend fun sign(
            algorithm: WasmWasiSigningAlgorithm,
            keyHandle: String,
            signedData: ByteArray
        ): ByteArray {
            require(algorithm == WasmWasiSigningAlgorithm.ES256)
            require(signedData.contentEquals("payload".encodeToByteArray()))
            return "signed:$keyHandle".encodeToByteArray()
        }

        override suspend fun selfTest(): Boolean {
            selfTestCalls += 1
            return selfTestResult
        }

        override suspend fun httpSelfTest(): Boolean {
            httpSelfTestCalls += 1
            return httpSelfTestResult
        }

        override suspend fun executeHttp(request: IdentityHttpRequest): IdentityHttpResponse {
            require(request.url == "https://identity-host.invalid/ready")
            return IdentityHttpResponse(204)
        }

        private fun hex(value: String): ByteArray =
            value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private companion object {
        const val SELF_TEST_MESSAGE = "aether-identity-runtime-self-test-v1"
    }
}
