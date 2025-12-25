package codes.yousef.aether.auth.crypto

import java.security.MessageDigest
import java.util.Base64

actual object PasswordHasher {
    actual suspend fun hash(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hash)
    }

    actual suspend fun verify(password: String, hash: String): Boolean {
        return hash(password) == hash
    }
}
