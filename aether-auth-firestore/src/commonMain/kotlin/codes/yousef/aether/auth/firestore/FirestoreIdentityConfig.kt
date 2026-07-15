package codes.yousef.aether.auth.firestore

import codes.yousef.aether.auth.IdentityConfig
import codes.yousef.aether.auth.IdentityEnvironment
import kotlinx.serialization.Serializable

/** Configuration for the Firestore REST adapter. No credential material is stored here. */
@Serializable
data class FirestoreIdentityConfig(
    val environment: IdentityEnvironment,
    val namespace: String,
    val projectId: String,
    val databaseId: String = "(default)",
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    val maximumRequestBytes: Int = DEFAULT_MAXIMUM_REQUEST_BYTES,
    val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
    val maximumTransactionAttempts: Int = DEFAULT_MAXIMUM_TRANSACTION_ATTEMPTS
) {
    init {
        require(PROJECT_ID.matches(projectId)) { "Invalid Firestore project ID" }
        require(DATABASE_ID.matches(databaseId)) { "Invalid Firestore database ID" }
        require(NAMESPACE.matches(namespace)) { "Invalid Firestore identity namespace" }
        require(environment.wireName in namespace) {
            "Firestore identity namespace must contain the environment name"
        }
        require(maximumRequestBytes in 1_024..MAXIMUM_WIRE_BYTES) { "Invalid maximum Firestore request size" }
        require(maximumResponseBytes in 1_024..MAXIMUM_WIRE_BYTES) { "Invalid maximum Firestore response size" }
        require(maximumTransactionAttempts in 1..10) { "Invalid maximum Firestore transaction attempts" }
        requireValidFirestoreBaseUrl(apiBaseUrl)
        if (environment in setOf(IdentityEnvironment.STAGING, IdentityEnvironment.PRODUCTION)) {
            require(apiBaseUrl.startsWith("https://")) { "Staging and production Firestore must use HTTPS" }
        }
    }

    val normalizedApiBaseUrl: String get() = apiBaseUrl.trimEnd('/')

    /** Fully qualified Firestore documents root, without a trailing slash. */
    val documentsRoot: String
        get() = "$normalizedApiBaseUrl/projects/$projectId/databases/$databaseId/documents"

    /** Namespace document below which all identity collections are stored. */
    val namespaceDocument: String
        get() = "aetherIdentity/$namespace"

    /** Database-global singleton marker. It is deliberately outside [namespaceDocument]. */
    val environmentMarkerDocument: String
        get() = "aetherIdentityEnvironment/current"

    override fun toString(): String =
        "FirestoreIdentityConfig(environment=${environment.wireName}, namespace=$namespace, " +
            "projectId=$projectId, databaseId=$databaseId, apiBaseUrl=<configured>)"

    companion object {
        const val DEFAULT_API_BASE_URL: String = "https://firestore.googleapis.com/v1"
        const val DEFAULT_MAXIMUM_REQUEST_BYTES: Int = 2 * 1_024 * 1_024
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES: Int = 8 * 1_024 * 1_024
        const val DEFAULT_MAXIMUM_TRANSACTION_ATTEMPTS: Int = 5
        private const val MAXIMUM_WIRE_BYTES: Int = 16 * 1_024 * 1_024
        private val PROJECT_ID = Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]")
        private val DATABASE_ID = Regex("\\(default\\)|[a-z][a-z0-9_-]{0,62}")
        private val NAMESPACE = Regex("[a-z][a-z0-9_-]{0,62}")

        fun fromIdentityConfig(
            identity: IdentityConfig,
            projectId: String,
            databaseId: String = "(default)",
            apiBaseUrl: String = DEFAULT_API_BASE_URL
        ): FirestoreIdentityConfig = FirestoreIdentityConfig(
            environment = identity.environment,
            namespace = identity.storageNamespace,
            projectId = projectId,
            databaseId = databaseId,
            apiBaseUrl = apiBaseUrl
        )
    }
}

private fun requireValidFirestoreBaseUrl(value: String) {
    require(value == value.trim() && value.isNotEmpty()) { "Invalid Firestore API base URL" }
    require('?' !in value && '#' !in value && '@' !in value) {
        "Firestore API base URL must not contain query, fragment, or user info"
    }
    val secure = value.startsWith("https://")
    val loopback = value.startsWith("http://localhost") ||
        value.startsWith("http://127.0.0.1") || value.startsWith("http://[::1]")
    require(secure || loopback) { "Firestore must use HTTPS except for an exact loopback emulator" }
}
