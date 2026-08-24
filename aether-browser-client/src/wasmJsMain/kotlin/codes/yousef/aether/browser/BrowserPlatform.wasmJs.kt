@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package codes.yousef.aether.browser

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual fun createPlatformBrowserHttpTransport(): BrowserHttpTransport = WasmJsBrowserHttpTransport()

private class WasmJsBrowserHttpTransport : BrowserHttpTransport {
    private val json = Json

    override suspend fun execute(request: BrowserTransportRequest): BrowserHttpResponse =
        suspendCancellableCoroutine { continuation ->
            var requestId = -1
            val callback: (Int, String?, String?, String?, String?) -> Unit =
                callback@{ status, statusText, headersJson, body, failureCode ->
                    if (failureCode != null) {
                        val failure = when (failureCode) {
                            "timeout" -> BrowserHttpFailure.TIMEOUT
                            "response_too_large" -> BrowserHttpFailure.RESPONSE_TOO_LARGE
                            "unsupported" -> BrowserHttpFailure.UNSUPPORTED
                            else -> BrowserHttpFailure.NETWORK
                        }
                        if (!continuation.isActive) return@callback
                        continuation.resumeWithException(
                            BrowserHttpTransportException(failure, "Browser HTTP request failed: $failureCode")
                        )
                        return@callback
                    }

                    val responseHeaders = runCatching {
                        json.decodeFromString<Map<String, String>>(headersJson ?: "{}")
                    }.getOrDefault(emptyMap())
                    if (!continuation.isActive) return@callback
                    continuation.resume(
                        BrowserHttpResponse(
                            statusCode = status,
                            statusText = statusText.orEmpty(),
                            headers = responseHeaders,
                            body = body.orEmpty()
                        )
                    )
                }

            requestId = startBrowserFetch(
                path = request.path,
                method = request.method.name,
                headersJson = json.encodeToString(request.headers),
                body = request.body,
                redirect = request.redirectPolicy.fetchValue,
                timeoutMillis = request.timeoutMillis,
                maximumResponseBytes = request.maximumResponseBytes,
                callback = callback
            )
            continuation.invokeOnCancellation {
                if (requestId >= 0) cancelBrowserFetch(requestId)
            }
        }
}

@JsFun(
    """
(path, method, headersJson, body, redirect, timeoutMillis, maximumResponseBytes, callback) => {
    const registry = globalThis.__aetherBrowserClientRequests ||
        (globalThis.__aetherBrowserClientRequests = {nextId: 1, requests: new Map()});
    const id = registry.nextId++;
    if (typeof globalThis.fetch !== 'function' ||
        typeof globalThis.AbortController !== 'function' ||
        typeof globalThis.TextDecoder !== 'function') {
        callback(0, null, null, null, 'unsupported');
        return id;
    }

    const controller = new AbortController();
    const entry = {controller, timer: null, timedOut: false};
    registry.requests.set(id, entry);
    const finish = (status, statusText, headers, responseBody, failure) => {
        const current = registry.requests.get(id);
        if (!current) return;
        if (current.timer !== null) clearTimeout(current.timer);
        registry.requests.delete(id);
        callback(status, statusText, headers, responseBody, failure);
    };
    entry.timer = setTimeout(() => {
        entry.timedOut = true;
        controller.abort();
    }, timeoutMillis);

    const headers = JSON.parse(headersJson);
    const options = {
        method,
        headers,
        body: body === null ? undefined : body,
        credentials: 'same-origin',
        mode: 'same-origin',
        redirect,
        signal: controller.signal
    };

    fetch(path, options).then(async response => {
        const responseHeaders = {};
        response.headers.forEach((value, name) => { responseHeaders[name] = value; });
        const declaredLength = Number(response.headers.get('content-length'));
        if (Number.isFinite(declaredLength) && declaredLength > maximumResponseBytes) {
            controller.abort();
            finish(0, null, null, null, 'response_too_large');
            return;
        }

        const decoder = new TextDecoder('utf-8');
        let text = '';
        let total = 0;
        if (response.body && response.body.getReader) {
            const reader = response.body.getReader();
            while (true) {
                const result = await reader.read();
                if (result.done) break;
                total += result.value.byteLength;
                if (total > maximumResponseBytes) {
                    await reader.cancel('response_too_large').catch(() => undefined);
                    controller.abort();
                    finish(0, null, null, null, 'response_too_large');
                    return;
                }
                text += decoder.decode(result.value, {stream: true});
            }
            text += decoder.decode();
        } else {
            const bytes = new Uint8Array(await response.arrayBuffer());
            if (bytes.byteLength > maximumResponseBytes) {
                finish(0, null, null, null, 'response_too_large');
                return;
            }
            text = decoder.decode(bytes);
        }
        finish(response.status, response.statusText, JSON.stringify(responseHeaders), text, null);
    }).catch(() => {
        finish(0, null, null, null, entry.timedOut ? 'timeout' : 'network');
    });
    return id;
}
"""
)
private external fun startBrowserFetch(
    path: String,
    method: String,
    headersJson: String,
    body: String?,
    redirect: String,
    timeoutMillis: Int,
    maximumResponseBytes: Int,
    callback: (Int, String?, String?, String?, String?) -> Unit
): Int

@JsFun(
    """
id => {
    const registry = globalThis.__aetherBrowserClientRequests;
    const entry = registry && registry.requests.get(id);
    if (!entry) return;
    if (entry.timer !== null) clearTimeout(entry.timer);
    registry.requests.delete(id);
    entry.controller.abort();
}
"""
)
private external fun cancelBrowserFetch(requestId: Int)

@JsFun(
    """
key => {
    try {
        return globalThis.sessionStorage ? globalThis.sessionStorage.getItem(key) : null;
    } catch (_) {
        return null;
    }
}
"""
)
private external fun readSessionStorageValue(key: String): String?

internal actual fun browserSessionStorageValue(key: String): String? = readSessionStorageValue(key)

@JsFun(
    """
() => JSON.stringify({
    href: globalThis.location.href,
    origin: globalThis.location.origin,
    pathname: globalThis.location.pathname,
    search: globalThis.location.search,
    hash: globalThis.location.hash
})
"""
)
private external fun browserLocationJson(): String

internal actual fun readPlatformBrowserLocation(): BrowserLocationSnapshot =
    Json.decodeFromString(browserLocationJson())

@JsFun("path => globalThis.history.pushState(null, '', path)")
private external fun pushHistory(path: String)

internal actual fun pushPlatformBrowserHistory(path: String) = pushHistory(path)

@JsFun("path => globalThis.history.replaceState(null, '', path)")
private external fun replaceHistory(path: String)

internal actual fun replacePlatformBrowserHistory(path: String) = replaceHistory(path)

@JsFun("delta => globalThis.history.go(delta)")
private external fun goHistory(delta: Int)

internal actual fun goPlatformBrowserHistory(delta: Int) = goHistory(delta)

@JsFun("id => globalThis.document ? globalThis.document.getElementById(id)?.textContent ?? null : null")
private external fun bootstrapText(elementId: String): String?

internal actual fun readPlatformBootstrapText(elementId: String): String? = bootstrapText(elementId)

@JsFun("value => globalThis.atob(value)")
private external fun decodeBase64(value: String): String

internal actual fun decodePlatformBase64Utf8(value: String): String {
    val binary = decodeBase64(value)
    return ByteArray(binary.length) { index -> binary[index].code.toByte() }
        .decodeToString(throwOnInvalidSequence = true)
}
