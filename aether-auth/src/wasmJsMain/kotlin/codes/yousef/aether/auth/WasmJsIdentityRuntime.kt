@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package codes.yousef.aether.auth

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object WasmJsIdentityClock : IdentityClock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(webNowMillis().toLong())
}

object WasmJsIdentitySecureRandom : IdentitySecureRandom {
    override fun nextBytes(size: Int): ByteArray {
        require(size in 1..1_048_576) { "Random byte request must be between 1 byte and 1 MiB" }
        return decodeHex(webRandomHex(size))
    }
}

class WasmJsIdentityCrypto : IdentityCrypto {
    override val capabilities: Set<IdentityCryptoCapability> = IdentityCryptoCapability.entries.toSet()

    override suspend fun providerSelfTest(): Boolean = webCryptoAvailable()

    override suspend fun sha256(input: ByteArray): ByteArray =
        decodeHex(awaitWebCrypto { callback -> webSha256Hex(input.encodeHex(), callback) })

    override suspend fun hmacSha256(key: IdentitySecret, input: ByteArray): ByteArray =
        key.useBytes { bytes ->
            decodeHex(awaitWebCrypto { callback ->
                webHmacSha256Hex(bytes.encodeHex(), input.encodeHex(), callback)
            })
        }

    override suspend fun validateP256PublicKey(publicKey: P256PublicKey): Boolean =
        awaitWebCrypto { callback ->
            webValidateP256PublicKey(publicKey.copyBytes().encodeHex(), callback)
        } == "true"

    override suspend fun verifyEs256(
        publicKey: P256PublicKey,
        signedData: ByteArray,
        signature: Es256Signature
    ): Boolean = awaitWebCrypto { callback ->
        webVerifyEs256(
            publicKey.copyBytes().encodeHex(),
            signedData.encodeHex(),
            signature.copyBytes().encodeHex(),
            callback
        )
    } == "true"

    override suspend fun verifyRsaSha256(
        publicKey: RsaPublicKey,
        signedData: ByteArray,
        signature: RsaSha256Signature
    ): Boolean = awaitWebCrypto { callback ->
        webVerifyRsaSha256(
            publicKey.copyBytes().encodeHex(),
            signedData.encodeHex(),
            signature.copyBytes().encodeHex(),
            callback
        )
    } == "true"

    override suspend fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        var difference = left.size xor right.size
        val length = maxOf(left.size, right.size)
        for (index in 0 until length) {
            val leftByte = if (index < left.size) left[index].toInt() else 0
            val rightByte = if (index < right.size) right[index].toInt() else 0
            difference = difference or (leftByte xor rightByte)
        }
        return difference == 0
    }
}

class WasmJsIdentityHttpClient : IdentityHttpClient {
    private val json = Json

    override suspend fun providerSelfTest(): Boolean = webFetchAvailable()

    override suspend fun execute(request: IdentityHttpRequest): IdentityHttpResponse =
        suspendCoroutine { continuation ->
            webFetch(
                url = request.url,
                method = request.method.name,
                headersJson = json.encodeToString(request.headers),
                bodyHex = request.bodyBytes().encodeHex(),
                maximumResponseBytes = request.maximumResponseBytes,
                callback = { status, headers, body, error ->
                    if (error != null) {
                        continuation.resumeWithException(IllegalStateException("Identity HTTP request failed"))
                    } else {
                        val responseHeaders = runCatching {
                            json.decodeFromString<Map<String, String>>(headers ?: "{}")
                        }.getOrDefault(emptyMap())
                        continuation.resume(
                            IdentityHttpResponse(
                                statusCode = status,
                                headers = responseHeaders,
                                body = decodeHex(body ?: "")
                            )
                        )
                    }
                }
            )
        }
}

@JsFun("() => typeof globalThis.fetch === 'function'")
private external fun webFetchAvailable(): Boolean

@JsFun("() => !!(globalThis.crypto && globalThis.crypto.subtle && typeof globalThis.crypto.getRandomValues === 'function')")
private external fun webCryptoAvailable(): Boolean

fun wasmJsIdentityRuntime(
    secrets: IdentitySecretResolver,
    http: IdentityHttpClient = WasmJsIdentityHttpClient()
): IdentityRuntime = IdentityRuntime(
    clock = WasmJsIdentityClock,
    secureRandom = WasmJsIdentitySecureRandom,
    crypto = WasmJsIdentityCrypto(),
    http = http,
    secrets = secrets
)

private suspend fun awaitWebCrypto(start: (((String?, String?) -> Unit)) -> Unit): String =
    suspendCoroutine { continuation ->
        start { value, error ->
            if (error != null || value == null) {
                continuation.resumeWithException(IllegalStateException("WebCrypto operation failed"))
            } else {
                continuation.resume(value)
            }
        }
    }

