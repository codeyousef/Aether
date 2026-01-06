package codes.yousef.aether.auth

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.Middleware

/**
 * JWT Middleware.
 * Extracts JWT token from Authorization header and sets the user in the exchange attributes.
 */
fun jwtMiddleware(userModel: UserModel<*>, secret: String, issuer: String? = null): Middleware = { exchange, next ->
    val authHeader = exchange.request.headers["Authorization"]
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        val token = authHeader.substring(7)
        val payload = JwtService.verifyToken(token, secret, issuer)

        if (payload != null) {
            val subject = payload.subject
            // Assuming subject is the User ID
            val userId = subject.toLongOrNull()

            if (userId != null) {
                // Fetch user from DB
                // We reuse the UserKey from AuthMiddleware
                val user = userModel.get(userId)
                if (user != null) {
                     @Suppress("UNCHECKED_CAST")
                    exchange.attributes.put(UserKey as AttributeKey<Any>, user)
                }
            }
        }
    }
    next()
}

