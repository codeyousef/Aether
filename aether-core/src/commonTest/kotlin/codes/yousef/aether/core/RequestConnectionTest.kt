package codes.yousef.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestConnectionTest {
    @Test
    fun `untrusted peer cannot spoof forwarded connection metadata`() {
        val request = TestRequest(
            headers = Headers.of(
                "X-Forwarded-For" to "198.51.100.7",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "identity.example.com"
            ),
            connection = RequestConnection(
                scheme = "http",
                host = "internal:8080",
                peerAddress = "203.0.113.9"
            )
        )

        val resolved = TrustedProxyResolver(setOf("10.0.0.5")).resolve(request)

        assertEquals("http", resolved.scheme)
        assertEquals("internal:8080", resolved.host)
        assertEquals("203.0.113.9", resolved.clientAddress)
        assertFalse(resolved.usedForwardedHeaders)
    }

    @Test
    fun `allowlisted proxy resolves the first untrusted hop from the right`() {
        val request = TestRequest(
            headers = Headers.of(
                "X-Forwarded-For" to "spoofed.invalid, 198.51.100.7, 10.0.0.4",
                "X-Forwarded-Proto" to "http, https",
                "X-Forwarded-Host" to "attacker.invalid, identity.example.com"
            ),
            connection = RequestConnection(
                scheme = "http",
                host = "internal:8080",
                peerAddress = "10.0.0.5"
            )
        )

        val resolved = TrustedProxyResolver(setOf("10.0.0.4", "10.0.0.5")).resolve(request)

        assertEquals("https", resolved.scheme)
        assertEquals("identity.example.com", resolved.host)
        assertEquals("198.51.100.7", resolved.clientAddress)
        assertEquals("https://identity.example.com", resolved.origin)
        assertTrue(resolved.usedForwardedHeaders)
    }

    @Test
    fun `custom request implementations remain compatible without metadata`() {
        val request = object : Request {
            override val method = HttpMethod.GET
            override val uri = "/"
            override val path = "/"
            override val query: String? = null
            override val headers = Headers.Empty
            override val cookies = Cookies.Empty
            override suspend fun bodyBytes(): ByteArray = ByteArray(0)
        }

        assertEquals(RequestConnection(), request.connection)
    }

    @Test
    fun `trusted proxy CIDRs match IP peers without trusting adjacent networks`() {
        val trusted = TestRequest(
            headers = Headers.of("X-Forwarded-For" to "198.51.100.9"),
            connection = RequestConnection(peerAddress = "10.22.4.5")
        )
        val untrusted = TestRequest(
            headers = Headers.of("X-Forwarded-For" to "198.51.100.9"),
            connection = RequestConnection(peerAddress = "11.22.4.5")
        )
        val resolver = TrustedProxyResolver(setOf("10.0.0.0/8", "2001:db8::/32"))

        assertEquals("198.51.100.9", resolver.resolve(trusted).clientAddress)
        assertTrue(resolver.resolve(trusted).usedForwardedHeaders)
        assertEquals("11.22.4.5", resolver.resolve(untrusted).clientAddress)
        assertFalse(resolver.resolve(untrusted).usedForwardedHeaders)
    }
}

private class TestRequest(
    override val method: HttpMethod = HttpMethod.GET,
    override val uri: String = "/",
    override val path: String = "/",
    override val query: String? = null,
    override val headers: Headers = Headers.Empty,
    override val cookies: Cookies = Cookies.Empty,
    override val connection: RequestConnection = RequestConnection(),
    private val body: ByteArray = ByteArray(0)
) : Request {
    override suspend fun bodyBytes(): ByteArray = body
}
