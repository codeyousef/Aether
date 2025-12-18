package codes.yousef.aether.core.auth

import codes.yousef.aether.core.Exchange

/**
 * Interface for authentication providers.
 * Implementations validate credentials and return authenticated principals.
 */
interface AuthenticationProvider {
    /**
     * Extract credentials from the exchange.
     * Returns null if no credentials are present.
     */
    suspend fun extractCredentials(exchange: Exchange): Credentials?

    /**
     * Authenticate the given credentials.
     * Returns AuthResult indicating success or failure.
     */
    suspend fun authenticate(credentials: Credentials): AuthResult

    /**
     * Handle authentication failure (e.g., send WWW-Authenticate header).
     */
    suspend fun onAuthenticationFailure(exchange: Exchange, result: AuthResult)
}

/**
 * Basic authentication provider.
 * Extracts username/password from Authorization header.
 */
class BasicAuthProvider(
    private val realm: String = "Aether",
    private val validator: suspend (username: String, password: String) -> Principal?
) : AuthenticationProvider {

    override suspend fun extractCredentials(exchange: Exchange): Credentials? {
        val authHeader = exchange.request.headers.get("Authorization") ?: return null
        
        if (!authHeader.startsWith("Basic ", ignoreCase = true)) {
            return null
        }

        val encoded = authHeader.substring(6)
        val decoded = try {
            decodeBase64(encoded)
        } catch (e: Exception) {
            return null
        }

        val colonIndex = decoded.indexOf(':')
        if (colonIndex < 0) {
            return null
        }

        val username = decoded.substring(0, colonIndex)
        val password = decoded.substring(colonIndex + 1)
        
        return Credentials.Basic(username, password)
    }

    override suspend fun authenticate(credentials: Credentials): AuthResult {
        if (credentials !is Credentials.Basic) {
            return AuthResult.Failure("Invalid credentials type for Basic auth")
        }

        val principal = validator(credentials.username, credentials.password)
        return if (principal != null) {
            AuthResult.Success(principal)
        } else {
            AuthResult.Failure("Invalid username or password")
        }
    }

    override suspend fun onAuthenticationFailure(exchange: Exchange, result: AuthResult) {
        exchange.response.setHeader("WWW-Authenticate", "Basic realm=\"$realm\"")
    }
}

/**
 * Bearer token authentication provider.
 * Extracts tokens from Authorization header.
 */
class BearerAuthProvider(
    private val realm: String = "Aether",
    private val validator: suspend (token: String) -> Principal?
) : AuthenticationProvider {

    override suspend fun extractCredentials(exchange: Exchange): Credentials? {
        val authHeader = exchange.request.headers.get("Authorization") ?: return null
        
        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
            return null
        }

        val token = authHeader.substring(7).trim()
        if (token.isEmpty()) {
            return null
        }

        return Credentials.BearerToken(token)
    }

    override suspend fun authenticate(credentials: Credentials): AuthResult {
        if (credentials !is Credentials.BearerToken) {
            return AuthResult.Failure("Invalid credentials type for Bearer auth")
        }

        val principal = validator(credentials.token)
        return if (principal != null) {
            AuthResult.Success(principal)
        } else {
            AuthResult.Failure("Invalid or expired token")
        }
    }

    override suspend fun onAuthenticationFailure(exchange: Exchange, result: AuthResult) {
        exchange.response.setHeader("WWW-Authenticate", "Bearer realm=\"$realm\"")
    }
}

/**
 * API key authentication provider.
 * Extracts API keys from headers, query params, or cookies.
 */
class ApiKeyAuthProvider(
    private val config: ApiKeyConfig = ApiKeyConfig(),
    private val validator: suspend (apiKey: String) -> Principal?
) : AuthenticationProvider {

    override suspend fun extractCredentials(exchange: Exchange): Credentials? {
        // Try header
        if (ApiKeySource.HEADER in config.sources) {
            val headerValue = exchange.request.headers.get(config.headerName)
            if (headerValue != null) {
                return Credentials.ApiKey(headerValue, ApiKeySource.HEADER)
            }
        }

        // Try query parameter
        if (ApiKeySource.QUERY_PARAM in config.sources) {
            val queryValue = exchange.request.queryParameter(config.queryParamName)
            if (queryValue != null) {
                return Credentials.ApiKey(queryValue, ApiKeySource.QUERY_PARAM)
            }
        }

        // Try cookie
        if (ApiKeySource.COOKIE in config.sources) {
            val cookieValue = exchange.request.cookies[config.cookieName]?.value
            if (cookieValue != null) {
                return Credentials.ApiKey(cookieValue, ApiKeySource.COOKIE)
            }
        }

        return null
    }

    override suspend fun authenticate(credentials: Credentials): AuthResult {
        if (credentials !is Credentials.ApiKey) {
            return AuthResult.Failure("Invalid credentials type for API key auth")
        }

        val principal = validator(credentials.key)
        return if (principal != null) {
            AuthResult.Success(principal)
        } else {
            AuthResult.Failure("Invalid API key")
        }
    }

    override suspend fun onAuthenticationFailure(exchange: Exchange, result: AuthResult) {
        // API key auth typically doesn't send a challenge header
    }
}

/**
 * Form-based authentication provider.
 * Extracts username/password from POST form data.
 */
class FormAuthProvider(
    private val usernameField: String = "username",
    private val passwordField: String = "password",
    private val loginPath: String = "/login",
    private val validator: suspend (username: String, password: String) -> Principal?
) : AuthenticationProvider {

    override suspend fun extractCredentials(exchange: Exchange): Credentials? {
        // Only handle POST requests to login path
        if (exchange.request.path != loginPath) {
            return null
        }
        
        val contentType = exchange.request.headers.get("Content-Type") ?: return null
        if (!contentType.contains("application/x-www-form-urlencoded")) {
            return null
        }

        val body = exchange.request.bodyText()
        val params = parseFormUrlEncoded(body)
        
        val username = params[usernameField] ?: return null
        val password = params[passwordField] ?: return null
        
        return Credentials.UsernamePassword(username, password)
    }

    override suspend fun authenticate(credentials: Credentials): AuthResult {
        if (credentials !is Credentials.UsernamePassword) {
            return AuthResult.Failure("Invalid credentials type for form auth")
        }

        val principal = validator(credentials.username, credentials.password)
        return if (principal != null) {
            AuthResult.Success(principal)
        } else {
            AuthResult.Failure("Invalid username or password")
        }
    }

    override suspend fun onAuthenticationFailure(exchange: Exchange, result: AuthResult) {
        // Form auth typically redirects to login page
    }

    private fun parseFormUrlEncoded(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        
        return body.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) {
                    val name = decodeUrlComponent(part.substring(0, index))
                    val value = decodeUrlComponent(part.substring(index + 1))
                    name to value
                } else {
                    null
                }
            }
            .toMap()
    }

    private fun decodeUrlComponent(encoded: String): String {
        return encoded
            .replace('+', ' ')
            .replace(Regex("%([0-9A-Fa-f]{2})")) { match ->
                val hex = match.groupValues[1]
                hex.toInt(16).toChar().toString()
            }
    }
}

/**
 * Platform-specific Base64 decoding.
 */
expect fun decodeBase64(encoded: String): String