private fun ByteArray.encodeHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun decodeHex(value: String): ByteArray {
    require(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        "Invalid host byte encoding"
    }
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

@JsFun("() => Date.now()")
private external fun webNowMillis(): Double

@JsFun("""
(size) => {
    const cryptoApi = globalThis.crypto;
    if (!cryptoApi || !cryptoApi.getRandomValues) throw new Error('WebCrypto unavailable');
    const bytes = new Uint8Array(size);
    for (let offset = 0; offset < bytes.length; offset += 65536) {
        cryptoApi.getRandomValues(bytes.subarray(offset, Math.min(offset + 65536, bytes.length)));
    }
    return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
}
""")
private external fun webRandomHex(size: Int): String

@JsFun("""
(inputHex, callback) => {
    const fromHex = h => new Uint8Array(h.match(/.{2}/g)?.map(x => parseInt(x, 16)) || []);
    const toHex = b => Array.from(new Uint8Array(b), x => x.toString(16).padStart(2, '0')).join('');
    globalThis.crypto.subtle.digest('SHA-256', fromHex(inputHex))
        .then(result => callback(toHex(result), null))
        .catch(() => callback(null, 'crypto_failed'));
}
""")
private external fun webSha256Hex(inputHex: String, callback: (String?, String?) -> Unit)

@JsFun("""
(keyHex, inputHex, callback) => {
    const fromHex = h => new Uint8Array(h.match(/.{2}/g)?.map(x => parseInt(x, 16)) || []);
    const toHex = b => Array.from(new Uint8Array(b), x => x.toString(16).padStart(2, '0')).join('');
    globalThis.crypto.subtle.importKey('raw', fromHex(keyHex), {name:'HMAC', hash:'SHA-256'}, false, ['sign'])
        .then(key => globalThis.crypto.subtle.sign('HMAC', key, fromHex(inputHex)))
        .then(result => callback(toHex(result), null))
        .catch(() => callback(null, 'crypto_failed'));
}
""")
private external fun webHmacSha256Hex(
    keyHex: String,
    inputHex: String,
    callback: (String?, String?) -> Unit
)

@JsFun("""
(publicKeyHex, callback) => {
    const fromHex = h => new Uint8Array(h.match(/.{2}/g)?.map(x => parseInt(x, 16)) || []);
    globalThis.crypto.subtle.importKey(
        'raw', fromHex(publicKeyHex), {name:'ECDSA', namedCurve:'P-256'}, false, ['verify'])
        .then(() => callback('true', null))
        .catch(() => callback('false', null));
}
""")
private external fun webValidateP256PublicKey(
    publicKeyHex: String,
    callback: (String?, String?) -> Unit
)

@JsFun("""
(publicKeyHex, inputHex, signatureHex, callback) => {
    const fromHex = h => new Uint8Array(h.match(/.{2}/g)?.map(x => parseInt(x, 16)) || []);
    globalThis.crypto.subtle.importKey(
        'raw', fromHex(publicKeyHex), {name:'ECDSA', namedCurve:'P-256'}, false, ['verify'])
        .then(key => globalThis.crypto.subtle.verify(
            {name:'ECDSA', hash:'SHA-256'}, key, fromHex(signatureHex), fromHex(inputHex)))
        .then(valid => callback(valid ? 'true' : 'false', null))
        .catch(() => callback(null, 'crypto_failed'));
}
""")
private external fun webVerifyEs256(
    publicKeyHex: String,
    inputHex: String,
    signatureHex: String,
    callback: (String?, String?) -> Unit
)

@JsFun("""
(publicKeyHex, inputHex, signatureHex, callback) => {
    const fromHex = h => new Uint8Array(h.match(/.{2}/g)?.map(x => parseInt(x, 16)) || []);
    globalThis.crypto.subtle.importKey(
        'spki', fromHex(publicKeyHex), {name:'RSASSA-PKCS1-v1_5', hash:'SHA-256'}, false, ['verify'])
        .then(key => globalThis.crypto.subtle.verify(
            {name:'RSASSA-PKCS1-v1_5'}, key, fromHex(signatureHex), fromHex(inputHex)))
        .then(valid => callback(valid ? 'true' : 'false', null))
        .catch(() => callback(null, 'crypto_failed'));
}
""")
private external fun webVerifyRsaSha256(
    publicKeyHex: String,
    inputHex: String,
    signatureHex: String,
    callback: (String?, String?) -> Unit
)

@JsFun("""
(url, method, headersJson, bodyHex, maximumResponseBytes, callback) => {
    const fromHex = h => new Uint8Array(h.match(/.{2}/g)?.map(x => parseInt(x, 16)) || []);
    const toHex = b => Array.from(new Uint8Array(b), x => x.toString(16).padStart(2, '0')).join('');
    const options = {
        method,
        headers: JSON.parse(headersJson),
        redirect: 'manual',
        credentials: 'omit'
    };
    if (bodyHex.length > 0) options.body = fromHex(bodyHex);
    fetch(url, options)
        .then(async response => {
            const headers = {};
            response.headers.forEach((value, key) => { headers[key] = value; });
            const declaredLength = Number(response.headers.get('content-length'));
            if (Number.isFinite(declaredLength) && declaredLength > maximumResponseBytes) {
                throw new Error('response_too_large');
            }
            let bytes;
            if (response.body && response.body.getReader) {
                const reader = response.body.getReader();
                const chunks = [];
                let total = 0;
                while (true) {
                    const {done, value} = await reader.read();
                    if (done) break;
                    total += value.byteLength;
                    if (total > maximumResponseBytes) {
                        await reader.cancel('response_too_large').catch(() => undefined);
                        throw new Error('response_too_large');
                    }
                    chunks.push(value);
                }
                bytes = new Uint8Array(total);
                let offset = 0;
                for (const chunk of chunks) {
                    bytes.set(chunk, offset);
                    offset += chunk.byteLength;
                }
            } else {
                bytes = new Uint8Array(await response.arrayBuffer());
                if (bytes.byteLength > maximumResponseBytes) throw new Error('response_too_large');
            }
            callback(response.status, JSON.stringify(headers), toHex(bytes), null);
        })
        .catch(() => callback(0, null, null, 'fetch_failed'));
}
""")
private external fun webFetch(
    url: String,
    method: String,
    headersJson: String,
    bodyHex: String,
    maximumResponseBytes: Int,
    callback: (Int, String?, String?, String?) -> Unit
)
