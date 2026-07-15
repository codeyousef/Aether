package codes.yousef.aether.cli.identity

import java.time.Instant
import kotlin.time.Duration

/** A deliberately small HTTP boundary so protocol behavior can be tested without a server. */
fun interface CliHttpClient {
    suspend fun execute(request: CliHttpRequest): CliHttpResponse
}

/** A retryable transport failure with no response body or credential-bearing diagnostic text. */
class CliTransportException internal constructor() : Exception("Identity transport unavailable")

data class CliHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    override fun toString(): String {
        val safeHeaders = headers.mapValues { (name, value) ->
            if (name.equals("Authorization", ignoreCase = true)) "<redacted>" else value
        }
        return "CliHttpRequest(method=$method, url=$url, headers=$safeHeaders, body=${body?.let { "<redacted>" }})"
    }
}

data class CliHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
) {
    override fun toString(): String =
        "CliHttpResponse(status=$status, headers=$headers, body=${if (body.isEmpty()) "" else "<redacted>"})"
}

fun interface CliClock {
    fun now(): Instant
}

fun interface CliDelay {
    suspend fun wait(duration: Duration)
}

/** Allows Ctrl+C or an embedding application to stop device polling deterministically. */
fun interface CliCancellation {
    fun isCancellationRequested(): Boolean
}

interface CliIo {
    fun output(message: String)
    fun error(message: String)
}

/**
 * A credential store must be backed by an operating-system protected facility. The production
 * factory only returns Keychain, DPAPI, or Secret Service implementations; there is intentionally
 * no file or preferences implementation.
 */
interface CredentialStore {
    suspend fun isAvailable(): Boolean
    suspend fun read(account: String): StoredCredentials?
    suspend fun write(account: String, credentials: StoredCredentials)
    suspend fun delete(account: String)
}

/**
 * Device credentials are opaque. The custom [toString] is intentionally redacted so a failed test,
 * debugger evaluation, or accidental interpolation cannot disclose either token.
 */
data class StoredCredentials(
    val server: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshTokenExpiresAt: Instant,
    val grantedScopes: Set<String>,
    val selectedOrganizationId: String? = null,
) {
    init {
        require(accessToken.isNotBlank())
        require(refreshToken.isNotBlank())
    }

    override fun toString(): String =
        "StoredCredentials(server=$server, accessToken=<redacted>, refreshToken=<redacted>, " +
            "accessTokenExpiresAt=$accessTokenExpiresAt, refreshTokenExpiresAt=$refreshTokenExpiresAt, " +
            "grantedScopes=$grantedScopes, selectedOrganizationId=$selectedOrganizationId)"
}

internal class CliUsageException(message: String) : IllegalArgumentException(message)

internal class CliOperationException(message: String) : IllegalStateException(message)
