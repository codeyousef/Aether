package codes.yousef.aether.auth

import codes.yousef.aether.auth.crypto.PasswordHasher
import codes.yousef.aether.db.eq

/**
 * Authenticate a user and return a JWT token.
 *
 * @param username The username to authenticate
 * @param password The password to authenticate
 * @param userModel The user model to query
 * @param secret The secret key for signing the token
 * @param issuer The issuer claim (optional)
 * @param expirationMillis Token expiration in milliseconds (default 1 hour)
 * @return The JWT token if authentication succeeds, null otherwise.
 */
suspend fun loginJwt(
    username: String,
    password: String,
    userModel: UserModel<*>,
    secret: String,
    issuer: String? = null,
    expirationMillis: Long = 3600_000
): String? {
    // Note: We access the username property of the model.
    // Assuming userModel.username maps to the username column.
    val user = userModel.objects.filter(
        userModel.username eq username
    ).firstOrNull()

    if (user != null) {
        val hashedPassword = user.password
        if (hashedPassword != null && PasswordHasher.verify(password, hashedPassword)) {
            val userId = user.id.toString()
            return JwtService.generateToken(
                subject = userId,
                secret = secret,
                issuer = issuer,
                expirationMillis = expirationMillis
            )
        }
    }
    return null
}

