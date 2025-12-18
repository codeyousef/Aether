package codes.yousef.aether.core.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import codes.yousef.aether.core.Exchange

/**
 * JWT authentication provider for JVM.
 * Validates JWT tokens and extracts principal information.
 */
class JwtAuthProvider(
    private val config: JwtConfig,
    private val realm: String = "Aether"
) : AuthenticationProvider {

    private val verifier by lazy {
        val algorithm = when {
            config.secret != null -> Algorithm.HMAC256(config.secret)
            config.publicKey != null -> {
                // For RSA/ECDSA, you would need to load the public key
                throw IllegalArgumentException("Public key authentication not yet implemented")
            }
            else -> throw IllegalArgumentException("Either secret or publicKey must be provided")
        }

        var builder = JWT.require(algorithm)
        
        config.issuer?.let { builder = builder.withIssuer(it) }
        config.audience?.let { builder = builder.withAudience(it) }
        
        if (config.leewaySeconds > 0) {
            builder = builder.acceptLeeway(config.leewaySeconds)
        }

        builder.build()
    }

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
            return AuthResult.Failure("Invalid credentials type for JWT auth")
        }

        return try {
            val decodedJwt = verifier.verify(credentials.token)
            val principal = createPrincipal(decodedJwt)
            AuthResult.Success(principal)
        } catch (e: JWTVerificationException) {
            AuthResult.Failure("Invalid or expired token: ${e.message}")
        } catch (e: Exception) {
            AuthResult.Failure("Token validation failed: ${e.message}", e)
        }
    }

    override suspend fun onAuthenticationFailure(exchange: Exchange, result: AuthResult) {
        val error = when (result) {
            is AuthResult.Failure -> result.message
            else -> "invalid_token"
        }
        exchange.response.setHeader(
            "WWW-Authenticate", 
            "Bearer realm=\"$realm\", error=\"invalid_token\", error_description=\"$error\""
        )
    }

    private fun createPrincipal(jwt: DecodedJWT): Principal {
        val id = jwt.getClaim(config.subjectClaim).asString() ?: jwt.subject ?: ""
        val name = jwt.getClaim(config.nameClaim).asString() ?: id
        
        val roles = try {
            jwt.getClaim(config.rolesClaim).asList(String::class.java)?.toSet() ?: emptySet()
        } catch (e: Exception) {
            val rolesString = jwt.getClaim(config.rolesClaim).asString()
            rolesString?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
        }

        // Extract all claims as attributes
        val attributes = jwt.claims.mapValues { (_, claim) ->
            when {
                claim.isNull -> null
                claim.asString() != null -> claim.asString()
                claim.asBoolean() != null -> claim.asBoolean().toString()
                claim.asInt() != null -> claim.asInt().toString()
                claim.asLong() != null -> claim.asLong().toString()
                claim.asDouble() != null -> claim.asDouble().toString()
                claim.asList(String::class.java) != null -> claim.asList(String::class.java).joinToString(",")
                else -> claim.toString()
            }
        }.filterValues { it != null }.mapValues { it.value!! }

        return UserPrincipal(
            id = id,
            name = name,
            roles = roles,
            _attributes = attributes
        )
    }

    companion object {
        /**
         * Create a JWT token for testing purposes.
         */
        fun createToken(
            secret: String,
            subject: String,
            name: String? = null,
            roles: List<String> = emptyList(),
            issuer: String? = null,
            audience: String? = null,
            expiresInSeconds: Long = 3600,
            additionalClaims: Map<String, Any> = emptyMap()
        ): String {
            val algorithm = Algorithm.HMAC256(secret)
            
            var builder = JWT.create()
                .withSubject(subject)
                .withExpiresAt(java.util.Date(System.currentTimeMillis() + expiresInSeconds * 1000))
                .withIssuedAt(java.util.Date())

            name?.let { builder = builder.withClaim("name", it) }
            issuer?.let { builder = builder.withIssuer(it) }
            audience?.let { builder = builder.withAudience(it) }
            
            if (roles.isNotEmpty()) {
                builder = builder.withClaim("roles", roles)
            }

            additionalClaims.forEach { (key, value) ->
                builder = when (value) {
                    is String -> builder.withClaim(key, value)
                    is Int -> builder.withClaim(key, value)
                    is Long -> builder.withClaim(key, value)
                    is Double -> builder.withClaim(key, value)
                    is Boolean -> builder.withClaim(key, value)
                    is List<*> -> builder.withClaim(key, value.map { it.toString() })
                    else -> builder.withClaim(key, value.toString())
                }
            }

            return builder.sign(algorithm)
        }
    }
}
