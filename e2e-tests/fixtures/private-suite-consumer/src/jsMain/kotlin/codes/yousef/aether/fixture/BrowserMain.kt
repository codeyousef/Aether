package codes.yousef.aether.fixture

import codes.yousef.aether.browser.BrowserHttpClient
import codes.yousef.aether.browser.BrowserHttpClientConfig

fun main() {
    BrowserHttpClient(
        BrowserHttpClientConfig(
            maximumRequestBytes = 1_048_576,
            maximumResponseBytes = 1_048_576
        )
    )
}
