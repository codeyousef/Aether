package codes.yousef.aether.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

class IdentityCryptoSelfTestDefaultTest {
    @Test
    fun `crypto providers fail readiness closed unless they implement a self test`() = runTest {
        val crypto = object : IdentityCrypto {
            override val capabilities: Set<IdentityCryptoCapability> = emptySet()
            override suspend fun sha256(input: ByteArray): ByteArray = error("not used")
            override suspend fun hmacSha256(key: IdentitySecret, input: ByteArray): ByteArray = error("not used")
            override suspend fun validateP256PublicKey(publicKey: P256PublicKey): Boolean = error("not used")
            override suspend fun verifyEs256(
                publicKey: P256PublicKey,
                signedData: ByteArray,
                signature: Es256Signature
            ): Boolean = error("not used")
            override suspend fun verifyRsaSha256(
                publicKey: RsaPublicKey,
                signedData: ByteArray,
                signature: RsaSha256Signature
            ): Boolean = error("not used")
            override suspend fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean = error("not used")
        }

        assertFalse(crypto.providerSelfTest())
    }
}
