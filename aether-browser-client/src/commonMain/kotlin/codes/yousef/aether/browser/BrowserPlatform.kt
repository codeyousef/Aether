package codes.yousef.aether.browser

internal interface BrowserHttpTransport {
    suspend fun execute(request: BrowserTransportRequest): BrowserHttpResponse
}

internal data class BrowserTransportRequest(
    val method: BrowserHttpMethod,
    val path: String,
    val headers: Map<String, String>,
    val body: String?,
    val redirectPolicy: BrowserRedirectPolicy,
    val timeoutMillis: Int,
    val maximumResponseBytes: Int
)

internal expect fun createPlatformBrowserHttpTransport(): BrowserHttpTransport

internal expect fun browserSessionStorageValue(key: String): String?

internal expect fun readPlatformBrowserLocation(): BrowserLocationSnapshot

internal expect fun pushPlatformBrowserHistory(path: String)

internal expect fun replacePlatformBrowserHistory(path: String)

internal expect fun goPlatformBrowserHistory(delta: Int)

internal expect fun readPlatformBootstrapText(elementId: String): String?

internal expect fun decodePlatformBase64Utf8(value: String): String
