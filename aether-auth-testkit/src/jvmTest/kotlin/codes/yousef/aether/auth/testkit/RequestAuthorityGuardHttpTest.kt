package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import codes.yousef.aether.core.jvm.*
import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.*

class RequestAuthorityGuardHttpTest {
    @Test
    fun originalHttpExchangeUsesCanonicalSessionNativeAndProxyAuthority() = runBlocking {
        val runtime = jvmIdentityRuntime(secrets = IdentitySecretResolver { IdentitySecret.fromUtf8("guard-test-only-key") })
        val temp = Files.createTempDirectory("aether-guard-tls-")
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/tls/$name")).use { it.readBytes() }
        val certificate = resource("test-cert.pem")
        Files.write(temp.resolve("cert.pem"), certificate); Files.write(temp.resolve("key.pem"), resource("test-key.pem"))
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null)
            setCertificateEntry("test", CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream())) }
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trustManager.trustManagers, null) }
        try {
            // Test direct development, direct HTTPS staging, and a configured TLS-terminating proxy.
            for (mode in listOf("development", "direct-tls", "proxy")) {
                val environment = if (mode == "development") IdentityEnvironment.TEST else IdentityEnvironment.STAGING
                fun key(name: String) = SecretReference("test", name, "v1", environment)
                val publicOrigin = if (mode == "development") "http://localhost:8080" else "https://identity.example.invalid"
                val config = IdentityConfig(environment = environment, publicBaseUrl = publicOrigin,
                    relyingParty = RelyingPartyConfig(if (mode == "development") "localhost" else "identity.example.invalid", "Test", setOf(publicOrigin)),
                    trustedProxy = if (mode == "proxy") TrustedProxyConfig(TrustedProxyMode.TRUSTED_CIDRS, setOf("127.0.0.1/32")) else TrustedProxyConfig(),
                    keys = IdentityKeyConfig(sessionPepper = key("session"), recoveryPepper = key("recovery"), deviceTokenPepper = key("device"),
                        serviceCredentialPepper = key("service"), auditPseudonymizationKey = key("audit"), encryptionKey = key("encryption"), signingKey = key("signing")))
                val ids = IdentityIdFactory(runtime); val now = runtime.clock.now()
                val user = IdentityFixtures.user(ids.newUserId()).copy(createdAt = now, updatedAt = now, activatedAt = now)
                val org = IdentityFixtures.organization(ids.newOrganizationId())
                val membership = IdentityFixtures.membership(ids.newMembershipId(), org.id, user.id)
                val issued = IdentitySessionIssuer(runtime, config).issue(user, AuthenticationAssurance.PASSKEY, SessionAuthenticationMethod.PASSKEY, now)
                val store = InMemoryIdentityStore(InMemoryIdentityStoreSeed(users = listOf(user), sessions = listOf(issued.session),
                    organizations = listOf(org), memberships = listOf(membership)))
                val deviceService = IdentityDeviceAuthorizationService(store, runtime, config, setOf(Capability.ORGANIZATION_READ))
                val context = accountSecurityContext(issued.session).copy(organization = org, membership = membership)
                val start = assertIs<IdentityOperationResult.Success<DeviceAuthorizationResponse>>(deviceService.start("guard-client", setOf(Capability.ORGANIZATION_READ))).value
                assertIs<IdentityOperationResult.Success<*>>(deviceService.approve(start.userCode, context, org.id, setOf(Capability.ORGANIZATION_READ)))
                val accessToken = assertIs<DeviceTokenEndpointResult.Success>(deviceService.poll(start.deviceCode, "guard-client")).response.accessToken
                val resolver = CompositeIdentityContextResolver(StoreBackedIdentityContextResolver(store, runtime, config), config,
                    IdentityBearerAuthenticator { deviceService.authenticateAccessToken(it) })
                val pipeline = Pipeline().apply { use(IdentityMiddleware(resolver, runtime.clock, runtime.secureRandom).asMiddleware()) }
                val guard = IdentityRequestAuthorityGuard(runtime, config)
                var calls = 0
                val server = VertxServer(VertxServerConfig(host = "127.0.0.1", port = 0,
                    tls = if (mode == "direct-tls") HttpsListenerConfig(temp.resolve("cert.pem").toString(), temp.resolve("key.pem").toString()) else null), pipeline) { original ->
                    guard.protect(original) { exchange ->
                        assertSame(original, exchange); assertEquals("/application/mutate", exchange.request.path)
                        assertEquals("unchanged-body", exchange.request.bodyBytes().decodeToString())
                        assertNotNull(exchange.request.connection.peerAddress)
                        calls++; exchange.response.write("effect-$calls")
                    }
                }
                server.start()
                fun send(headers: List<Pair<String, String>>, query: String = "", host: String = publicOrigin.substringAfter("://"),
                    forwarding: List<Pair<String, String>> = if (mode == "proxy") listOf("X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "identity.example.invalid") else emptyList()): Int {
                    val socket = if (mode == "direct-tls") ssl.socketFactory.createSocket("localhost", server.actualPort) else Socket("127.0.0.1", server.actualPort)
                    return socket.use {
                        it.soTimeout = 5000
                        if (it is javax.net.ssl.SSLSocket) { it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }; it.startHandshake() }
                        val request = buildString {
                            append("POST /application/mutate$query HTTP/1.1\r\nHost: $host\r\nContent-Length: 14\r\nConnection: close\r\n")
                            (headers + forwarding).forEach { (name, value) -> append("$name: $value\r\n") }
                            append("\r\nunchanged-body")
                        }
                        it.getOutputStream().write(request.toByteArray()); it.getOutputStream().flush()
                        it.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
                    }
                }
                val cookie = "Cookie" to "${config.cookie.name}=${issued.cookieValue()}"
                val origin = "Origin" to publicOrigin
                val csrf = "X-CSRF-Token" to issued.csrfToken()
                val valid = listOf(cookie, origin, csrf)
                fun rejected(headers: List<Pair<String, String>>, query: String = "", expected: Int = 403) {
                    val before = calls; assertEquals(expected, send(headers, query), "$mode should reject request"); assertEquals(before, calls)
                }
                try {
                    assertEquals(200, send(valid)); assertEquals(1, calls)
                    rejected(listOf(cookie, origin))
                    rejected(valid + csrf)
                    rejected(listOf(cookie, origin, "X-CSRF-Token" to Base64Url.encode(ByteArray(32))))
                    rejected(listOf(cookie, csrf))
                    rejected(listOf(cookie, "Origin" to "https://wrong.invalid", csrf))
                    rejected(valid + origin)
                    rejected(valid, "?X-CSRF-Token=${issued.csrfToken()}")
                    rejected(valid + ("Authorization" to "Bearer $accessToken"), expected = 401)
                    rejected(listOf("Authorization" to "Bearer $accessToken", "authorization" to "Bearer $accessToken"), expected = 401)
                    rejected(valid + cookie, expected = 400)
                    assertEquals(200, send(listOf("Authorization" to "Bearer $accessToken"))); assertEquals(2, calls)
                    // A cookie CSRF token cannot stand in for an authenticated native credential.
                    rejected(listOf(origin, csrf), expected = 401)
                    if (mode == "proxy") {
                        val before = calls
                        assertEquals(400, send(valid, forwarding = listOf("X-Forwarded-Proto" to "http", "X-Forwarded-Host" to "identity.example.invalid")))
                        assertEquals(400, send(valid, forwarding = listOf("X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "wrong.invalid")))
                        assertEquals(before, calls)
                    } else if (mode == "direct-tls") {
                        val before = calls
                        assertEquals(400, send(valid, host = "wrong.invalid", forwarding = listOf("X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "identity.example.invalid")))
                        assertEquals(before, calls)
                    }
                } finally { server.close() }
            }
        } finally { temp.toFile().deleteRecursively() }
    }
}
