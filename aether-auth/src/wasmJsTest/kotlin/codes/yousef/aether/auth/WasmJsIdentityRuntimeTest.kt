package codes.yousef.aether.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasmJsIdentityRuntimeTest {
    private val crypto = WasmJsIdentityCrypto()

    @Test
    fun `webcrypto matches required digest and signature known answers`() = runTest {
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

        val message = SELF_TEST_MESSAGE.encodeToByteArray()
        val es256Key = P256PublicKey(hex(ES256_PUBLIC_KEY))
        val es256Signature = Es256Signature(hex(ES256_SIGNATURE))
        assertTrue(crypto.validateP256PublicKey(es256Key))
        assertTrue(crypto.verifyEs256(es256Key, message, es256Signature))
        assertFalse(crypto.verifyEs256(es256Key, message + 0, es256Signature))

        val rsaKey = RsaPublicKey(Base64Url.decode(RSA_PUBLIC_KEY, maximumBytes = 8_192))
        val rsaSignature = RsaSha256Signature(Base64Url.decode(RSA_SIGNATURE, maximumBytes = 1_024))
        assertTrue(crypto.verifyRsaSha256(rsaKey, message, rsaSignature))
        assertFalse(crypto.verifyRsaSha256(rsaKey, message + 0, rsaSignature))
    }

    @Test
    fun `webcrypto randomness and constant time comparison satisfy runtime bounds`() {
        val first = WasmJsIdentitySecureRandom.nextBytes(32)
        val second = WasmJsIdentitySecureRandom.nextBytes(32)
        assertTrue(first.any { it != 0.toByte() })
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `webcrypto randomness chunks requests above the platform call limit`() {
        val boundary = WasmJsIdentitySecureRandom.nextBytes(65_537)
        val maximum = WasmJsIdentitySecureRandom.nextBytes(1_048_576)
        assertTrue(boundary.size == 65_537 && boundary.any { it != 0.toByte() })
        assertTrue(maximum.size == 1_048_576 && maximum.any { it != 0.toByte() })
    }

    @Test
    fun `webcrypto provider availability self test is implemented`() = runTest {
        assertTrue(crypto.providerSelfTest())
    }

    @Test
    fun `fetch capability is available without making a request`() = runTest {
        assertTrue(WasmJsIdentityHttpClient().providerSelfTest())
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val SELF_TEST_MESSAGE = "aether-identity-runtime-self-test-v1"
        const val ES256_PUBLIC_KEY =
            "04c7caca1a6acba85b090691115ced579263f1323d512bb4e8c44a123161a648e" +
                "049c127e44484d16c059ae4cbe1c22ba2bcfc95282c71da868158480b4f1a48ff"
        const val ES256_SIGNATURE =
            "8ee04ae681d1d26a64aa25c73b2bccd0c20c84387eb0d9489d4b004a5ead3090" +
                "01d25632ea0f8bbdd59fef455c9545a83e97de6ebfa2320d41d2c98d3504f2f9"
        const val RSA_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAv2CcA62AR5WAPTMw4sbvlMqqrLSg49dSQ6PGGweOjYgzqyBWIXs-swmgp4UZFBdKwAXxaEa0yE1mHGwwol1ybrKSY67Nm5kM-skV4JMZQ4m15DNBfAKsByAAHkYGrhovjYx3HFzDzM4aCHSqX_toqvOTrfYzyuq0wmiMOb_I25_913x6pzyS25_TjCa24Zu4BmjeOAxhW8ZTEn0GvvxrbrwDh8XQOctVQHtIXonACz9VoIVRWNJDqKxvGEe9UKtF5dag69Q-hJk3x6hakHStyExHyqBC4gxoN8bXZvi06-vxyY-a90W8kr9_0HFb7Q4WZmfgQb-9miwjtoOrhe62gQIDAQAB"
        const val RSA_SIGNATURE =
            "R6UzsrhWaH2mXBaZ3vIqB0MKeOnoTEWbB_bT2iDPLOll5SQF7peDAnEH7Z1rkAQiWQ5Svuf5leEc_LByky7qRo-NGdDl8FenwNqIBnJnSi5M5-2qKMKl3Zy6GXuR1DKyTqNl4m9LrE6ZQ290LB03gpon2VulS-oI-lSNHTbqHFRZO0g4lOKMi9IOakeQE2a_-2_2Jwhuo8-rMC0V-Wjkvw8NOaNfJzVq2fAoVNMRLl7ytAP2f9uKzkvvgQPkd1J_ZGmFOF3UzHV7ULTFsDw-f28jCHopxv2x3hOIG78kwkoH560eJ2mNibJDnCuvLnPsPOFLkelHTkqMHppaxIxuCw"
    }
}
