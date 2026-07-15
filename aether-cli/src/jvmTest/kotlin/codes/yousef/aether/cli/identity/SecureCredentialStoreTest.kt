package codes.yousef.aether.cli.identity

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureCredentialStoreTest {
    @Test
    fun `Linux store requires a session bus without executing a command`() = runTest {
        val runner = RecordingRunner()
        val store = LinuxSecretServiceCredentialStore(runner, hasSessionBus = false)

        assertFalse(store.isAvailable())
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `Linux store sends credential payload over stdin and not command arguments`() = runTest {
        val runner = RecordingRunner(mutableListOf(SecureCommandResult(0, "")))
        val store = LinuxSecretServiceCredentialStore(runner, hasSessionBus = true)
        val credentials = credentials("linux-access", "linux-refresh")

        store.write(SERVER, credentials)

        val invocation = runner.commands.single()
        assertFalse(invocation.command.joinToString(" ").contains("linux-access"))
        assertFalse(invocation.command.joinToString(" ").contains("linux-refresh"))
        assertTrue(invocation.stdin.orEmpty().contains("linux-access"))
        assertTrue(invocation.stdin.orEmpty().contains("linux-refresh"))
    }

    @Test
    fun `macOS store sends exact credential payload through security interactive stdin`() = runTest {
        val runner = RecordingRunner(mutableListOf(SecureCommandResult(0, "security> ")))
        val store = MacOsKeychainCredentialStore(runner)
        val credentials = credentials("mac-access", "mac-refresh")

        store.write(SERVER, credentials)

        val invocation = runner.commands.single()
        assertEquals(listOf("security", "-i"), invocation.command)
        assertFalse(invocation.command.any { it.contains("mac-access") || it.contains("mac-refresh") })
        val passwordHex = invocation.stdin.orEmpty()
            .substringAfter(" -X ", missingDelimiterValue = "")
            .trimEnd('\r', '\n')
        assertTrue(passwordHex.isNotEmpty())
        val storedBytes = passwordHex.chunked(2)
            .map { octet -> octet.toInt(16).toByte() }
            .toByteArray()
        val storedPayload = storedBytes.toString(Charsets.UTF_8)
        storedBytes.fill(0)
        assertEquals(IdentityCliProtocol.encodeCredentials(credentials), storedPayload)
    }

    @Test
    fun `credential codec round trips but diagnostic rendering is redacted`() {
        val credentials = credentials("codec-access", "codec-refresh")

        val decoded = IdentityCliProtocol.decodeCredentials(IdentityCliProtocol.encodeCredentials(credentials))

        assertEquals(credentials, decoded)
        assertFalse(credentials.toString().contains("codec-access"))
        assertFalse(credentials.toString().contains("codec-refresh"))
        assertFalse(
            CliHttpRequest(
                method = "POST",
                url = "$SERVER/oauth/token",
                headers = mapOf("Authorization" to "Bearer codec-access"),
                body = "refresh_token=codec-refresh",
            ).toString().contains("codec-access"),
        )
        assertFalse(
            CliHttpRequest(
                method = "POST",
                url = "$SERVER/oauth/token",
                body = "refresh_token=codec-refresh",
            ).toString().contains("codec-refresh"),
        )
    }

    @Test
    fun `unknown operating systems have no plaintext fallback`() = runTest {
        val store = OperatingSystemCredentialStore.create("Plan 9", emptyMap())

        assertFalse(store.isAvailable())
        assertTrue(store === UnavailableCredentialStore)
    }

    private fun credentials(access: String, refresh: String) = StoredCredentials(
        server = SERVER,
        accessToken = access,
        refreshToken = refresh,
        accessTokenExpiresAt = Instant.parse("2026-07-14T12:15:00Z"),
        refreshTokenExpiresAt = Instant.parse("2026-08-13T12:00:00Z"),
        grantedScopes = setOf("identity.profile"),
    )

    private data class Invocation(val command: List<String>, val stdin: String?)

    private class RecordingRunner(
        private val results: MutableList<SecureCommandResult> = mutableListOf(),
    ) : SecureCommandRunner {
        val commands = mutableListOf<Invocation>()

        override fun run(command: List<String>, stdin: String?): SecureCommandResult {
            commands += Invocation(command, stdin)
            return if (results.isEmpty()) SecureCommandResult(0, "") else results.removeFirst()
        }
    }

    private companion object {
        const val SERVER = "https://identity.example"
    }
}
