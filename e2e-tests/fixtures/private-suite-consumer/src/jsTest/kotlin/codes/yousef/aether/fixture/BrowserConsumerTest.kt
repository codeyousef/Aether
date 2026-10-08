package codes.yousef.aether.fixture

import codes.yousef.aether.browser.BrowserHttpClientConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserConsumerTest {
    @Test
    fun compilesAndUsesPinnedAetherBrowserSurface() {
        val config = BrowserHttpClientConfig(
            timeoutMillis = 5_000,
            maximumRequestBytes = 1_048_576,
            maximumResponseBytes = 1_048_576
        )
        assertEquals(5_000, config.timeoutMillis)
        assertEquals(1_048_576, config.maximumRequestBytes)
    }
}
