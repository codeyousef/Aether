package codes.yousef.aether.core.auth

/**
 * Base interface for authentication strategies.
 * Unlike AuthenticationProvider which is coupled to Exchange (HTTP),
 * AuthStrategy works with raw tokens/credentials making it usable
 * for both HTTP requests and gRPC metadata.
 */
interface AuthStrategy {
    /**
     * Authenticate the given credential value.
     * Returns AuthResult indicating success or failure.
     */
    suspend fun authenticate(credential: String): AuthResult

    /**
     * Authenticate the given credential, returning NoCredentials if null.
     */
    suspend fun authenticateOrNoCredentials(credential: String?): AuthResult {
        return if (credential == null) {
            AuthResult.NoCredentials
        } else {
            authenticate(credential)
        }
    }
}

/**
 * Strategy for Bearer token authentication (JWT, OAuth tokens, etc.).
 * Validates tokens passed in Authorization: Bearer headers or gRPC metadata.
 */
class BearerTokenStrategy(
    private val validator: suspend (token: String) -> Principal?
) : AuthStrategy {

    override suspend fun authenticate(credential: String): AuthResult {
        val principal = validator(credential)
        return if (principal != null) {
            AuthResult.Success(principal)
        } else {
            AuthResult.Failure("Invalid or expired token")
        }
    }

    /**
     * Extract token from an Authorization header value.
     * Returns null if the header is not a valid Bearer token.
     */
    fun extractToken(authorizationHeader: String?): String? {
        if (authorizationHeader == null) return null
        if (!authorizationHeader.startsWith("Bearer ", ignoreCase = true)) return null
        val token = authorizationHeader.substring(7).trim()
        return token.ifEmpty { null }
    }

    /**
     * Authenticate from an Authorization header value.
     */
    suspend fun authenticateFromHeader(authorizationHeader: String?): AuthResult {
        val token = extractToken(authorizationHeader)
        return authenticateOrNoCredentials(token)
    }
}

/**
 * Strategy for API key authentication.
 * Validates API keys passed in headers or gRPC metadata.
 */
class ApiKeyStrategy(
    private val validator: suspend (apiKey: String) -> Principal?
) : AuthStrategy {

    override suspend fun authenticate(credential: String): AuthResult {
        val principal = validator(credential)
        return if (principal != null) {
            AuthResult.Success(principal)
        } else {
            AuthResult.Failure("Invalid API key")
        }
    }
}

/**
 * Strategy for basic username:password authentication.
 * Validates base64-encoded credentials.
 */
class BasicAuthStrategy(
    private val validator: suspend (username: String, password: String) -> Principal?
) : AuthStrategy {

    override suspend fun authenticate(credential: String): AuthResult {
        // credential is expected to be base64 encoded "username:password"
        val decoded = try {
            decodeBase64(credential)
        } catch (e: Exception) {
            return AuthResult.Failure("Invalid base64 encoding")
        }

        val colonIndex = decoded.indexOf(':')
        if (colonIndex < 0) {
            return AuthResult.Failure("Invalid basic auth format")
        }

        val username = decoded.substring(0, colonIndex)
        val password = decoded.substring(colonIndex + 1)

        val principal = validator(username, password)
        return if (principal != null) {
            AuthResult.Success(principal)
        } else {
            AuthResult.Failure("Invalid username or password")
        }
    }

    /**
     * Extract credentials from an Authorization header value.
     * Returns null if the header is not a valid Basic auth header.
     */
    fun extractCredentials(authorizationHeader: String?): String? {
        if (authorizationHeader == null) return null
        if (!authorizationHeader.startsWith("Basic ", ignoreCase = true)) return null
        return authorizationHeader.substring(6).trim().ifEmpty { null }
    }

    /**
     * Authenticate from an Authorization header value.
     */
    suspend fun authenticateFromHeader(authorizationHeader: String?): AuthResult {
        val credentials = extractCredentials(authorizationHeader)
        return authenticateOrNoCredentials(credentials)
    }
}

/**
 * Composite strategy that tries multiple strategies in order.
 * Useful for supporting multiple authentication methods.
 */
class CompositeAuthStrategy(
    private val strategies: List<AuthStrategy>
) : AuthStrategy {

    override suspend fun authenticate(credential: String): AuthResult {
        for (strategy in strategies) {
            val result = strategy.authenticate(credential)
            if (result is AuthResult.Success) {
                return result
            }
        }
        return AuthResult.Failure("Authentication failed")
    }

    /**
     * Try to authenticate using a Bearer token from Authorization header.
     */
    suspend fun authenticateWithHeader(authorizationHeader: String?): AuthResult {
        if (authorizationHeader == null) return AuthResult.NoCredentials

        for (strategy in strategies) {
            when (strategy) {
                is BearerTokenStrategy -> {
                    val result = strategy.authenticateFromHeader(authorizationHeader)
                    if (result is AuthResult.Success) return result
                }

                is BasicAuthStrategy -> {
                    val result = strategy.authenticateFromHeader(authorizationHeader)
                    if (result is AuthResult.Success) return result
                }
            }
        }
        return AuthResult.Failure("No matching authentication strategy")
    }

    /**
     * Try to authenticate using an API key.
     */
    suspend fun authenticateWithApiKey(apiKey: String?): AuthResult {
        if (apiKey == null) return AuthResult.NoCredentials

        for (strategy in strategies) {
            if (strategy is ApiKeyStrategy) {
                val result = strategy.authenticate(apiKey)
                if (result is AuthResult.Success) return result
            }
        }
        return AuthResult.Failure("Invalid API key")
    }
}
