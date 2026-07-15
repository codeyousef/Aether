# Testing

Aether is designed to be testable. You can write unit tests for your logic and integration tests for your full application.

## Unit Testing

Since most logic resides in `commonMain`, you can use standard `kotlin.test` assertions.

Aether apps are easy to test using standard tools like JUnit and MockK.

### Testing Handlers

You can mock the `Exchange` object to test handlers in isolation.

```kotlin
@Test
fun testHelloHandler() = runTest {
    val exchange = MockExchange(path = "/hello/world")
    val handler = { ex: Exchange -> 
        ex.respond(body = "Hello") 
    }
    
    handler(exchange)
    
    assertEquals(200, exchange.response.statusCode)
    assertEquals("Hello", exchange.response.bodyString)
}
```

## End-to-End (E2E) Testing

For integration testing, you can spin up a real server instance within your test suite.

```kotlin
@Test
fun testServer() {
    val server = VertxServer.create(port = 0) {
        it.respond("OK")
    }
    runBlocking {
        server.start()
        // Use an HTTP client to test endpoints
        server.stop()
    }
}
```

Using `testcontainers` is recommended for testing with real database backends.

## Integration Testing

Integration tests run the full server stack. Aether uses **TestContainers** to spin up dependencies like PostgreSQL.

### Setup

Add the test dependencies in `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}
```

### Writing an Integration Test

```kotlin
@Testcontainers
class UserIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
        
        @JvmStatic
        @BeforeAll
        fun setup() {
            // Configure Aether to use the container's JDBC URL
            DatabaseDriverRegistry.register(
                PostgresDriver(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password
                )
            )
        }
    }

    @Test
    fun `should create and retrieve user`() = runTest {
        // 1. Start Server (or use TestClient directly against Pipeline)
        val app = TestApplication()
        
        // 2. Perform Request
        val response = app.client.post("/users", json = """{"name": "Alice"}""")
        
        // 3. Assert
        assertEquals(201, response.statusCode)
        
        val user = Users.get(1)
        assertNotNull(user)
        assertEquals("Alice", user.name)
    }
}
```

## Wasm Testing

Wasm tests run in a headless browser (Chrome/Firefox) or Node.js.

```bash
./gradlew wasmJsBrowserTest
```

Ensure you have the necessary browsers installed or configured in Karma.

## Identity release verification

Identity protocol and middleware tests run on JVM, wasmJs and wasmWasi. Storage race tests run
against PostgreSQL 16 and the Firestore emulator. The current wasmWasi Node suite verifies guest
ABI/readiness behavior, while the native C suite verifies the OpenSSL primitives independently.
Neither is accepted as the production wasmWasi release gate: release evidence must also run a
combined component host that binds the Kotlin guest to the WIT crypto world and `wasi:http`.

```text
./gradlew verifyExpectedSourceTasks :aether-auth:jvmTest :aether-auth:wasmJsNodeTest :aether-auth:wasmWasiNodeTest
./gradlew :aether-auth-postgresql:allTests
./gradlew :aether-auth-firestore:allTests
./aether-auth-firestore/run-emulator-gate.sh
./gradlew :aether-auth-oidc:allTests :aether-auth-saml:allTests :aether-auth-scim:allTests
./gradlew :aether-auth-summon:jvmTest :aether-auth-summon:wasmJsBrowserTest :example-app:wasmJsBrowserTest
./gradlew :aether-cli:test :example-app:allTests :aether-core:allTests
./gradlew check

cmake -S aether-identity-wasi-host -B build/identity-wasi-host -DCMAKE_BUILD_TYPE=Release
cmake --build build/identity-wasi-host --config Release --parallel 2
ctest --test-dir build/identity-wasi-host --output-on-failure

npm ci --prefix e2e-tests
npm test --prefix e2e-tests
npm run typecheck --prefix e2e-tests
npm exec --prefix e2e-tests -- playwright install chromium firefox webkit
npm run test:browser --prefix e2e-tests
```

The state-changing release journey is a separate disposable-store gate. Set
`AETHER_E2E_LIVE_IDENTITY=1`, `AETHER_E2E_EPHEMERAL=1`, and
`AETHER_E2E_RECOVERY_FLOW=1`, provide the loopback release-candidate base URL and a disposable
bootstrap secret, then run `npm run test:release --prefix e2e-tests`. Never aim that command at a
persistent or shared environment. Its ignored sensitive-results directory must be deleted after
the run and must never be uploaded as CI evidence.

The Firestore gate requires `gcloud` with the `cloud-firestore-emulator` component and starts an
isolated loopback emulator itself. It uses only a project ID under the
`aether-identity-emulator-*` test prefix, flushes that project before and after the run, provisions
and verifies a unique `TEST` namespace marker, and fails if the emulator is missing or unreachable.
`aether-auth-testkit` owns one adapter-neutral `IdentityStoreConformanceSuite`; the in-memory store,
the PostgreSQL 16 integration gate, and the real Firestore emulator gate invoke that same runner.
It covers concurrent challenge consumption, WebAuthn credential uniqueness rollback, session epochs,
revocation, and sliding idle-session CAS renewal without audit flooding; last-owner protection,
recovery-code reuse and generation replacement; device-token
refresh replay and family revocation, service-credential transitions, federation-link uniqueness and
replay receipts, and retryable compare-and-set races. Adapter-specific suites retain their additional
invitation, SCIM, transport, migration, and provider failure coverage.

The combined wasmWasi component-host command is intentionally absent until that integration
exists. Do not substitute `:aether-auth:wasmWasiNodeTest` or native `ctest` for it.
The pinned Kotlin 2.3 toolchain targets WASI 0.1/Preview 1; see the
[official Kotlin/Wasm WASI documentation](https://kotlinlang.org/docs/wasm-wasi.html). Moving to a
later experimental component-model compiler would be a separate compatibility decision, not a
test-only workaround for this release gate.

Adversarial coverage includes malformed WebAuthn CBOR/COSE, wrong challenge/RP/origin/type,
signature and UP/UV failures, concurrent ceremony completion, counter anomalies, CSRF and proxy
spoofing, tenant enumeration, token replay, OIDC claim/JWKS failures, SAML signature wrapping/XXE
and SCIM retry/deprovisioning behavior.

The state-changing Playwright journey handles bootstrap, recovery, device, access, and refresh
secrets. Its `chromium-live` project therefore disables traces, screenshots, videos, and HTML
reports. It also disables automatic retries because a partial run has already mutated its disposable
store, and it never reuses an already-running local server. The project writes only to the ignored
`.live-sensitive-results/` directory, and CI deletes that directory without uploading it. Keep
secret-bearing assertions redacted; only the separate UI lane may publish `test-results/` or
`playwright-report/` as failure evidence.

Firefox and Safari hardware-passkey smoke tests are a separate manual release gate. Each browser
must enroll and use a second passkey, revoke a distinct active session, and prove that the revoked
cookie can no longer authenticate in addition to the registration, sign-in, step-up, and recovery
journeys. See
[Identity deployment](identity/deployment.md#release-verification) for the required flows and safe
evidence fields.
