package codes.yousef.aether.browser

import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserPlatformFetchTest {
    @Test
    fun `platform transport fetches a same-origin Karma resource`(): Promise<Unit> = MainScope().promise {
        if (!hasBrowserRuntime()) return@promise
        val response = BrowserHttpClient().execute(BrowserHttpMethod.GET, "/context.json")

        assertEquals(200, response.statusCode)
        assertTrue(response.body.startsWith("{"))
    }

    @Test
    fun `platform fetch receives checked method credentials headers body and redirect policy`(): Promise<Unit> =
        MainScope().promise {
            if (!hasBrowserRuntime()) return@promise
            val browser = window.asDynamic()
            val originalFetch = browser.fetch
            var captured: dynamic = null
            browser.fetch = { _: String, init: dynamic ->
                captured = init
                js("Promise.resolve(new Response('{}', { status: 200, headers: { 'ETag': 'fixture-tag' } }))")
            }
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

                assertEquals("PATCH", captured.method)
                assertEquals("same-origin", captured.credentials)
                assertEquals("same-origin", captured.mode)
                assertEquals("error", captured.redirect)
                assertEquals("fixture", captured.headers.get("X-Request"))
                assertEquals("csrf-value", captured.headers.get("X-CSRF-Token"))
                assertEquals("""{"value":1}""", captured.body)
                assertEquals("fixture-tag", response.etag)
            } finally {
                browser.fetch = originalFetch
            }
        }
}

private fun hasBrowserRuntime(): Boolean =
    js("typeof window !== 'undefined' && typeof window.fetch === 'function'") as Boolean
