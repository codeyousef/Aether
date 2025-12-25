package codes.yousef.aether.auth.crypto

actual object PasswordHasher {
    actual suspend fun hash(password: String): String {
        // TODO: Implement proper hashing for Wasm
        return "insecure:$password"
    }

    actual suspend fun verify(password: String, hash: String): Boolean {
        return hash(password) == hash
    }
}
