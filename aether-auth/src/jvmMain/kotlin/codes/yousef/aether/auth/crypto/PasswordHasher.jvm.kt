package codes.yousef.aether.auth.crypto

import at.favre.lib.crypto.bcrypt.BCrypt

actual object PasswordHasher {
    actual suspend fun hash(password: String): String {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray())
    }

    actual suspend fun verify(password: String, hash: String): Boolean {
        val result = BCrypt.verifyer().verify(password.toCharArray(), hash)
        return result.verified
    }
}
