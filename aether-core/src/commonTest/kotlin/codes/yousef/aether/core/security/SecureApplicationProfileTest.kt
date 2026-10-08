package codes.yousef.aether.core.security

import codes.yousef.aether.core.*
import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.core.middleware.InMemoryQuotaProvider
import codes.yousef.aether.core.middleware.RateLimitConfig
import codes.yousef.aether.core.middleware.RateLimitMiddleware
import codes.yousef.aether.core.middleware.RateLimitNamespace
import codes.yousef.aether.core.middleware.serverResolvedRateLimitKey
import codes.yousef.aether.core.proxy.ProxyConfig
import codes.yousef.aether.core.proxy.buildProxyHeaders
import codes.yousef.aether.core.proxy.proxyRequest
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureApplicationProfileTest {
    @Test
    fun `secure profile fixes entry and finally order`() = runTest {
        val events = mutableListOf<String>()
        fun stage(name: String): Middleware = { _, next ->
            events += "$name:before"
            try { next() } finally { events += "$name:after" }
        }
        val pipeline = secureApplicationPipeline(
            SecureApplicationPipelineProfile(
                stage("identity"), stage("validation"), stage("recovery"),
                stage("authentication"), stage("csrf"), stage("grant"),
                stage("authorization"), stage("idempotency"), stage("audit")
            )
        )

        pipeline.execute(SecurityTestExchange()) { events += "use-case" }

        assertEquals(
            listOf(
                "identity:before", "validation:before", "recovery:before", "authentication:before",
                "csrf:before", "grant:before", "authorization:before", "idempotency:before",
                "audit:before", "use-case", "audit:after", "idempotency:after",
                "authorization:after", "grant:after", "csrf:after", "authentication:after",
                "recovery:after", "validation:after", "identity:after"
            ),
            events
        )
    }

    @Test
    fun `safe boundary encloses authentication failures`() = runTest {
        val events = mutableListOf<String>()
        val pass: Middleware = { _, next -> next() }
        val recovery: Middleware = { _, next ->
            try { next() } catch (_: IllegalStateException) { events += "recovered" }
        }
        val authentication: Middleware = { _, _ ->
            events += "authentication"
            error("private provider detail")
        }
        val pipeline = secureApplicationPipeline(
            SecureApplicationPipelineProfile(
                pass, pass, recovery, authentication, pass, pass, pass, pass, pass
            )
        )

        pipeline.execute(SecurityTestExchange()) { error("must not reach use case") }

        assertEquals(listOf("authentication", "recovered"), events)
    }

    @Test
    fun `exact CORS rejects hostile actual and preflight requests before mutation`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            ExactCorsConfig(setOf("*"), setOf(HttpMethod.GET))
        }
        assertFailsWith<IllegalArgumentException> {
            ExactCorsConfig(setOf("https://app.example.com/path"), setOf(HttpMethod.GET))
        }
        val cors = ExactCorsMiddleware(
            ExactCorsConfig(
                allowedOrigins = setOf("https://app.example.com"),
                allowedMethods = setOf(HttpMethod.GET, HttpMethod.POST),
                allowedHeaders = setOf("Content-Type", "X-CSRF-Token")
            )
        ).asMiddleware()
        var mutations = 0
        val missingOrigin = SecurityTestExchange(request = SecurityTestRequest(method = HttpMethod.POST))
        cors(missingOrigin) { mutations++ }
        assertEquals(403, missingOrigin.response.statusCode)

        val hostile = SecurityTestExchange(
            request = SecurityTestRequest(
                method = HttpMethod.POST,
                headers = Headers.of("Origin" to "https://attacker.example")
            )
        )
        cors(hostile) { mutations++ }
        assertEquals(403, hostile.response.statusCode)

        val duplicateOrigin = SecurityTestExchange(
            request = SecurityTestRequest(
                method = HttpMethod.POST,
                headers = Headers.of(
                    "Origin" to "https://app.example.com",
                    "Origin" to "https://app.example.com"
                )
            )
        )
        cors(duplicateOrigin) { mutations++ }
        assertEquals(403, duplicateOrigin.response.statusCode)

        val hostilePreflight = SecurityTestExchange(
            request = SecurityTestRequest(
                method = HttpMethod.OPTIONS,
                headers = Headers.of(
                    "Origin" to "https://app.example.com",
                    "Access-Control-Request-Method" to "POST",
                    "Access-Control-Request-Headers" to "X-Actor-Id"
                )
            )
        )
        cors(hostilePreflight) { mutations++ }

        assertEquals(403, hostilePreflight.response.statusCode)
        assertEquals(0, mutations)

        val allowed = SecurityTestExchange(
            request = SecurityTestRequest(
                method = HttpMethod.OPTIONS,
                headers = Headers.of(
                    "Origin" to "https://app.example.com",
                    "Access-Control-Request-Method" to "POST",
                    "Access-Control-Request-Headers" to "Content-Type, X-CSRF-Token"
                )
            )
        )
        cors(allowed) { mutations++ }
        assertEquals(204, allowed.response.statusCode)
        assertEquals("https://app.example.com", allowed.response.headers.build()["Access-Control-Allow-Origin"])
        assertEquals(0, mutations)
    }

    @Test
    fun `resolved policy ignores forged request claims and feature keys are server selected`() = runTest {
        data class Actor(val id: String)
        data class Resource(val tenant: String, val grantedEpoch: Long = 1, val expiresAt: Long? = null)
        val actorKey = AttributeKey("test.actor", Actor::class)
        val resourceKey = AttributeKey("test.resource", Resource::class)
        val exchange = SecurityTestExchange(
            request = SecurityTestRequest(
                method = HttpMethod.POST,
                query = "actor=attacker&tenant=other",
                body = "{\"actor\":\"attacker\",\"tenant\":\"other\"}".encodeToByteArray()
            )
        )
        exchange.attributes.put(actorKey, Actor("server-user"))
        exchange.attributes.put(resourceKey, Resource("server-tenant"))
        var observed: ResolvedAuthorizationInput<Actor, Resource, TestAction>? = null
        val policy = ResolvedAuthorizationPolicy<Actor, Resource, TestAction> { input ->
            observed = input
            AuthorizationDecision.Allow
        }
        var called = false

        requireResolvedAuthorization(
            actorKey,
            resourceKey,
            TestAction.WRITE,
            policy,
            timeSource = AuthorizationTimeSource { 1_234L }
        )(exchange) { called = true }

        assertTrue(called)
        assertEquals("server-user", observed?.actor?.id)
        assertEquals("server-tenant", observed?.resource?.tenant)
        assertEquals(TestAction.WRITE, observed?.action)
        assertEquals(1_234L, observed?.evaluatedAtEpochMilliseconds)

        var freshnessPolicyCalls = 0
        val currentPolicy = ResolvedAuthorizationPolicy<Actor, Resource, TestAction> {
            freshnessPolicyCalls++
            AuthorizationDecision.Allow
        }
        val freshness = AuthorizationValidityResolver<Resource> {
            AuthorizationValidity(it.grantedEpoch, currentEpoch = 2, it.expiresAt)
        }
        val stale = SecurityTestExchange()
        stale.attributes.put(actorKey, Actor("server-user"))
        stale.attributes.put(resourceKey, Resource("server-tenant", grantedEpoch = 1))
        requireCurrentResolvedAuthorization(
            actorKey,
            resourceKey,
            TestAction.WRITE,
            freshness,
            currentPolicy,
            timeSource = AuthorizationTimeSource { 1_234L }
        )(stale) { error("stale grant must not mutate") }
        assertEquals(403, stale.response.statusCode)
        assertTrue(stale.response.bodyText().contains("stale_grant"))

        val expired = SecurityTestExchange()
        expired.attributes.put(actorKey, Actor("server-user"))
        expired.attributes.put(resourceKey, Resource("server-tenant", grantedEpoch = 2, expiresAt = 1_234L))
        requireCurrentResolvedAuthorization(
            actorKey,
            resourceKey,
            TestAction.WRITE,
            freshness,
            currentPolicy,
            timeSource = AuthorizationTimeSource { 1_234L }
        )(expired) { error("expired grant must not mutate") }
        assertEquals(403, expired.response.statusCode)
        assertTrue(expired.response.bodyText().contains("expired"))
        assertEquals(0, freshnessPolicyCalls)

        val feature = FeatureId("vault.write")
        var observedFeature: FeatureId? = null
        val disabled = requireFeature(feature, FeatureStateProvider {
            observedFeature = it
            false
        })
        disabled(exchange) { error("disabled feature must not mutate") }
        assertEquals(feature, observedFeature)
        assertEquals(404, exchange.response.statusCode)
        assertTrue(exchange.response.bodyText().contains("feature_disabled"))
    }

    @Test
    fun `private headers reject unsafe sources and cover redirects and errors`() = runTest {
        assertFailsWith<IllegalArgumentException> { CspDynamicSource.nonce("unsafe-inline") }
        val middleware = PrivateSecurityHeaders(
            PrivateSecurityHeadersConfig(
                scriptSources = PrivateCspSourceProvider {
                    setOf(CspDynamicSource.nonce("YWV0aGVyLW5vbmNl"))
                }
            )
        ).asMiddleware()
        val redirect = SecurityTestExchange()
        middleware(redirect) { redirect.response.statusCode = 302 }
        assertPrivateHeaders(redirect.response)

        val failed = SecurityTestExchange()
        try {
            middleware(failed) { error("synthetic") }
            error("Expected middleware failure")
        } catch (_: IllegalStateException) {
            // Headers are installed before the protected pipeline executes.
        }
        assertPrivateHeaders(failed.response)

        val publicAsset = SecurityTestExchange()
        PublicCacheHeaders(
            PublicCacheHeadersConfig(maxAgeSeconds = 60, sharedMaxAgeSeconds = 120, immutable = true)
        ).asMiddleware()(publicAsset) { publicAsset.response.statusCode = 200 }
        assertEquals(
            "public, max-age=60, s-maxage=120, immutable",
            publicAsset.response.headers.build()["Cache-Control"]
        )
    }

    @Test
    fun `rate limit uses trusted bounded keys and emits positive retry delay`() = runTest {
        data class Subject(val id: String)
        val subjectKey = AttributeKey("test.rate-subject", Subject::class)
        val provider = InMemoryQuotaProvider(limit = 2, windowMillis = 60_000, maximumBuckets = 2)
        val middleware = RateLimitMiddleware(
            RateLimitConfig(
                keyExtractor = serverResolvedRateLimitKey(
                    RateLimitNamespace("private.write"),
                    subjectKey,
                    Subject::id
                ),
                requireKey = true,
                quotaProvider = provider
            )
        ).asMiddleware()
        fun request(): SecurityTestExchange = SecurityTestExchange(
            request = SecurityTestRequest(query = "quota_namespace=attacker")
        ).also { it.attributes.put(subjectKey, Subject("server-user")) }
        var calls = 0

        middleware(request()) { calls++ }
        middleware(request()) { calls++ }
        val rejected = request()
        middleware(rejected) { calls++ }

        assertEquals(2, calls)
        assertEquals(429, rejected.response.statusCode)
        assertTrue(requireNotNull(rejected.response.headers.build()["Retry-After"]).toLong() >= 1)

        val missing = SecurityTestExchange()
        middleware(missing) { error("missing trusted key must not mutate") }
        assertEquals(400, missing.response.statusCode)

        val bounded = InMemoryQuotaProvider(limit = 2, windowMillis = 60_000, maximumBuckets = 1)
        assertFalse(bounded.recordUsage("first").isExceeded)
        assertTrue(bounded.recordUsage("second").isExceeded)

        val concurrent = InMemoryQuotaProvider(limit = 25, windowMillis = 60_000, maximumBuckets = 1)
        val allowed = atomic(0)
        coroutineScope {
            List(100) {
                async(Dispatchers.Default) {
                    if (!concurrent.recordUsage("shared").isExceeded) allowed.incrementAndGet()
                }
            }.awaitAll()
        }
        assertEquals(25, allowed.value)
    }

    @Test
    fun `authorization revocation cache bounds staleness size and epoch transitions`() {
        var now = 1_000L
        assertFailsWith<IllegalArgumentException> {
            BoundedAuthorizationRevocationCache<String, String>(1, 15_001)
        }
        val cache = BoundedAuthorizationRevocationCache<String, String>(
            maximumEntries = 2,
            ttlMillis = 15_000,
            timeSource = AuthorizationTimeSource { now }
        )
        cache.put("a", epoch = 1, value = "allowed-a")
        cache.put("b", epoch = 1, value = "allowed-b")
        cache.put("c", epoch = 1, value = "allowed-c")
        assertEquals(2, cache.size)
        assertEquals(null, cache.get("a", observedEpoch = 1))
        assertEquals("allowed-b", cache.get("b", observedEpoch = 1))

        cache.invalidateIfEpochChanged("b", observedEpoch = 2)
        assertEquals(null, cache.get("b", observedEpoch = 2))

        now += 15_000
        assertEquals(null, cache.get("c", observedEpoch = 1))
    }

    @Test
    fun `private object proxy cannot restore browser credentials per request`() {
        val exchange = SecurityTestExchange(
            request = SecurityTestRequest(
                headers = Headers.of(
                    "Authorization" to "Bearer browser-session",
                    "Cookie" to "session=secret",
                    "X-CSRF-Token" to "csrf-secret",
                    "X-Object-Id" to "object-1"
                )
            )
        )
        val requestConfig = proxyRequest {
            header("Authorization", "Bearer attempted-override")
            header("Cookie", "session=attempted-override")
        }

        val forwarded = buildProxyHeaders(exchange, ProxyConfig.PrivateObjectTransfer, requestConfig)

        assertFalse(forwarded.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertFalse(forwarded.keys.any { it.equals("Cookie", ignoreCase = true) })
        assertFalse(forwarded.keys.any { it.equals("X-CSRF-Token", ignoreCase = true) })
        assertEquals("object-1", forwarded["X-Object-Id"])
    }

    @Test
    fun `route proxy can replace a removed inbound authorization header`() {
        val exchange = SecurityTestExchange(
            request = SecurityTestRequest(
                headers = Headers.of("Authorization" to "Bearer browser-session")
            )
        )
        val requestConfig = proxyRequest {
            bearerToken("service-api-key")
        }

        val forwarded = buildProxyHeaders(exchange, ProxyConfig(), requestConfig)

        assertEquals("Bearer service-api-key", forwarded["Authorization"])
    }

    private fun assertPrivateHeaders(response: SecurityTestResponse) {
        val headers = response.headers.build()
        assertEquals("no-store", headers["Cache-Control"])
        assertEquals("DENY", headers["X-Frame-Options"])
        val csp = headers["Content-Security-Policy"].orEmpty()
        assertTrue(csp.contains("form-action 'self'"))
        assertTrue(csp.contains("frame-ancestors 'none'"))
        assertTrue(csp.contains("base-uri 'none'"))
        assertTrue(csp.contains("object-src 'none'"))
        assertFalse(csp.contains("unsafe-inline"))
        assertFalse(csp.contains("unsafe-eval"))
    }
}

private enum class TestAction { WRITE }

private class SecurityTestRequest(
    override val method: HttpMethod = HttpMethod.GET,
    override val path: String = "/private",
    override val query: String? = null,
    override val headers: Headers = Headers.Empty,
    override val cookies: Cookies = Cookies.Empty,
    private val body: ByteArray = ByteArray(0)
) : Request {
    override val uri: String = if (query == null) path else "$path?$query"
    override suspend fun bodyBytes(): ByteArray = body
}

private class SecurityTestResponse : Response {
    override var statusCode: Int = 200
    override var statusMessage: String? = null
    override val headers = Headers.HeadersBuilder()
    override val cookies = mutableListOf<Cookie>()
    private val body = mutableListOf<Byte>()
    override suspend fun write(data: ByteArray) { body += data.toList() }
    override suspend fun end() = Unit
    fun bodyText(): String = body.toByteArray().decodeToString()
}

private class SecurityTestExchange(
    override val request: Request = SecurityTestRequest(),
    override val response: SecurityTestResponse = SecurityTestResponse(),
    override val attributes: Attributes = Attributes()
) : Exchange
