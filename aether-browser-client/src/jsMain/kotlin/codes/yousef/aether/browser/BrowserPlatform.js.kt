package codes.yousef.aether.browser

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeout
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise

internal actual fun createPlatformBrowserHttpTransport(): BrowserHttpTransport = JsBrowserHttpTransport()

private class JsBrowserHttpTransport : BrowserHttpTransport {
    override suspend fun execute(request: BrowserTransportRequest): BrowserHttpResponse {
        val abortController = js("new AbortController()")
        val headers = Headers()
        request.headers.forEach { (name, value) -> headers.set(name, value) }
        val init = RequestInit(
            method = request.method.name,
            headers = headers,
            body = request.body
        )
        init.asDynamic().credentials = "same-origin"
        init.asDynamic().mode = "same-origin"
        init.asDynamic().redirect = request.redirectPolicy.fetchValue
        init.asDynamic().signal = abortController.signal

        return try {
            withTimeout(request.timeoutMillis.toLong()) {
                val response = window.fetch(request.path, init).await()
                val responseHeaders = linkedMapOf<String, String>()
                response.headers.asDynamic().forEach { value: String, name: String ->
                    responseHeaders[name] = value
                }
                BrowserHttpResponse(
                    statusCode = response.status.toInt(),
                    statusText = response.statusText,
                    headers = responseHeaders,
                    body = readBoundedResponseBody(response, request.maximumResponseBytes)
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            abortController.abort()
            throw BrowserHttpTransportException(
                BrowserHttpFailure.TIMEOUT,
                "Browser HTTP request timed out",
                timeout
            )
        } catch (cancelled: CancellationException) {
            abortController.abort()
            throw cancelled
        } catch (failure: BrowserHttpTransportException) {
            abortController.abort()
            throw failure
        } catch (failure: Throwable) {
            abortController.abort()
            throw BrowserHttpTransportException(
                BrowserHttpFailure.NETWORK,
                "Browser HTTP request failed",
                failure
            )
        }
    }
}

private suspend fun readBoundedResponseBody(response: Response, maximumBytes: Int): String {
    val declaredBytes = response.headers.get("content-length")?.toLongOrNull()
    if (declaredBytes != null && declaredBytes > maximumBytes) {
        throw BrowserHttpTransportException(
            BrowserHttpFailure.RESPONSE_TOO_LARGE,
            "Browser HTTP response exceeded its configured hard limit"
        )
    }

    val stream = response.asDynamic().body
    if (stream == null || stream.getReader == null) {
        val buffer = response.arrayBuffer().await()
        val size = (buffer.asDynamic().byteLength as Number).toInt()
        if (size > maximumBytes) {
            throw BrowserHttpTransportException(
                BrowserHttpFailure.RESPONSE_TOO_LARGE,
                "Browser HTTP response exceeded its configured hard limit"
            )
        }
        val decoder = js("new TextDecoder('utf-8')")
        val bytes = js("new Uint8Array(buffer)")
        return decoder.decode(bytes) as String
    }

    val reader = stream.getReader()
    val decoder = js("new TextDecoder('utf-8')")
    val streamOptions = js("({ stream: true })")
    val output = StringBuilder()
    var totalBytes = 0
    while (true) {
        val result = reader.read().unsafeCast<Promise<dynamic>>().await()
        if (result.done as Boolean) break
        val chunk = result.value
        totalBytes += (chunk.byteLength as Number).toInt()
        if (totalBytes > maximumBytes) {
            reader.cancel("response_too_large")
            throw BrowserHttpTransportException(
                BrowserHttpFailure.RESPONSE_TOO_LARGE,
                "Browser HTTP response exceeded its configured hard limit"
            )
        }
        output.append(decoder.decode(chunk, streamOptions) as String)
    }
    output.append(decoder.decode() as String)
    return output.toString()
}

internal actual fun browserSessionStorageValue(key: String): String? =
    runCatching { window.sessionStorage.getItem(key) }.getOrNull()

internal actual fun readPlatformBrowserLocation(): BrowserLocationSnapshot = BrowserLocationSnapshot(
    href = window.location.href,
    origin = window.location.origin,
    pathname = window.location.pathname,
    search = window.location.search,
    hash = window.location.hash
)

internal actual fun pushPlatformBrowserHistory(path: String) {
    window.history.pushState(null, "", path)
}

internal actual fun replacePlatformBrowserHistory(path: String) {
    window.history.replaceState(null, "", path)
}

internal actual fun goPlatformBrowserHistory(delta: Int) {
    window.history.go(delta)
}

internal actual fun readPlatformBootstrapText(elementId: String): String? =
    document.getElementById(elementId)?.textContent

internal actual fun decodePlatformBase64Utf8(value: String): String {
    val binary = window.atob(value)
    return ByteArray(binary.length) { index -> binary[index].code.toByte() }
        .decodeToString(throwOnInvalidSequence = true)
}
