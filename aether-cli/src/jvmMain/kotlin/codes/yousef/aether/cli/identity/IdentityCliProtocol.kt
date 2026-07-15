package codes.yousef.aether.cli.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

internal object IdentityCliProtocol {
    const val CLIENT_ID = "aether-cli"
    const val DEFAULT_SERVER = "http://127.0.0.1:8080"
    const val DEFAULT_SCOPE = "identity.profile identity.organizations"
    const val DEVICE_AUTHORIZATION_PATH = "/oauth/device_authorization"
    const val TOKEN_PATH = "/oauth/token"
    const val REVOCATION_PATH = "/oauth/revoke"
    const val DEVICE_CANCELLATION_PATH = "/identity/v1/device/cancel"
    const val WHO_AM_I_PATH = "/identity/v1/me"
    const val ORGANIZATIONS_PATH = "/identity/v1/organizations"

    private const val MAX_JSON_CHARS = 1_048_576
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    fun normalizedServer(raw: String): String {
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw CliUsageException("The identity server URL is invalid.")
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw CliUsageException("The identity server URL must not contain credentials, a query, or a fragment.")
        }
        if (uri.path != null && uri.path != "" && uri.path != "/") {
            throw CliUsageException("The identity server URL must not contain a path.")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]")
            ?: throw CliUsageException("The identity server URL must include a host.")
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (scheme != "https" && !(scheme == "http" && loopback)) {
            throw CliUsageException("HTTPS is required except for loopback development servers.")
        }
        val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
        val authority = if (uri.port == -1 || defaultPort) hostForAuthority(host) else "${hostForAuthority(host)}:${uri.port}"
        return "$scheme://$authority"
    }

    fun endpoint(server: String, path: String): String = normalizedServer(server) + path

    fun account(server: String): String = normalizedServer(server)

    fun form(fields: Map<String, String>): String = fields.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }

    fun routeId(value: String): String {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull()
            ?: throw CliUsageException("Organization ID must be a canonical UUID.")
        val canonical = parsed.toString()
        if (canonical != value.lowercase()) {
            throw CliUsageException("Organization ID must be a canonical UUID.")
        }
        return canonical
    }

    fun parseDeviceAuthorization(body: String): DeviceAuthorization {
        val objectValue = parseObject(body)
        val expiresIn = objectValue.requiredLong("expires_in", "expiresIn")
        val interval = objectValue.optionalLong("interval") ?: 5
        if (expiresIn !in 1..3_600 || interval !in 1..300) {
            throw CliOperationException("The identity server returned an invalid device authorization response.")
        }
        val result = DeviceAuthorization(
            deviceCode = objectValue.requiredString("device_code", "deviceCode", maxLength = 2_048),
            userCode = objectValue.requiredString("user_code", "userCode", maxLength = 64),
            verificationUri = objectValue.requiredString("verification_uri", "verificationUri", maxLength = 4_096),
            expiresInSeconds = expiresIn,
            intervalSeconds = interval.coerceAtLeast(5),
        )
        validateVerificationUri(result.verificationUri)
        return result
    }

    fun parseToken(body: String): TokenGrant {
        val objectValue = parseObject(body)
        val accessExpiresIn = objectValue.requiredLong("expires_in", "expiresIn")
        val refreshExpiresIn = objectValue.optionalLong("refresh_expires_in", "refreshExpiresIn")
            ?: 30L * 24 * 60 * 60
        if (accessExpiresIn !in 1..86_400 || refreshExpiresIn !in 1..(90L * 24 * 60 * 60)) {
            throw CliOperationException("The identity server returned invalid token lifetimes.")
        }
        val tokenType = objectValue.requiredString("token_type", "tokenType", maxLength = 32)
        if (!tokenType.equals("Bearer", ignoreCase = true)) {
            throw CliOperationException("The identity server returned an unsupported token type.")
        }
        val scopes = objectValue.optionalString("scope", maxLength = 4_096)
            ?.split(' ')
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()
        return TokenGrant(
            accessToken = objectValue.requiredString("access_token", "accessToken", maxLength = 8_192),
            refreshToken = objectValue.requiredString("refresh_token", "refreshToken", maxLength = 8_192),
            accessExpiresInSeconds = accessExpiresIn,
            refreshExpiresInSeconds = refreshExpiresIn,
            scopes = scopes,
        )
    }

    fun parseOAuthError(body: String): OAuthError? {
        val objectValue = runCatching { parseObject(body) }.getOrNull() ?: return null
        val code = objectValue.optionalString("error", maxLength = 128) ?: return null
        return OAuthError.entries.firstOrNull { it.wireValue == code }
    }

    fun parseWhoAmI(body: String): WhoAmI {
        val value = parseObject(body)
        return WhoAmI(
            userId = value.requiredString("userId", "user_id", maxLength = 128),
            displayName = value.optionalString("displayName", "display_name", maxLength = 512),
            assurance = value.optionalString("assuranceLevel", "assurance_level", maxLength = 64),
        )
    }

    fun parseOrganizations(body: String): List<OrganizationSummary> {
        val root = parseElement(body)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["organizations"] as? JsonArray
            else -> null
        } ?: throw CliOperationException("The identity server returned an invalid organization response.")
        if (array.size > 10_000) {
            throw CliOperationException("The identity server returned too many organizations.")
        }
        return array.map { item ->
            val value = item as? JsonObject
                ?: throw CliOperationException("The identity server returned an invalid organization response.")
            OrganizationSummary(
                id = value.requiredString("id", "organizationId", maxLength = 128),
                name = value.requiredString("name", maxLength = 512),
                role = value.requiredString("role", maxLength = 64),
            )
        }
    }

    fun encodeCredentials(value: StoredCredentials): String = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("server", JsonPrimitive(value.server))
        put("accessToken", JsonPrimitive(value.accessToken))
        put("refreshToken", JsonPrimitive(value.refreshToken))
        put("accessTokenExpiresAt", JsonPrimitive(value.accessTokenExpiresAt.toString()))
        put("refreshTokenExpiresAt", JsonPrimitive(value.refreshTokenExpiresAt.toString()))
        put("grantedScopes", JsonArray(value.grantedScopes.sorted().map(::JsonPrimitive)))
        value.selectedOrganizationId?.let { put("selectedOrganizationId", JsonPrimitive(it)) }
    }.toString()

    fun encodeDeviceCancellation(deviceCode: String): String = buildJsonObject {
        put("deviceCode", JsonPrimitive(deviceCode))
    }.toString()

    fun decodeCredentials(encoded: String): StoredCredentials {
        val value = parseObject(encoded)
        if (value.requiredLong("version") != 1L) {
            throw CliOperationException("Stored CLI credentials use an unsupported format.")
        }
        val scopes = (value["grantedScopes"] as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.length <= 256 }
                ?: throw CliOperationException("Stored CLI credentials are invalid.")
        }?.toSet() ?: throw CliOperationException("Stored CLI credentials are invalid.")
        return try {
            StoredCredentials(
                server = normalizedServer(value.requiredString("server", maxLength = 4_096)),
                accessToken = value.requiredString("accessToken", maxLength = 8_192),
                refreshToken = value.requiredString("refreshToken", maxLength = 8_192),
                accessTokenExpiresAt = Instant.parse(value.requiredString("accessTokenExpiresAt", maxLength = 64)),
                refreshTokenExpiresAt = Instant.parse(value.requiredString("refreshTokenExpiresAt", maxLength = 64)),
                grantedScopes = scopes,
                selectedOrganizationId = value.optionalString("selectedOrganizationId", maxLength = 128),
            )
        } catch (failure: CliOperationException) {
            throw failure
        } catch (_: Exception) {
            throw CliOperationException("Stored CLI credentials are invalid.")
        }
    }

    private fun parseObject(body: String): JsonObject = parseElement(body) as? JsonObject
        ?: throw CliOperationException("The identity server returned an invalid JSON response.")

    private fun parseElement(body: String): JsonElement {
        if (body.length > MAX_JSON_CHARS) {
            throw CliOperationException("The identity server response was too large.")
        }
        return try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            throw CliOperationException("The identity server returned an invalid JSON response.")
        }
    }

    private fun validateVerificationUri(raw: String) {
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw CliOperationException("The identity server returned an invalid verification URL.")
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]")
            ?: throw CliOperationException("The identity server returned an invalid verification URL.")
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (uri.userInfo != null || (uri.scheme != "https" && !(uri.scheme == "http" && loopback))) {
            throw CliOperationException("The identity server returned an unsafe verification URL.")
        }
    }

    private fun JsonObject.requiredString(vararg names: String, maxLength: Int): String =
        optionalString(*names, maxLength = maxLength)
            ?: throw CliOperationException("The identity server returned an incomplete response.")

    private fun JsonObject.optionalString(vararg names: String, maxLength: Int): String? {
        val primitive = names.firstNotNullOfOrNull { name -> get(name) as? JsonPrimitive } ?: return null
        if (!primitive.isString) {
            throw CliOperationException("The identity server returned an invalid response.")
        }
        return primitive.contentOrNull?.takeIf { it.isNotBlank() && it.length <= maxLength }
            ?: throw CliOperationException("The identity server returned an invalid response.")
    }

    private fun JsonObject.requiredLong(vararg names: String): Long =
        optionalLong(*names) ?: throw CliOperationException("The identity server returned an incomplete response.")

    private fun JsonObject.optionalLong(vararg names: String): Long? {
        val primitive = names.firstNotNullOfOrNull { name -> get(name) as? JsonPrimitive } ?: return null
        return primitive.content.toLongOrNull()
            ?: throw CliOperationException("The identity server returned an invalid response.")
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun hostForAuthority(host: String): String = if (':' in host) "[$host]" else host
}

internal data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
) {
    override fun toString(): String =
        "DeviceAuthorization(deviceCode=<redacted>, userCode=<redacted>, verificationUri=$verificationUri, " +
            "expiresInSeconds=$expiresInSeconds, intervalSeconds=$intervalSeconds)"
}

internal data class TokenGrant(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresInSeconds: Long,
    val refreshExpiresInSeconds: Long,
    val scopes: Set<String>,
) {
    override fun toString(): String =
        "TokenGrant(accessToken=<redacted>, refreshToken=<redacted>, " +
            "accessExpiresInSeconds=$accessExpiresInSeconds, refreshExpiresInSeconds=$refreshExpiresInSeconds, " +
            "scopes=$scopes)"
}

internal enum class OAuthError(val wireValue: String) {
    AUTHORIZATION_PENDING("authorization_pending"),
    SLOW_DOWN("slow_down"),
    ACCESS_DENIED("access_denied"),
    EXPIRED_TOKEN("expired_token"),
    INVALID_GRANT("invalid_grant"),
}

internal data class WhoAmI(
    val userId: String,
    val displayName: String?,
    val assurance: String?,
)

internal data class OrganizationSummary(
    val id: String,
    val name: String,
    val role: String,
)
