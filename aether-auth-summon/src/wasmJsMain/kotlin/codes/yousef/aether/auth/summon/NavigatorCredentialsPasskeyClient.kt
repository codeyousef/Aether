@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package codes.yousef.aether.auth.summon

import codes.yousef.aether.auth.webauthn.AuthenticationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialCreationOptions
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialRequestOptions
import codes.yousef.aether.auth.webauthn.RegistrationPublicKeyCredentialDto
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Production wasmJs adapter for the browser Credential Management API. */
class NavigatorCredentialsPasskeyClient : PasskeyBrowserClient {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    override suspend fun create(
        options: PublicKeyCredentialCreationOptions
    ): RegistrationPublicKeyCredentialDto {
        val response = awaitBrowserCredential { callback ->
            navigatorCreateCredential(json.encodeToString(options), callback)
        }
        return decodeCredential(response)
    }

    override suspend fun get(
        options: PublicKeyCredentialRequestOptions
    ): AuthenticationPublicKeyCredentialDto {
        val response = awaitBrowserCredential { callback ->
            navigatorGetCredential(json.encodeToString(options), callback)
        }
        return decodeCredential(response)
    }

    private inline fun <reified T> decodeCredential(response: String): T = runCatching {
        json.decodeFromString<T>(response)
    }.getOrElse {
        throw PasskeyBrowserException(PasskeyBrowserErrorCode.INVALID_RESPONSE)
    }
}

private suspend fun awaitBrowserCredential(
    start: (((String?, String?) -> Unit)) -> Unit
): String = suspendCoroutine { continuation ->
    start { response, error ->
        if (response != null && error == null) {
            continuation.resume(response)
        } else {
            val code = runCatching { PasskeyBrowserErrorCode.valueOf(error.orEmpty()) }
                .getOrDefault(PasskeyBrowserErrorCode.UNKNOWN)
            continuation.resumeWithException(PasskeyBrowserException(code))
        }
    }
}

@JsFun("""
(optionsJson, callback) => {
    const fail = error => {
        const name = error && error.name;
        const code = name === 'NotAllowedError' ? 'NOT_ALLOWED'
            : name === 'AbortError' ? 'ABORTED'
            : name === 'SecurityError' ? 'SECURITY_ERROR'
            : 'UNKNOWN';
        callback(null, code);
    };
    const decode = value => {
        if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value) || value.length % 4 === 1) {
            throw new Error('invalid_base64url');
        }
        const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
        const binary = globalThis.atob(padded);
        return Uint8Array.from(binary, character => character.charCodeAt(0));
    };
    const encode = value => {
        const bytes = new Uint8Array(value);
        let binary = '';
        for (const byte of bytes) binary += String.fromCharCode(byte);
        return globalThis.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
    };
    try {
        if (!globalThis.navigator || !globalThis.navigator.credentials || !globalThis.PublicKeyCredential) {
            callback(null, 'NOT_SUPPORTED');
            return;
        }
        const publicKey = JSON.parse(optionsJson);
        publicKey.challenge = decode(publicKey.challenge);
        publicKey.user = {...publicKey.user, id: decode(publicKey.user.id)};
        publicKey.excludeCredentials = (publicKey.excludeCredentials || []).map(item => ({...item, id: decode(item.id)}));
        globalThis.navigator.credentials.create({publicKey}).then(credential => {
            if (!credential || !credential.response) {
                callback(null, 'INVALID_RESPONSE');
                return;
            }
            const rawId = encode(credential.rawId);
            callback(JSON.stringify({
                id: rawId,
                rawId,
                type: credential.type,
                response: {
                    clientDataJSON: encode(credential.response.clientDataJSON),
                    attestationObject: encode(credential.response.attestationObject),
                    transports: credential.response.getTransports ? credential.response.getTransports() : []
                },
                authenticatorAttachment: credential.authenticatorAttachment || null,
                clientExtensionResults: credential.getClientExtensionResults ? credential.getClientExtensionResults() : {}
            }), null);
        }).catch(fail);
    } catch (_) {
        callback(null, 'INVALID_RESPONSE');
    }
}
""")
private external fun navigatorCreateCredential(
    optionsJson: String,
    callback: (String?, String?) -> Unit
)

@JsFun("""
(optionsJson, callback) => {
    const fail = error => {
        const name = error && error.name;
        const code = name === 'NotAllowedError' ? 'NOT_ALLOWED'
            : name === 'AbortError' ? 'ABORTED'
            : name === 'SecurityError' ? 'SECURITY_ERROR'
            : 'UNKNOWN';
        callback(null, code);
    };
    const decode = value => {
        if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value) || value.length % 4 === 1) {
            throw new Error('invalid_base64url');
        }
        const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
        const binary = globalThis.atob(padded);
        return Uint8Array.from(binary, character => character.charCodeAt(0));
    };
    const encode = value => {
        const bytes = new Uint8Array(value);
        let binary = '';
        for (const byte of bytes) binary += String.fromCharCode(byte);
        return globalThis.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
    };
    try {
        if (!globalThis.navigator || !globalThis.navigator.credentials || !globalThis.PublicKeyCredential) {
            callback(null, 'NOT_SUPPORTED');
            return;
        }
        const publicKey = JSON.parse(optionsJson);
        publicKey.challenge = decode(publicKey.challenge);
        publicKey.allowCredentials = (publicKey.allowCredentials || []).map(item => ({...item, id: decode(item.id)}));
        globalThis.navigator.credentials.get({publicKey}).then(credential => {
            if (!credential || !credential.response) {
                callback(null, 'INVALID_RESPONSE');
                return;
            }
            const rawId = encode(credential.rawId);
            callback(JSON.stringify({
                id: rawId,
                rawId,
                type: credential.type,
                response: {
                    clientDataJSON: encode(credential.response.clientDataJSON),
                    authenticatorData: encode(credential.response.authenticatorData),
                    signature: encode(credential.response.signature),
                    userHandle: credential.response.userHandle == null ? null : encode(credential.response.userHandle)
                },
                authenticatorAttachment: credential.authenticatorAttachment || null,
                clientExtensionResults: credential.getClientExtensionResults ? credential.getClientExtensionResults() : {}
            }), null);
        }).catch(fail);
    } catch (_) {
        callback(null, 'INVALID_RESPONSE');
    }
}
""")
private external fun navigatorGetCredential(
    optionsJson: String,
    callback: (String?, String?) -> Unit
)

/** Test seam proving the same strict ArrayBuffer conversion remains canonical and unpadded. */
@JsFun("""
(value) => {
    if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value) || value.length % 4 === 1) {
        throw new Error('invalid_base64url');
    }
    const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
    const binary = globalThis.atob(padded);
    const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
    let output = '';
    for (const byte of bytes) output += String.fromCharCode(byte);
    return globalThis.btoa(output).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}
""")
internal external fun roundTripBrowserBase64Url(value: String): String
