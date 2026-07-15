package codes.yousef.aether.cli.identity

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class IdentityCliTest {
    @Test
    fun `device authorization diagnostic rendering redacts both codes`() {
        val authorization = IdentityCliProtocol.parseDeviceAuthorization(deviceAuthorization().body)

        assertFalse(authorization.toString().contains("device-secret"))
        assertFalse(authorization.toString().contains("ABCD-EFGH"))
        assertEquals("device-secret", authorization.deviceCode)
        assertEquals("ABCD-EFGH", authorization.userCode)
    }

    @Test
    fun `usage failures never echo arbitrary rejected input`() {
        val marker = "never-echo-%sensitive-input"
        val rejected = listOf(
            listOf("auth", marker),
            listOf("auth", "login", marker),
            listOf("auth", "org", marker),
            listOf("auth", "whoami", marker),
            listOf("auth", "login", "--scope", marker)
        )

        rejected.forEach { args ->
            val failure = assertFailsWith<CliUsageException> {
                IdentityCliCommandParser.parse(args, SERVER)
            }
            assertFalse(marker in failure.message.orEmpty())
        }
    }

    @Test
    fun `device login polls pending and slow down then stores rotated token grant`() = runTest {
        val fixture = fixture(
            responses = listOf(
                deviceAuthorization(),
                oauthError("authorization_pending"),
                oauthError("slow_down"),
                tokenGrant("access-final", "refresh-final"),
            ),
        )

        val exit = fixture.cli.execute(
            listOf("auth", "login", "--server", SERVER, "--scope", "identity.profile,packages.publish"),
        )

        assertEquals(IdentityCli.EXIT_SUCCESS, exit)
        assertEquals(listOf(5.seconds, 5.seconds, 10.seconds), fixture.delay.waits)
        val stored = assertNotNull(fixture.store.value)
        assertEquals("access-final", stored.accessToken)
        assertEquals("refresh-final", stored.refreshToken)
        assertEquals(setOf("identity.profile", "packages.publish"), stored.grantedScopes)
        assertEquals(4, fixture.http.requests.size)
        assertTrue(fixture.http.requests.drop(1).all { "device_code=device-secret" in (it.body ?: "") })
        assertTrue(fixture.io.output.any { "ABCD-EFGH" in it })
        assertRedacted(fixture.io, "device-secret", "access-final", "refresh-final")
    }

    @Test
    fun `device polling backs off after a transport timeout without extending the grant`() = runTest {
        val clock = MutableClock(NOW)
        val delay = AdvancingDelay(clock)
        val requests = mutableListOf<CliHttpRequest>()
        var call = 0
        val http = CliHttpClient { request ->
            requests += request
            when (call++) {
                0 -> deviceAuthorization(expiresIn = 20)
                1 -> throw CliTransportException()
                2 -> tokenGrant("access-after-timeout", "refresh-after-timeout")
                else -> error("Unexpected request")
            }
        }
        val store = FakeCredentialStore()
        val io = RecordingIo()
        val cli = IdentityCli(http, clock, delay, io, store, defaultServer = SERVER)

        val exit = cli.execute(listOf("auth", "login", "--server", SERVER))

        assertEquals(IdentityCli.EXIT_SUCCESS, exit)
        assertEquals(listOf(5.seconds, 10.seconds), delay.waits)
        assertEquals(3, requests.size)
        assertEquals(NOW.plusSeconds(15), clock.now())
        assertEquals("access-after-timeout", store.value?.accessToken)
        assertRedacted(io, "device-secret", "access-after-timeout", "refresh-after-timeout")
    }

    @Test
    fun `secure store is probed before device authorization`() = runTest {
        val fixture = fixture(responses = emptyList(), storeAvailable = false)

        val exit = fixture.cli.execute(listOf("auth", "login", "--server", SERVER))

        assertEquals(IdentityCli.EXIT_FAILURE, exit)
        assertEquals(1, fixture.store.availabilityChecks)
        assertTrue(fixture.http.requests.isEmpty())
        assertTrue(fixture.io.error.single().contains("--no-store"))
    }

    @Test
    fun `no store explicitly bypasses preflight and never persists tokens`() = runTest {
        val fixture = fixture(
            responses = listOf(deviceAuthorization(), tokenGrant("access-no-store", "refresh-no-store")),
            storeAvailable = false,
        )

        val exit = fixture.cli.execute(listOf("auth", "login", "--server", SERVER, "--no-store"))

        assertEquals(IdentityCli.EXIT_SUCCESS, exit)
        assertEquals(0, fixture.store.availabilityChecks)
        assertEquals(0, fixture.store.writeCount)
        assertNull(fixture.store.value)
        assertTrue(fixture.io.output.last().contains("not stored"))
        assertRedacted(fixture.io, "access-no-store", "refresh-no-store", "device-secret")
    }

    @Test
    fun `polling expires without sending a token request at the deadline`() = runTest {
        val fixture = fixture(responses = listOf(deviceAuthorization(expiresIn = 5)))

        val exit = fixture.cli.execute(listOf("auth", "login", "--server", SERVER, "--no-store"))

        assertEquals(IdentityCli.EXIT_FAILURE, exit)
        assertEquals(listOf(5.seconds), fixture.delay.waits)
        assertEquals(1, fixture.http.requests.size)
        assertTrue(fixture.io.error.single().contains("expired"))
    }

    @Test
    fun `polling cancellation sends a best effort cancellation transition`() = runTest {
        var cancelled = false
        val clock = MutableClock(NOW)
        val delay = AdvancingDelay(clock) { cancelled = true }
        val http = QueueHttp(listOf(deviceAuthorization(), response(204, "")))
        val store = FakeCredentialStore()
        val io = RecordingIo()
        val cli = IdentityCli(http, clock, delay, io, store, CliCancellation { cancelled }, SERVER)

        val exit = cli.execute(listOf("auth", "login", "--server", SERVER, "--no-store"))

        assertEquals(IdentityCli.EXIT_FAILURE, exit)
        assertEquals(2, http.requests.size)
        val cancellation = http.requests.last()
        assertTrue(cancellation.url.endsWith(IdentityCliProtocol.DEVICE_CANCELLATION_PATH))
        assertEquals("application/json", cancellation.headers["Content-Type"])
        assertEquals("""{"deviceCode":"device-secret"}""", cancellation.body)
        assertTrue(io.error.single().contains("cancelled"))
        assertRedacted(io, "device-secret")
    }

    @Test
    fun `expired access token refresh rotates both tokens before whoami`() = runTest {
        val existing = credentials(access = "old-access", refresh = "old-refresh", accessExpiresAt = NOW)
        val fixture = fixture(
            responses = listOf(
                tokenGrant("new-access", "new-refresh"),
                response(
                    200,
                    """{"userId":"018f0f6e-7b20-7abc-8def-0123456789ab","displayName":"Aether User","assuranceLevel":"passkey"}""",
                ),
            ),
            initialCredentials = existing,
        )

        val exit = fixture.cli.execute(listOf("auth", "whoami", "--server", SERVER))

        assertEquals(IdentityCli.EXIT_SUCCESS, exit)
        val rotated = assertNotNull(fixture.store.value)
        assertEquals("new-access", rotated.accessToken)
        assertEquals("new-refresh", rotated.refreshToken)
        assertEquals(1, fixture.store.writeCount)
        assertTrue(fixture.http.requests.first().body.orEmpty().contains("refresh_token=old-refresh"))
        assertEquals("Bearer new-access", fixture.http.requests.last().headers["Authorization"])
        assertTrue(fixture.io.output.first().contains("Aether User"))
        assertRedacted(fixture.io, "old-access", "old-refresh", "new-access", "new-refresh")
        assertFalse(rotated.toString().contains("new-access"))
        assertFalse(rotated.toString().contains("new-refresh"))
    }

    @Test
    fun `invalid refresh deletes replayed credentials and does not call resource`() = runTest {
        val existing = credentials(access = "old-access", refresh = "replayed-refresh", accessExpiresAt = NOW)
        val fixture = fixture(
            responses = listOf(oauthError("invalid_grant")),
            initialCredentials = existing,
        )

        val exit = fixture.cli.execute(listOf("auth", "whoami", "--server", SERVER))

        assertEquals(IdentityCli.EXIT_FAILURE, exit)
        assertNull(fixture.store.value)
        assertEquals(1, fixture.store.deleteCount)
        assertEquals(1, fixture.http.requests.size)
        assertRedacted(fixture.io, "replayed-refresh", "old-access")
    }

    @Test
    fun `organization list marks selection and use validates explicit organization route`() = runTest {
        val selected = ORG_ONE
        val fixture = fixture(
            responses = listOf(
                response(
                    200,
                    """{"organizations":[{"id":"$ORG_ONE","name":"One","role":"OWNER"},{"id":"$ORG_TWO","name":"Two","role":"VIEWER"}]}""",
                ),
                response(200, """{"id":"$ORG_TWO","name":"Two","role":"VIEWER"}"""),
            ),
            initialCredentials = credentials(selectedOrganizationId = selected),
        )

        assertEquals(
            IdentityCli.EXIT_SUCCESS,
            fixture.cli.execute(listOf("auth", "org", "list", "--server", SERVER)),
        )
        assertTrue(fixture.io.output.first().startsWith("* $ORG_ONE"))
        assertEquals(
            IdentityCli.EXIT_SUCCESS,
            fixture.cli.execute(listOf("auth", "org", "use", ORG_TWO, "--server", SERVER)),
        )

        assertTrue(fixture.http.requests.last().url.endsWith("/identity/v1/organizations/$ORG_TWO"))
        assertEquals(ORG_TWO, fixture.store.value?.selectedOrganizationId)
    }

    @Test
    fun `logout revokes refresh token and always removes local credentials`() = runTest {
        val fixture = fixture(
            responses = listOf(response(503, """{"code":"temporarily_unavailable"}""")),
            initialCredentials = credentials(refresh = "logout-refresh"),
        )

        val exit = fixture.cli.execute(listOf("auth", "logout", "--server", SERVER))

        assertEquals(IdentityCli.EXIT_FAILURE, exit)
        assertNull(fixture.store.value)
        assertEquals(1, fixture.store.deleteCount)
        assertTrue(fixture.http.requests.single().url.endsWith("/oauth/revoke"))
        assertTrue(fixture.http.requests.single().body.orEmpty().contains("token=logout-refresh"))
        assertTrue(fixture.io.error.single().contains("Local credentials were removed"))
        assertRedacted(fixture.io, "logout-refresh")
    }

    private fun fixture(
        responses: List<CliHttpResponse>,
        storeAvailable: Boolean = true,
        initialCredentials: StoredCredentials? = null,
    ): Fixture {
        val clock = MutableClock(NOW)
        val http = QueueHttp(responses)
        val store = FakeCredentialStore(storeAvailable, initialCredentials)
        val io = RecordingIo()
        val delay = AdvancingDelay(clock)
        return Fixture(
            cli = IdentityCli(http, clock, delay, io, store, defaultServer = SERVER),
            clock = clock,
            delay = delay,
            http = http,
            store = store,
            io = io,
        )
    }

    private fun deviceAuthorization(expiresIn: Int = 600): CliHttpResponse = response(
        200,
        """{"device_code":"device-secret","user_code":"ABCD-EFGH","verification_uri":"https://identity.example/activate","expires_in":$expiresIn,"interval":5}""",
    )

    private fun tokenGrant(access: String, refresh: String): CliHttpResponse = response(
        200,
        """{"access_token":"$access","token_type":"Bearer","expires_in":900,"refresh_token":"$refresh","refresh_expires_in":2592000,"scope":"identity.profile packages.publish"}""",
    )

    private fun oauthError(error: String): CliHttpResponse = response(400, """{"error":"$error"}""")

    private fun response(status: Int, body: String): CliHttpResponse = CliHttpResponse(status = status, body = body)

    private fun credentials(
        access: String = "access",
        refresh: String = "refresh",
        accessExpiresAt: Instant = NOW.plusSeconds(3_600),
        selectedOrganizationId: String? = null,
    ) = StoredCredentials(
        server = SERVER,
        accessToken = access,
        refreshToken = refresh,
        accessTokenExpiresAt = accessExpiresAt,
        refreshTokenExpiresAt = NOW.plusSeconds(30L * 24 * 60 * 60),
        grantedScopes = setOf("identity.profile", "identity.organizations"),
        selectedOrganizationId = selectedOrganizationId,
    )

    private fun assertRedacted(io: RecordingIo, vararg secrets: String) {
        val rendered = (io.output + io.error).joinToString("\n")
        secrets.forEach { secret -> assertFalse(rendered.contains(secret), "secret was rendered: $secret") }
    }

    private data class Fixture(
        val cli: IdentityCli,
        val clock: MutableClock,
        val delay: AdvancingDelay,
        val http: QueueHttp,
        val store: FakeCredentialStore,
        val io: RecordingIo,
    )

    private class MutableClock(var instant: Instant) : CliClock {
        override fun now(): Instant = instant
    }

    private class AdvancingDelay(
        private val clock: MutableClock,
        private val afterWait: () -> Unit = {},
    ) : CliDelay {
        val waits = mutableListOf<Duration>()

        override suspend fun wait(duration: Duration) {
            waits += duration
            clock.instant = clock.instant.plusMillis(duration.inWholeMilliseconds)
            afterWait()
        }
    }

    private class QueueHttp(responses: List<CliHttpResponse>) : CliHttpClient {
        private val responses = ArrayDeque(responses)
        val requests = mutableListOf<CliHttpRequest>()

        override suspend fun execute(request: CliHttpRequest): CliHttpResponse {
            requests += request
            return responses.removeFirstOrNull() ?: error("No response queued for ${request.method} ${request.url}")
        }
    }

    private class FakeCredentialStore(
        private val available: Boolean = true,
        initial: StoredCredentials? = null,
    ) : CredentialStore {
        var value: StoredCredentials? = initial
        var availabilityChecks = 0
        var writeCount = 0
        var deleteCount = 0

        override suspend fun isAvailable(): Boolean {
            availabilityChecks++
            return available
        }

        override suspend fun read(account: String): StoredCredentials? = value

        override suspend fun write(account: String, credentials: StoredCredentials) {
            writeCount++
            value = credentials
        }

        override suspend fun delete(account: String) {
            deleteCount++
            value = null
        }
    }

    private class RecordingIo : CliIo {
        val output = mutableListOf<String>()
        val error = mutableListOf<String>()
        override fun output(message: String) {
            output += message
        }

        override fun error(message: String) {
            error += message
        }
    }

    private companion object {
        const val SERVER = "https://identity.example"
        const val ORG_ONE = "018f0f6e-7b20-7abc-8def-0123456789ab"
        const val ORG_TWO = "018f0f6e-7b20-7abc-8def-1123456789ab"
        val NOW: Instant = Instant.parse("2026-07-14T12:00:00Z")
    }
}
