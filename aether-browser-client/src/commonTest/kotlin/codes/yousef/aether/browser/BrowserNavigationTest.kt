package codes.yousef.aether.browser

import kotlin.test.Test
import kotlin.test.assertFailsWith

class BrowserNavigationTest {
    @Test
    fun `same-origin navigation accepts paths queries and fragments`() {
        listOf("/photography", "/photography#gallery", "?filter=video", "#gallery").forEach {
            requireSameOriginNavigationPath(it)
        }
    }

    @Test
    fun `same-origin navigation rejects external and protocol-relative targets`() {
        listOf("https://example.test", "//example.test/path", "example.test/path", "/bad\\path").forEach {
            assertFailsWith<IllegalArgumentException> { requireSameOriginNavigationPath(it) }
        }
    }
}
