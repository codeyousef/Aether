package codes.yousef.aether.auth.oidc

import codes.yousef.aether.auth.JvmIdentityCrypto
import codes.yousef.aether.auth.RsaPublicKey
import codes.yousef.aether.auth.RsaSha256Signature
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class OidcRsaKeyEncodingTest {
    @Test
    fun jwkRsaComponentsProduceAUsableSubjectPublicKeyInfo() = runTest {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey
        val modulus = publicKey.modulus.toByteArray().stripUnsignedSignByte()
        val exponent = publicKey.publicExponent.toByteArray().stripUnsignedSignByte()
        val message = "oidc-rs256-spki-known-path".encodeToByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }

        assertTrue(
            JvmIdentityCrypto().verifyRsaSha256(
                RsaPublicKey(rsaSubjectPublicKeyInfo(modulus, exponent)),
                message,
                RsaSha256Signature(signature)
            )
        )
    }

    private fun ByteArray.stripUnsignedSignByte(): ByteArray =
        if (size > 1 && first() == 0.toByte()) copyOfRange(1, size) else this
}
