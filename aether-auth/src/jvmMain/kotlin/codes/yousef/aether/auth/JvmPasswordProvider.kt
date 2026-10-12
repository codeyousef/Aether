package codes.yousef.aether.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.concurrent.Semaphore

class PasswordProviderOverloadedException : IllegalStateException("Password provider is busy")

/** JCA PBKDF2-HMAC-SHA256, 600,000 iterations, 128-bit random salt, 256-bit result. */
class JvmPasswordProvider : IdentityPasswordProvider {
    override suspend fun hash(password: String): PasswordVerifier = work {
        require(validIdentityPassword(password, enrollment = true)) { "Password does not meet input policy" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive(password, salt)
        try { PasswordVerifier("pbkdf2-sha256-v1", Base64Url.encode(salt), Base64Url.encode(hash), 600_000) }
        finally { salt.fill(0); hash.fill(0) }
    }

    override suspend fun verify(password: String, verifier: PasswordVerifier): Boolean = work {
        require(validIdentityPassword(password, enrollment = false)) { "Password does not meet input policy" }
        val salt = Base64Url.decode(verifier.salt, 16)
        val expected = Base64Url.decode(verifier.hash, 32)
        val actual = derive(password, salt)
        try { MessageDigest.isEqual(expected, actual) }
        finally { salt.fill(0); expected.fill(0); actual.fill(0) }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, 600_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { chars.fill('\u0000'); spec.clearPassword() }
    }

    private suspend fun <T> work(block: () -> T): T {
        if (!permits.tryAcquire()) throw PasswordProviderOverloadedException()
        return try { withContext(Dispatchers.IO) { block() } } finally { permits.release() }
    }
    companion object { private val permits = Semaphore(2) }
}
