package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.IdentityConfig
import codes.yousef.aether.auth.IdentityEnvironment
import codes.yousef.aether.auth.IdentityHttpMethod
import codes.yousef.aether.auth.IdentityHttpRequest
import codes.yousef.aether.auth.SecretReference
import kotlinx.serialization.Serializable

/**
 * PostgreSQL adapter configuration shared by the direct JVM and PostgREST transports.
 * The schema is fixed by the shipped migrations; [namespace] separates deployment data and is
 * verified against the database environment marker before the store can be used.
 */
@Serializable
data class PostgresqlIdentityConfig(
    val environment: IdentityEnvironment,
    val namespace: String,
    val schema: String = DEFAULT_SCHEMA,
    val postgrestBaseUrl: String? = null,
    val postgrestAuthorizationSecret: SecretReference? = null,
    val maximumRequestBytes: Int = DEFAULT_MAXIMUM_REQUEST_BYTES,
    val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES
) {
    init {
        require(schema == DEFAULT_SCHEMA) { "PostgreSQL identity schema must match the shipped migrations" }
        require(NAMESPACE.matches(namespace)) { "Invalid PostgreSQL identity namespace" }
        require(environment.wireName in namespace) {
            "PostgreSQL identity namespace must contain the environment name"
        }
        require(maximumRequestBytes in 1_024..MAXIMUM_WIRE_BYTES) { "Invalid maximum PostgreSQL RPC request size" }
        require(maximumResponseBytes in 1_024..MAXIMUM_WIRE_BYTES) { "Invalid maximum PostgreSQL RPC response size" }

        postgrestBaseUrl?.let(::requireValidPostgrestBaseUrl)
        require(postgrestBaseUrl != null || postgrestAuthorizationSecret == null) {
            "PostgREST authorization cannot be configured without a PostgREST base URL"
        }
        postgrestAuthorizationSecret?.let {
            require(it.environment == environment) { "PostgREST authorization secret belongs to another environment" }
        }
        if (postgrestBaseUrl != null && environment in setOf(IdentityEnvironment.STAGING, IdentityEnvironment.PRODUCTION)) {
            require(postgrestAuthorizationSecret != null) { "Staging and production PostgREST require authorization" }
        }
    }

    val normalizedPostgrestBaseUrl: String?
        get() = postgrestBaseUrl?.trimEnd('/')

    override fun toString(): String =
        "PostgresqlIdentityConfig(environment=${environment.wireName}, namespace=$namespace, schema=$schema, " +
            "postgrestBaseUrl=${if (postgrestBaseUrl == null) "none" else "<configured>"}, " +
            "postgrestAuthorization=<redacted>)"

    companion object {
        const val DEFAULT_SCHEMA: String = "aether_identity"
        const val DEFAULT_MAXIMUM_REQUEST_BYTES: Int = 2 * 1_024 * 1_024
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES: Int = 4 * 1_024 * 1_024
        private const val MAXIMUM_WIRE_BYTES: Int = 16 * 1_024 * 1_024
        private val NAMESPACE = Regex("[a-z][a-z0-9_-]{2,63}")

        fun fromIdentityConfig(
            identity: IdentityConfig,
            postgrestBaseUrl: String? = null,
            postgrestAuthorizationSecret: SecretReference? = null
        ): PostgresqlIdentityConfig = PostgresqlIdentityConfig(
            environment = identity.environment,
            namespace = identity.storageNamespace,
            postgrestBaseUrl = postgrestBaseUrl,
            postgrestAuthorizationSecret = postgrestAuthorizationSecret
        )
    }
}

private fun requireValidPostgrestBaseUrl(value: String) {
    require(value == value.trim() && value.isNotEmpty()) { "Invalid PostgREST base URL" }
    require('?' !in value && '#' !in value && '@' !in value) { "PostgREST base URL must not contain query, fragment, or user info" }
    IdentityHttpRequest(IdentityHttpMethod.GET, value)
}
