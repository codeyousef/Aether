package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import codes.yousef.aether.auth.webauthn.WebAuthnService
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.*
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.*
import java.time.Duration
import kotlinx.serialization.json.*
import kotlin.test.*

class AccountSecurityHttpTest {
    @Test
    fun realHttpAuthorityEnforcesOriginCsrfBoundsCookiesAndPrivateIds() = runBlocking {
        val runtime = jvmIdentityRuntime(secrets = IdentitySecretResolver { IdentitySecret.fromUtf8("test-only-http-security-key") })
        val config = accountSecurityTestConfig()
        val ids = IdentityIdFactory(runtime)
        val now = runtime.clock.now()
        val user = IdentityFixtures.user(ids.newUserId()).copy(primaryEmail = EmailAddress("http@example.invalid"),
            createdAt = now, updatedAt = now, activatedAt = now)
        val store = InMemoryIdentityStore(InMemoryIdentityStoreSeed(users = listOf(user)))
        val passkey = IdentitySessionIssuer(runtime, config).issue(user, AuthenticationAssurance.PASSKEY, SessionAuthenticationMethod.PASSKEY, now)
        assertIs<StoreResult.Success<*>>(store.createSession(CreateSessionCommand(passkey.session, AuditEvent(ids.newAuditEventId(),
            AuditActor(type = AuditActorType.USER, userId = user.id), action = AuditAction.SESSION_CREATED,
            target = AuditTarget(AuditTargetType.USER, user.id.value), outcome = AuditOutcome.SUCCEEDED, occurredAt = now))))
        var delivery: IdentityEmailDelivery? = null
        val service = IdentityAccountSecurityService(store, runtime, config, IdentityAccountAttemptLimiter { _, _ -> true },
            JvmPasswordProvider(), IdentityEmailDeliveryProvider { delivery = it; true })
        val api = IdentityHttpApi(runtime, config, WebAuthnService(store, runtime, config), IdentityRecoveryService(store, runtime, config),
            IdentityAccountManagementService(store, runtime), IdentityDeviceAuthorizationService(store, runtime, config, setOf(Capability.ORGANIZATION_READ)),
            accountSecurity = service)
        val logs = mutableListOf<String>()
        val pipeline = Pipeline().apply {
            installCallLogging(messageSink = logs::add)
            use(IdentityMiddleware(StoreBackedIdentityContextResolver(store, runtime, config), runtime.clock, runtime.secureRandom).asMiddleware())
            use(api.asMiddleware())
        }
        val server = VertxServer(VertxServerConfig(host = "127.0.0.1", port = 0,
            diagnostics = RequestDiagnosticsPolicy(requestIdSource = RequestIdSource.SERVER_GENERATED_ONLY)), pipeline) {
            respondWithApiError(it, ApiErrorKind.NOT_FOUND)
        }
        server.start()
        val client = HttpClient.newHttpClient()
        val marker = "PRIVATE_HTTP_SECRET_2524"
        val password = "PRIVATE_HTTP_PASSWORD_2522"
        fun send(path: String, body: String = "{}", origin: String? = config.publicBaseUrl,
            cookie: String? = null, csrf: String? = null): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI("http://localhost:${server.actualPort}$path"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").header("X-Request-Id", marker)
                .POST(HttpRequest.BodyPublishers.ofString(body))
            origin?.let { request.header("Origin", it) }
            cookie?.let { request.header("Cookie", "${config.cookie.name}=$it") }
            csrf?.let { request.header("X-CSRF-Token", it) }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).also { response ->
                assertFalse(response.body().contains(password))
                assertFalse(response.body().contains(marker))
                assertFalse(response.headers().toString().contains(marker))
                assertTrue(response.headers().firstValue("X-Request-Id").orElseThrow().matches(Regex("[a-f0-9]{32}")))
            }
        }
        try {
            val replaceBody = "{\"password\":\"$password\"}"
            assertEquals(403, send(IdentityHttpApi.PASSWORD_REPLACE, replaceBody, cookie = passkey.cookieValue()).statusCode())
            assertEquals(200, send(IdentityHttpApi.PASSWORD_REPLACE, replaceBody, cookie = passkey.cookieValue(), csrf = passkey.csrfToken()).statusCode())
            val loginBody = "{\"email\":\"http@example.invalid\",\"password\":\"$password\"}"
            assertEquals(403, send(IdentityHttpApi.PASSWORD_LOGIN, loginBody, origin = null).statusCode())
            assertEquals(403, send(IdentityHttpApi.PASSWORD_LOGIN, loginBody, origin = "https://hostile.invalid").statusCode())
            assertEquals(400, send(IdentityHttpApi.PASSWORD_LOGIN, "{broken").statusCode())
            assertEquals(400, send(IdentityHttpApi.PASSWORD_LOGIN, "x".repeat(4097)).statusCode())
            val login = send(IdentityHttpApi.PASSWORD_LOGIN, loginBody)
            assertEquals(200, login.statusCode(), login.body())
            val cookie = login.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';').substringAfter('=')
            val csrf = Json.parseToJsonElement(login.body()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            assertTrue(login.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"))
            assertEquals(401, send(IdentityHttpApi.PASSWORD_REPLACE, replaceBody, cookie = cookie, csrf = csrf).statusCode())
            assertEquals(200, send(IdentityHttpApi.EMAIL_REQUEST, cookie = cookie, csrf = csrf).statusCode())
            val code = requireNotNull(delivery).code()
            assertEquals(403, send(IdentityHttpApi.EMAIL_VERIFY, "{\"code\":\"$code\"}", cookie = cookie).statusCode())
            assertEquals(200, send(IdentityHttpApi.EMAIL_VERIFY, "{\"code\":\"$code\"}", cookie = cookie, csrf = csrf).statusCode())
            assertEquals(400, send(IdentityHttpApi.EMAIL_VERIFY, "{\"code\":\"$code\"}", cookie = cookie, csrf = csrf).statusCode())
            val unknown = send(IdentityHttpApi.PASSWORD_LOGIN, loginBody.replace("http@example.invalid", "unknown@example.invalid"))
            val invalid = send(IdentityHttpApi.PASSWORD_LOGIN, loginBody.replace(password, "incorrect-password"))
            assertEquals(unknown.statusCode(), invalid.statusCode())
            assertEquals(Json.parseToJsonElement(unknown.body()).jsonObject["code"], Json.parseToJsonElement(invalid.body()).jsonObject["code"])
            assertFalse(logs.any { it.contains(marker) || it.contains(password) || it.contains(code) })
        } finally { server.close() }
    }
}
