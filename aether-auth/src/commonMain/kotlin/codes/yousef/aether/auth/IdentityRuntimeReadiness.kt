package codes.yousef.aether.auth

import kotlin.time.Instant

/** Generic startup failure which never includes provider or secret material. */
class IdentityRuntimeUnavailableException : IllegalStateException(
    "Required identity runtime capabilities failed their startup self-test"
)

/**
 * Executes mandatory known-answer and availability checks before an identity authority accepts
 * traffic. Production callers must invoke this during startup; [DefaultIdentityService.start]
 * does so automatically.
 */
suspend fun IdentityRuntime.requireReady(config: IdentityConfig) {
    fun requireReady(condition: Boolean) {
        if (!condition) throw IdentityRuntimeUnavailableException()
    }

    try {
        requireReady(crypto.providerSelfTest())
        if (config.environment == IdentityEnvironment.PRODUCTION ||
            config.environment == IdentityEnvironment.STAGING
        ) {
            requireReady(http.providerSelfTest())
        }

        val now = clock.now()
        requireReady(now >= MINIMUM_REAL_WALL_CLOCK && now <= MAXIMUM_REAL_WALL_CLOCK)

        val firstRandom = secureRandom.nextBytes(32)
        val secondRandom = secureRandom.nextBytes(32)
        try {
            requireReady(firstRandom.size == 32 && secondRandom.size == 32)
            requireReady(firstRandom.any { it != 0.toByte() })
            requireReady(!crypto.constantTimeEquals(firstRandom, secondRandom))
        } finally {
            firstRandom.fill(0)
            secondRandom.fill(0)
        }

        requireReady(
            crypto.constantTimeEquals(
                crypto.sha256("abc".encodeToByteArray()),
                hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            )
        )
        requireReady(
            crypto.constantTimeEquals(
                crypto.hmacSha256(
                    IdentitySecret.fromUtf8("key"),
                    "The quick brown fox jumps over the lazy dog".encodeToByteArray()
                ),
                hex("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")
            )
        )
        requireReady(crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        requireReady(!crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        requireReady(!crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))

        val message = SELF_TEST_MESSAGE.encodeToByteArray()
        val es256Key = P256PublicKey(hex(ES256_PUBLIC_KEY))
        val es256Signature = Es256Signature(hex(ES256_SIGNATURE))
        requireReady(crypto.validateP256PublicKey(es256Key))
        requireReady(!crypto.validateP256PublicKey(P256PublicKey(byteArrayOf(0x04) + ByteArray(64))))
        requireReady(crypto.verifyEs256(es256Key, message, es256Signature))
        requireReady(!crypto.verifyEs256(es256Key, message + 0, es256Signature))

        val rsaKey = RsaPublicKey(Base64Url.decode(RSA_PUBLIC_KEY, maximumBytes = 8_192))
        val rsaSignature = RsaSha256Signature(Base64Url.decode(RSA_SIGNATURE, maximumBytes = 1_024))
        requireReady(crypto.verifyRsaSha256(rsaKey, message, rsaSignature))
        requireReady(!crypto.verifyRsaSha256(rsaKey, message + 0, rsaSignature))

        val requiredSecrets = buildList {
            add(config.keys.sessionPepper)
            addAll(config.keys.previousSessionPeppers)
            add(config.keys.recoveryPepper)
            addAll(config.keys.previousRecoveryPeppers)
            add(config.keys.deviceTokenPepper)
            addAll(config.keys.previousDeviceTokenPeppers)
            add(config.keys.serviceCredentialPepper)
            addAll(config.keys.previousServiceCredentialPeppers)
            add(config.keys.auditPseudonymizationKey)
            add(config.keys.encryptionKey)
            add(config.keys.signingKey)
            if (config.bootstrapLifecycle == IdentityBootstrapLifecycle.PENDING) {
                config.bootstrapSecret?.let(::add)
            }
        }
        requiredSecrets.forEach { reference ->
            secrets.resolve(reference).useBytes { bytes ->
                requireReady(bytes.size >= 16)
            }
        }
    } catch (unavailable: IdentityRuntimeUnavailableException) {
        throw unavailable
    } catch (_: Throwable) {
        throw IdentityRuntimeUnavailableException()
    }
}

private fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private val MINIMUM_REAL_WALL_CLOCK = Instant.parse("2020-01-01T00:00:00Z")
private val MAXIMUM_REAL_WALL_CLOCK = Instant.parse("2200-01-01T00:00:00Z")
private const val SELF_TEST_MESSAGE = "aether-identity-runtime-self-test-v1"
private const val ES256_PUBLIC_KEY =
    "04c7caca1a6acba85b090691115ced579263f1323d512bb4e8c44a123161a648e" +
        "049c127e44484d16c059ae4cbe1c22ba2bcfc95282c71da868158480b4f1a48ff"
private const val ES256_SIGNATURE =
    "8ee04ae681d1d26a64aa25c73b2bccd0c20c84387eb0d9489d4b004a5ead3090" +
        "01d25632ea0f8bbdd59fef455c9545a83e97de6ebfa2320d41d2c98d3504f2f9"
private const val RSA_PUBLIC_KEY =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAv2CcA62AR5WAPTMw4sbvlMqqrLSg49dSQ6PGGweOjYgzqyBWIXs-swmgp4UZFBdKwAXxaEa0yE1mHGwwol1ybrKSY67Nm5kM-skV4JMZQ4m15DNBfAKsByAAHkYGrhovjYx3HFzDzM4aCHSqX_toqvOTrfYzyuq0wmiMOb_I25_913x6pzyS25_TjCa24Zu4BmjeOAxhW8ZTEn0GvvxrbrwDh8XQOctVQHtIXonACz9VoIVRWNJDqKxvGEe9UKtF5dag69Q-hJk3x6hakHStyExHyqBC4gxoN8bXZvi06-vxyY-a90W8kr9_0HFb7Q4WZmfgQb-9miwjtoOrhe62gQIDAQAB"
private const val RSA_SIGNATURE =
    "R6UzsrhWaH2mXBaZ3vIqB0MKeOnoTEWbB_bT2iDPLOll5SQF7peDAnEH7Z1rkAQiWQ5Svuf5leEc_LByky7qRo-NGdDl8FenwNqIBnJnSi5M5-2qKMKl3Zy6GXuR1DKyTqNl4m9LrE6ZQ290LB03gpon2VulS-oI-lSNHTbqHFRZO0g4lOKMi9IOakeQE2a_-2_2Jwhuo8-rMC0V-Wjkvw8NOaNfJzVq2fAoVNMRLl7ytAP2f9uKzkvvgQPkd1J_ZGmFOF3UzHV7ULTFsDw-f28jCHopxv2x3hOIG78kwkoH560eJ2mNibJDnCuvLnPsPOFLkelHTkqMHppaxIxuCw"
