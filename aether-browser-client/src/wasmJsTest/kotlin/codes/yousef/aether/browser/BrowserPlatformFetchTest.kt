package codes.yousef.aether.browser

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserPlatformFetchTest {
    @Test
    fun `platform transport fetches a same-origin Karma resource`() = runTest {
        if (!hasBrowserRuntime()) return@runTest
        val response = BrowserHttpClient().execute(BrowserHttpMethod.GET, "/context.json")

        assertEquals(200, response.statusCode)
        assertTrue(response.body.startsWith("{"))
    }

    @Test
    fun `platform fetch receives checked method credentials headers body and redirect policy`() = runTest {
        if (!hasBrowserRuntime()) return@runTest
        installFetchCapture()
        try {
            val client = BrowserHttpClient(
                BrowserHttpClientConfig(
                    csrfProvider = BrowserCsrfProvider.fixed("csrf-value"),
                    redirectPolicy = BrowserRedirectPolicy.ERROR
                )
            )
            val response = client.execute(
                BrowserHttpMethod.PATCH,
                "/api/object",
                headers = mapOf("X-Request" to "fixture"),
                body = """{"value":1}"""
            )

            assertEquals("PATCH", capturedProperty("method"))
            assertEquals("same-origin", capturedProperty("credentials"))
            assertEquals("same-origin", capturedProperty("mode"))
            assertEquals("error", capturedProperty("redirect"))
            assertEquals("fixture", capturedHeader("X-Request"))
            assertEquals("csrf-value", capturedHeader("X-CSRF-Token"))
            assertEquals("""{"value":1}""", capturedProperty("body"))
            assertEquals("fixture-tag", response.etag)
        } finally {
            restoreFetchCapture()
        }
    }
}

@JsFun("""() => typeof window !== 'undefined' && typeof globalThis.fetch === 'function'""")
private external fun hasBrowserRuntime(): Boolean

@JsFun(
    """() => {
        globalThis.__aetherOriginalFetch = globalThis.fetch;
        globalThis.fetch = (_path, init) => {
            globalThis.__aetherFetchCapture = init;
            return Promise.resolve(new Response('{}', {
                status: 200,
                headers: { 'ETag': 'fixture-tag' }
            }));
        };
    }"""
)
private external fun installFetchCapture()

@JsFun("""name => String(globalThis.__aetherFetchCapture[name])""")
private external fun capturedProperty(name: String): String

@JsFun(
    """name => {
        const headers = globalThis.__aetherFetchCapture.headers;
        if (typeof headers.get === 'function') return headers.get(name);
        const key = Object.keys(headers).find(candidate => candidate.toLowerCase() === name.toLowerCase());
        return key === undefined ? null : headers[key];
    }"""
)
private external fun capturedHeader(name: String): String?

@JsFun(
    """() => {
        globalThis.fetch = globalThis.__aetherOriginalFetch;
        delete globalThis.__aetherOriginalFetch;
        delete globalThis.__aetherFetchCapture;
    }"""
)
private external fun restoreFetchCapture()
