package codes.yousef.aether.auth

import java.net.http.HttpClient
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmIdentityRuntimeTest {
    @Test
    fun jcaProviderSelfTestRequiresEveryIdentityPrimitive() = runTest {
        assertTrue(JvmIdentityCrypto().providerSelfTest())
    }

    private val crypto = JvmIdentityCrypto()

    @Test
    fun `sha256 and hmac match known answer vectors`() = runTest {
        assertContentEquals(
            hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            crypto.sha256("abc".encodeToByteArray())
        )
        assertContentEquals(
            hex("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"),
            crypto.hmacSha256(
                IdentitySecret.fromUtf8("key"),
                "The quick brown fox jumps over the lazy dog".encodeToByteArray()
            )
        )
    }

    @Test
    fun `es256 verifies canonical raw signatures`() = runTest {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val pair = generator.generateKeyPair()
        val message = "aether-webauthn-vector".encodeToByteArray()
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }
        val publicKey = pair.public as ECPublicKey
        val encodedPoint = byteArrayOf(0x04) +
            fixedWidth(publicKey.w.affineX.toByteArray()) +
            fixedWidth(publicKey.w.affineY.toByteArray())
        val rawSignature = derEs256ToRaw(der)

        assertTrue(
            crypto.verifyEs256(P256PublicKey(encodedPoint), message, Es256Signature(rawSignature))
        )
        assertFalse(
            crypto.verifyEs256(P256PublicKey(encodedPoint), message + 0, Es256Signature(rawSignature))
        )
    }

    @Test
    fun `rsa sha256 rejects tampering and undersized keys`() = runTest {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
        val message = "aether-federation-vector".encodeToByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }

        assertTrue(
            crypto.verifyRsaSha256(
                RsaPublicKey(pair.public.encoded),
                message,
                RsaSha256Signature(signature)
            )
        )
        assertFalse(
            crypto.verifyRsaSha256(
                RsaPublicKey(pair.public.encoded),
                message + 0,
                RsaSha256Signature(signature)
            )
        )

        val weakPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1_024) }.generateKeyPair()
        val weakSignature = Signature.getInstance("SHA256withRSA").run {
            initSign(weakPair.private)
            update(message)
            sign()
        }
        // The signature wrapper itself rejects sub-2048-bit material before verification.
        assertFailsWith<IllegalArgumentException> { RsaSha256Signature(weakSignature) }
    }

    @Test
    fun `production runtime passes mandatory startup known-answer tests`() = runTest {
        val references = productionReferences(includeBootstrap = true)
        val runtime = jvmIdentityRuntime(
            secrets = productionSecrets(references),
            http = JvmIdentityHttpClient()
        )
        val config = productionConfig(references, bootstrapSecret = references.getValue("bootstrap"))

        runtime.requireReady(config)
    }

    @Test
    fun `production runtime rejects an HTTP client without a readiness capability`() = runTest {
        val references = productionReferences(includeBootstrap = true)
        val runtime = jvmIdentityRuntime(
            secrets = productionSecrets(references),
            http = IdentityHttpClient { error("must not be called by the local readiness probe") }
        )

        assertFailsWith<IdentityRuntimeUnavailableException> {
            runtime.requireReady(
                productionConfig(references, bootstrapSecret = references.getValue("bootstrap"))
            )
        }
    }

    @Test
    fun `production runtime rejects a throwing HTTP readiness capability`() = runTest {
        val references = productionReferences(includeBootstrap = true)
        val runtime = jvmIdentityRuntime(
            secrets = productionSecrets(references),
            http = object : IdentityHttpClient {
                override suspend fun providerSelfTest(): Boolean = error("provider unavailable")

                override suspend fun execute(request: IdentityHttpRequest): IdentityHttpResponse =
                    error("must not be called by the local readiness probe")
            }
        )

        assertFailsWith<IdentityRuntimeUnavailableException> {
            runtime.requireReady(
                productionConfig(references, bootstrapSecret = references.getValue("bootstrap"))
            )
        }
    }

    @Test
    fun `JVM HTTP readiness rejects redirect following clients`() = runTest {
        val references = productionReferences(includeBootstrap = true)
        val runtime = jvmIdentityRuntime(
            secrets = productionSecrets(references),
            http = JvmIdentityHttpClient(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
            )
        )

        assertFailsWith<IdentityRuntimeUnavailableException> {
            runtime.requireReady(
                productionConfig(references, bootstrapSecret = references.getValue("bootstrap"))
            )
        }
    }

    @Test
    fun `retired production bootstrap does not resolve a bootstrap secret`() = runTest {
        val references = productionReferences(includeBootstrap = false)
        val resolved = mutableSetOf<String>()
        val runtime = jvmIdentityRuntime(
            secrets = productionSecrets(references, resolved),
            http = JvmIdentityHttpClient()
        )

        runtime.requireReady(
            productionConfig(
                references = references,
                bootstrapLifecycle = IdentityBootstrapLifecycle.RETIRED,
                bootstrapSecret = null
            )
        )

        assertFalse("bootstrap" in resolved)
    }

    private fun productionReferences(includeBootstrap: Boolean): Map<String, SecretReference> {
        val names = buildList {
            addAll(listOf("session", "recovery", "device", "service", "audit", "encryption", "signing"))
            if (includeBootstrap) add("bootstrap")
        }
        return names.associateWith { name ->
            SecretReference("test", name, "v1", IdentityEnvironment.PRODUCTION)
        }
    }

    private fun productionSecrets(
        references: Map<String, SecretReference>,
        resolved: MutableSet<String>? = null
    ): IdentitySecretResolver = IdentitySecretResolver { secret ->
        check(secret in references.values)
        resolved?.add(secret.name)
        IdentitySecret.fromBytes(ByteArray(32) { index -> (index + secret.name.length).toByte() })
    }

    private fun productionConfig(
        references: Map<String, SecretReference>,
        bootstrapLifecycle: IdentityBootstrapLifecycle = IdentityBootstrapLifecycle.PENDING,
        bootstrapSecret: SecretReference?
    ): IdentityConfig = IdentityConfig(
        environment = IdentityEnvironment.PRODUCTION,
        publicBaseUrl = "https://login.example.com",
        relyingParty = RelyingPartyConfig(
            id = "example.com",
            name = "Aether",
            allowedOrigins = setOf("https://login.example.com")
        ),
        keys = IdentityKeyConfig(
            sessionPepper = references.getValue("session"),
            recoveryPepper = references.getValue("recovery"),
            deviceTokenPepper = references.getValue("device"),
            serviceCredentialPepper = references.getValue("service"),
            auditPseudonymizationKey = references.getValue("audit"),
            encryptionKey = references.getValue("encryption"),
            signingKey = references.getValue("signing")
        ),
        bootstrapLifecycle = bootstrapLifecycle,
        bootstrapSecret = bootstrapSecret
    )

    private fun fixedWidth(value: ByteArray): ByteArray = when {
        value.size == 32 -> value
        value.size == 33 && value[0] == 0.toByte() -> value.copyOfRange(1, 33)
        value.size < 32 -> ByteArray(32 - value.size) + value
        else -> error("Unexpected P-256 coordinate")
    }

    private fun derEs256ToRaw(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte())
        var offset = 2
        require(der[offset++] == 0x02.toByte())
        val rLength = der[offset++].toInt() and 0xff
        val r = der.copyOfRange(offset, offset + rLength)
        offset += rLength
        require(der[offset++] == 0x02.toByte())
        val sLength = der[offset++].toInt() and 0xff
        val s = der.copyOfRange(offset, offset + sLength)
        return fixedWidth(r) + fixedWidth(s)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
