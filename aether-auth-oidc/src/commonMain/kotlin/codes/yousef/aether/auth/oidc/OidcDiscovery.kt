package codes.yousef.aether.auth.oidc

import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.IdentityHttpMethod
import codes.yousef.aether.auth.IdentityHttpRequest
import codes.yousef.aether.auth.IdentityRuntime
import codes.yousef.aether.auth.P256PublicKey
import codes.yousef.aether.auth.RsaPublicKey
import kotlin.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class OidcMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val jwksUri: String,
    val idTokenSigningAlgorithms: Set<String>,
    val tokenEndpointAuthenticationMethods: Set<String>?
)

internal sealed interface OidcVerificationKey {
    val keyId: String
    val algorithm: String

    data class Es256(
        override val keyId: String,
        val publicKey: P256PublicKey
    ) : OidcVerificationKey {
        override val algorithm: String = "ES256"
    }

    data class Rs256(
        override val keyId: String,
        val publicKey: RsaPublicKey,
        val signatureSize: Int
    ) : OidcVerificationKey {
        override val algorithm: String = "RS256"
    }
}

internal class OidcProviderDocuments(
    private val config: OidcProviderConfig,
    private val runtime: IdentityRuntime
) {
    private val lock = Mutex()
    private var metadataCache: Timed<OidcMetadata>? = null
    private var jwksCache: Timed<List<OidcVerificationKey>>? = null

    suspend fun metadata(forceRefresh: Boolean = false): OidcMetadata = lock.withLock {
        val now = runtime.clock.now()
        val existing = metadataCache
        if (!forceRefresh && existing != null && existing.expiresAt > now) return@withLock existing.value
        val loaded = loadMetadata()
        if (existing?.value?.jwksUri != loaded.jwksUri) jwksCache = null
        metadataCache = Timed(loaded, now + config.discoveryCacheLifetime)
        loaded
    }

    suspend fun verificationKey(keyId: String, algorithm: String, forceRefresh: Boolean = false): OidcVerificationKey? =
        lock.withLock {
            val now = runtime.clock.now()
            val metadata = metadataCache?.takeIf { it.expiresAt > now }?.value ?: loadMetadata().also {
                metadataCache = Timed(it, now + config.discoveryCacheLifetime)
            }
            val existing = jwksCache
            val keys = if (!forceRefresh && existing != null && existing.expiresAt > now) {
                existing.value
            } else {
                loadJwks(metadata.jwksUri).also {
                    jwksCache = Timed(it, now + config.jwksCacheLifetime)
                }
            }
            keys.singleOrNull { it.keyId == keyId && it.algorithm == algorithm }
        }

    private suspend fun loadMetadata(): OidcMetadata {
        val response = executeGet("${config.issuer}/.well-known/openid-configuration", config.maximumDiscoveryBytes)
        val body = response
        val document = try {
            BoundedJson.parseObject(body, config.maximumDiscoveryBytes)
        } catch (failure: OidcAbort) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        } finally {
            body.fill(0)
        }
        val issuer = document.requiredString("issuer", 2_048)
        if (issuer != config.issuer) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        val authorizationEndpoint = document.requiredString("authorization_endpoint", 4_096)
        val tokenEndpoint = document.requiredString("token_endpoint", 4_096)
        val jwksUri = document.requiredString("jwks_uri", 4_096)
        validateProviderUrl(authorizationEndpoint)
        validateProviderUrl(tokenEndpoint)
        validateProviderUrl(jwksUri)
        if (queryParameterNames(authorizationEndpoint).any { it in AUTHORIZATION_REQUEST_PARAMETERS } ||
            queryParameterNames(tokenEndpoint).any { it in TOKEN_REQUEST_PARAMETERS }
        ) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }

        val responseTypes = document.optionalStringSet("response_types_supported")
        if (responseTypes == null || "code" !in responseTypes) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        val subjectTypes = document.optionalStringSet("subject_types_supported")
        if (subjectTypes == null || subjectTypes.none { it == "public" || it == "pairwise" }) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        val grantTypes = document.optionalStringSet("grant_types_supported")
        if (grantTypes != null && "authorization_code" !in grantTypes) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        val supportedScopes = document.optionalStringSet("scopes_supported")
        if (supportedScopes != null && "openid" !in supportedScopes) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        val pkceMethods = document.optionalStringSet("code_challenge_methods_supported")
        if (pkceMethods == null || "S256" !in pkceMethods) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        val signingAlgorithms = document.optionalStringSet("id_token_signing_alg_values_supported")
        if (signingAlgorithms == null || signingAlgorithms.none { it == "ES256" || it == "RS256" }) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        val authMethods = document.optionalStringSet("token_endpoint_auth_methods_supported")
        if (config.clientSecretReference == null && (authMethods == null || "none" !in authMethods)) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        if (config.clientSecretReference != null && authMethods != null && "client_secret_basic" !in authMethods) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        return OidcMetadata(
            issuer,
            authorizationEndpoint,
            tokenEndpoint,
            jwksUri,
            signingAlgorithms.filter { it == "ES256" || it == "RS256" }.toSet(),
            authMethods
        )
    }

    private suspend fun loadJwks(url: String): List<OidcVerificationKey> {
        val body = executeGet(url, config.maximumJwksBytes)
        val document = try {
            BoundedJson.parseObject(body, config.maximumJwksBytes)
        } catch (failure: OidcAbort) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        } finally {
            body.fill(0)
        }
        val keyArray = document.members["keys"] as? JsonArrayValue
            ?: oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        if (keyArray.elements.isEmpty() || keyArray.elements.size > 128) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        val parsed = keyArray.elements.mapNotNull { raw ->
            val key = raw as? JsonObjectValue ?: oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
            parseVerificationKey(key)
        }
        val identifiers = HashSet<Pair<String, String>>()
        if (parsed.any { !identifiers.add(it.keyId to it.algorithm) }) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        if (parsed.isEmpty()) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        return parsed
    }

    private fun parseVerificationKey(key: JsonObjectValue): OidcVerificationKey? {
        val use = key.optionalString("use", 32)
        if (use != null && use != "sig") return null
        val operations = key.optionalStringSet("key_ops", maximumElements = 16, maximumElementLength = 32)
        if (operations != null && "verify" !in operations) return null
        val keyId = key.optionalString("kid", 255) ?: return null
        if (keyId.isEmpty() || keyId.any { it.isWhitespace() || it.isProtocolControl() }) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        val algorithm = key.optionalString("alg", 16)
        return when (key.requiredString("kty", 16)) {
            "EC" -> {
                if (algorithm != null && algorithm != "ES256") return null
                if (key.requiredString("crv", 32) != "P-256") return null
                val x = decodeJwkValue(key.requiredString("x", 128), 32)
                val y = decodeJwkValue(key.requiredString("y", 128), 32)
                if (x.size != 32 || y.size != 32) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
                OidcVerificationKey.Es256(keyId, P256PublicKey(byteArrayOf(0x04) + x + y))
            }
            "RSA" -> {
                if (algorithm != null && algorithm != "RS256") return null
                val modulus = decodeJwkValue(key.requiredString("n", 2_048), 1_024)
                val exponent = decodeJwkValue(key.requiredString("e", 16), 4)
                val spki = rsaSubjectPublicKeyInfo(modulus, exponent)
                OidcVerificationKey.Rs256(keyId, RsaPublicKey(spki), modulus.size)
            }
            else -> null
        }
    }

    private fun decodeJwkValue(value: String, maximumBytes: Int): ByteArray = try {
        Base64Url.decode(value, maximumBytes)
    } catch (_: IllegalArgumentException) {
        oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    }

    private suspend fun executeGet(url: String, maximumBytes: Int): ByteArray {
        val response = try {
            runtime.http.execute(
                IdentityHttpRequest(
                    method = IdentityHttpMethod.GET,
                    url = url,
                    headers = mapOf("Accept" to "application/json"),
                    maximumResponseBytes = maximumBytes
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            oidcAbort(OidcErrorCode.DISCOVERY_UNAVAILABLE)
        }
        if (response.statusCode !in 200..299) oidcAbort(OidcErrorCode.DISCOVERY_UNAVAILABLE)
        val bytes = response.bodyBytes()
        if (bytes.size > maximumBytes) {
            bytes.fill(0)
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        return bytes
    }

    private fun validateProviderUrl(url: String) {
        if (url.length > 4_096 || '#' in url || url.any { it.isWhitespace() || it.isProtocolControl() }) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        try {
            IdentityHttpRequest(IdentityHttpMethod.GET, url)
        } catch (_: IllegalArgumentException) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        if (oidcEndpointOrigin(url) !in config.allowedEndpointOrigins) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
    }

    private data class Timed<T>(val value: T, val expiresAt: Instant)

    private companion object {
        val AUTHORIZATION_REQUEST_PARAMETERS = setOf(
            "response_type", "client_id", "redirect_uri", "scope", "state", "nonce",
            "code_challenge", "code_challenge_method"
        )
        val TOKEN_REQUEST_PARAMETERS = setOf(
            "grant_type", "code", "redirect_uri", "client_id", "code_verifier"
        )
    }
}
