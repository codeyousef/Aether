package codes.yousef.aether.auth.crypto

expect object PasswordHasher {
    suspend fun hash(password: String): String
    suspend fun verify(password: String, hash: String): Boolean
}
