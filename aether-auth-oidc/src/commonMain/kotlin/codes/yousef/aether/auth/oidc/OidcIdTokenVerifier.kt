package codes.yousef.aether.auth.oidc

import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.aether.auth.Es256Signature
import codes.yousef.aether.auth.ExternalSubject
import codes.yousef.aether.auth.IdentityRuntime
import codes.yousef.aether.auth.RsaSha256Signature
import kotlin.time.Instant

internal data class VerifiedIdToken(
    val claims: OidcVerifiedClaims,
    val assertionDigest: ByteArray
)

internal class OidcIdTokenVerifier(
    private val config: OidcProviderConfig,
    private val runtime: IdentityRuntime,
    private val documents: OidcProviderDocuments
) {
    suspend fun verify(idToken: String, expectedNonceDigest: ByteArray): VerifiedIdToken {
        if (idToken.length !in 32..config.maximumIdTokenBytes || idToken.any(Char::isWhitespace)) {
            oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        }
        val segments = idToken.split('.')
        if (segments.size != 3 || segments.any(String::isEmpty)) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        val headerBytes = decodeSegment(segments[0], 8_192)
        val claimsBytes = decodeSegment(segments[1], 49_152)
        val signatureBytes = decodeSegment(segments[2], 8_192)
        try {
            val header = BoundedJson.parseJwtObject(headerBytes, 8_192)
            val claimsDocument = BoundedJson.parseJwtObject(claimsBytes, 49_152)
            val algorithm = jwtString(header, "alg", 16)
            if (algorithm != "ES256" && algorithm != "RS256") oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
            if (algorithm !in documents.metadata().idTokenSigningAlgorithms) {
                oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
            }
            val keyId = jwtString(header, "kid", 255)
            if (keyId.any { it.isWhitespace() || it.isProtocolControl() }) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
            val type = jwtOptionalString(header, "typ", 16)
            if (type != null && type != "JWT") oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
            if (header.members.keys.any { it in FORBIDDEN_JOSE_HEADERS }) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)

            val signedData = "${segments[0]}.${segments[1]}".encodeToByteArray()
            val signatureValid = try {
                verifyWithRefresh(keyId, algorithm, signedData, signatureBytes)
            } finally {
                signedData.fill(0)
            }
            if (!signatureValid) oidcAbort(OidcErrorCode.SIGNATURE_INVALID)

            val claims = validateClaims(claimsDocument, expectedNonceDigest)
            val digest = runtime.crypto.sha256(idToken.encodeToByteArray())
            if (digest.size != 32) {
                digest.fill(0)
                oidcAbort(OidcErrorCode.STORE_UNAVAILABLE)
            }
            return VerifiedIdToken(claims, digest)
        } finally {
            headerBytes.fill(0)
            claimsBytes.fill(0)
            signatureBytes.fill(0)
        }
    }

    private suspend fun verifyWithRefresh(
        keyId: String,
        algorithm: String,
        signedData: ByteArray,
        signature: ByteArray
    ): Boolean {
        val first = documents.verificationKey(keyId, algorithm)
        if (first != null && verify(first, signedData, signature)) return true
        val refreshed = documents.verificationKey(keyId, algorithm, forceRefresh = true) ?: return false
        return verify(refreshed, signedData, signature)
    }

    private suspend fun verify(key: OidcVerificationKey, signedData: ByteArray, signature: ByteArray): Boolean =
        try {
            when (key) {
                is OidcVerificationKey.Es256 -> {
                    if (signature.size != 64) return false
                    runtime.crypto.verifyEs256(key.publicKey, signedData, Es256Signature(signature))
                }
                is OidcVerificationKey.Rs256 -> {
                    if (signature.size != key.signatureSize || signature.size !in 256..1_024) return false
                    runtime.crypto.verifyRsaSha256(key.publicKey, signedData, RsaSha256Signature(signature))
                }
            }
        } catch (_: IllegalArgumentException) {
            false
        }

    private suspend fun validateClaims(document: JsonObjectValue, expectedNonceDigest: ByteArray): OidcVerifiedClaims {
        val issuer = jwtString(document, "iss", 2_048)
        if (issuer != config.issuer) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        val subjectValue = jwtString(document, "sub", 1_024)
        if (subjectValue.any(Char::isProtocolControl)) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        val subject = try {
            ExternalSubject(subjectValue)
        } catch (_: IllegalArgumentException) {
            oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        }
        val audiences = jwtAudience(document)
        if (config.clientId !in audiences) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        val authorizedParty = jwtOptionalString(document, "azp", 512)
        if ((audiences.size > 1 && authorizedParty == null) ||
            (authorizedParty != null && authorizedParty != config.clientId)
        ) {
            oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        }

        val expiresAtSeconds = jwtLong(document, "exp")
        val issuedAtSeconds = jwtLong(document, "iat")
        val expiresAt = instantFromEpochSeconds(expiresAtSeconds)
        val issuedAt = instantFromEpochSeconds(issuedAtSeconds)
        if (expiresAt <= issuedAt) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        val now = runtime.clock.now()
        if (expiresAt <= now - config.clockSkew || issuedAt > now + config.clockSkew) {
            oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        }
        if (expiresAt - issuedAt > config.maximumIdTokenLifetime) {
            oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        }
        val notBefore = jwtOptionalLong(document, "nbf")
        if (notBefore != null && instantFromEpochSeconds(notBefore) > now + config.clockSkew) {
            oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        }

        val nonce = jwtString(document, "nonce", 512)
        val actualNonceDigest = runtime.crypto.sha256(nonce.encodeToByteArray())
        val nonceMatches = try {
            runtime.crypto.constantTimeEquals(actualNonceDigest, expectedNonceDigest)
        } finally {
            actualNonceDigest.fill(0)
        }
        if (!nonceMatches) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)

        val email = jwtOptionalString(document, "email", 320)
            ?.takeIf { it.none(Char::isProtocolControl) }
            ?.let { raw ->
            try {
                EmailAddress(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val displayName = jwtOptionalString(document, "name", 200)
            ?.takeIf { it.isNotBlank() && it.none(Char::isProtocolControl) }
        return OidcVerifiedClaims(
            issuer = issuer,
            subject = subject,
            audiences = audiences,
            authorizedParty = authorizedParty,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            email = email,
            displayName = displayName
        )
    }

    private fun decodeSegment(value: String, maximumBytes: Int): ByteArray = try {
        Base64Url.decode(value, maximumBytes)
    } catch (_: IllegalArgumentException) {
        oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
    }

    private fun jwtString(document: JsonObjectValue, name: String, maximumLength: Int): String {
        val value = (document.members[name] as? JsonStringValue)?.value ?: oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        if (value.isEmpty() || value.length > maximumLength) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        return value
    }

    private fun jwtOptionalString(document: JsonObjectValue, name: String, maximumLength: Int): String? {
        val raw = document.members[name] ?: return null
        if (raw === JsonNullValue) return null
        val value = (raw as? JsonStringValue)?.value ?: oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        if (value.length > maximumLength) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        return value
    }

    private fun jwtLong(document: JsonObjectValue, name: String): Long {
        val source = (document.members[name] as? JsonNumberValue)?.source ?: oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        if ('.' in source || 'e' in source || 'E' in source) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        return source.toLongOrNull() ?: oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
    }

    private fun jwtOptionalLong(document: JsonObjectValue, name: String): Long? {
        val raw = document.members[name] ?: return null
        if (raw === JsonNullValue) return null
        val source = (raw as? JsonNumberValue)?.source ?: oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        if ('.' in source || 'e' in source || 'E' in source) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        return source.toLongOrNull() ?: oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
    }

    private fun jwtAudience(document: JsonObjectValue): Set<String> {
        val raw = document.members["aud"] ?: oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        val audiences = when (raw) {
            is JsonStringValue -> setOf(raw.value)
            is JsonArrayValue -> {
                if (raw.elements.isEmpty() || raw.elements.size > 32) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
                raw.elements.map { (it as? JsonStringValue)?.value ?: oidcAbort(OidcErrorCode.ID_TOKEN_INVALID) }.toSet()
            }
            else -> oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        }
        if (audiences.isEmpty() || audiences.size > 32 ||
            audiences.any { it.isEmpty() || it.length > 512 || it.any(Char::isProtocolControl) }
        ) {
            oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        }
        if (raw is JsonArrayValue && audiences.size != raw.elements.size) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        return audiences
    }

    private fun instantFromEpochSeconds(seconds: Long): Instant = try {
        Instant.fromEpochSeconds(seconds)
    } catch (_: IllegalArgumentException) {
        oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
    }

    private companion object {
        val FORBIDDEN_JOSE_HEADERS = setOf("crit", "jku", "jwk", "x5u", "x5c")
    }
}
