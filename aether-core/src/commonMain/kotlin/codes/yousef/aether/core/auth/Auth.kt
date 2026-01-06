package codes.yousef.aether.core.auth

import kotlinx.serialization.Serializable

/**
 * Represents an authenticated user/entity.
 */
interface Principal {
    /**
     * Unique identifier for this principal.
     */
    val id: String

    /**
     * Display name of the principal.
     */
    val name: String

    /**
     * Roles assigned to this principal.
     */
    val roles: Set<String>

    /**
     * Additional attributes/claims.
     */
    val attributes: Map<String, Any?>

    /**
     * Check if the principal has a specific role.
     */
    fun hasRole(role: String): Boolean = role in roles

    /**
     * Check if the principal has any of the specified roles.
     */
    fun hasAnyRole(vararg roles: String): Boolean = roles.any { it in this.roles }

    /**
     * Check if the principal has all of the specified roles.
     */
    fun hasAllRoles(vararg roles: String): Boolean = roles.all { it in this.roles }

    /**
     * Check if the principal has a specific permission.
     */
    fun hasPermission(permission: String): Boolean = false

    /**
     * Check if the principal has any of the specified permissions.
     */
    fun hasAnyPermission(vararg permissions: String): Boolean = permissions.any { hasPermission(it) }

    /**
     * Check if the principal has all of the specified permissions.
     */
    fun hasAllPermissions(vararg permissions: String): Boolean = permissions.all { hasPermission(it) }
}

/**
 * Default implementation of Principal.
 */
@Serializable
data class UserPrincipal(
    override val id: String,
    override val name: String,
    override val roles: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
    private val _attributes: Map<String, String> = emptyMap()
) : Principal {
    @Suppress("UNCHECKED_CAST")
    override val attributes: Map<String, Any?>
        get() = _attributes as Map<String, Any?>

    override fun hasPermission(permission: String): Boolean = permission in permissions
}

/**
 * Represents authentication credentials.
 */
sealed class Credentials {
    /**
     * Username and password credentials.
     */
    data class UsernamePassword(
        val username: String,
        val password: String
    ) : Credentials()

    /**
     * Bearer token (e.g., JWT, API key).
     */
    data class BearerToken(
        val token: String
    ) : Credentials()

    /**
     * Basic authentication header.
     */
    data class Basic(
        val username: String,
        val password: String
    ) : Credentials()

    /**
     * API key authentication.
     */
    data class ApiKey(
        val key: String,
        val source: ApiKeySource
    ) : Credentials()
}

/**
 * Source of API key.
 */
enum class ApiKeySource {
    HEADER,
    QUERY_PARAM,
    COOKIE
}

/**
 * Result of an authentication attempt.
 */
sealed class AuthResult {
    /**
     * Authentication successful.
     */
    data class Success(val principal: Principal) : AuthResult()

    /**
     * Authentication failed.
     */
    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : AuthResult()

    /**
     * No credentials provided (unauthenticated).
     */
    object NoCredentials : AuthResult()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun principalOrNull(): Principal? = (this as? Success)?.principal
}

/**
 * Configuration for authentication.
 */
data class AuthConfig(
    /**
     * Realm name for HTTP authentication challenges.
     */
    val realm: String = "Aether",

    /**
     * Whether authentication is required (403) or optional (just don't set principal).
     */
    val required: Boolean = true,

    /**
     * Paths to exclude from authentication.
     */
    val excludedPaths: Set<String> = emptySet(),

    /**
     * Path prefixes to exclude from authentication.
     */
    val excludedPathPrefixes: Set<String> = emptySet(),

    /**
     * Custom error message for unauthenticated requests.
     */
    val unauthenticatedMessage: String = "Authentication required",

    /**
     * Custom error message for unauthorized requests.
     */
    val unauthorizedMessage: String = "Access denied"
)

/**
 * Configuration for JWT authentication.
 */
data class JwtConfig(
    /**
     * Secret key for HMAC algorithms (HS256, HS384, HS512).
     */
    val secret: String? = null,

    /**
     * Public key for RSA/ECDSA algorithms (RS256, ES256, etc.).
     */
    val publicKey: String? = null,

    /**
     * Expected issuer claim.
     */
    val issuer: String? = null,

    /**
     * Expected audience claim.
     */
    val audience: String? = null,

    /**
     * Leeway for expiration checking in seconds.
     */
    val leewaySeconds: Long = 0,

    /**
     * Claim name containing the user ID.
     */
    val subjectClaim: String = "sub",

    /**
     * Claim name containing the user's name.
     */
    val nameClaim: String = "name",

    /**
     * Claim name containing roles.
     */
    val rolesClaim: String = "roles"
)

/**
 * Configuration for API key authentication.
 */
data class ApiKeyConfig(
    /**
     * Header name for API key.
     */
    val headerName: String = "X-API-Key",

    /**
     * Query parameter name for API key.
     */
    val queryParamName: String = "api_key",

    /**
     * Cookie name for API key.
     */
    val cookieName: String = "api_key",

    /**
     * Sources to check for API key.
     */
    val sources: Set<ApiKeySource> = setOf(ApiKeySource.HEADER)
)
