package codes.yousef.aether.auth.saml

import codes.yousef.aether.auth.Es256Signature
import codes.yousef.aether.auth.IdentityCrypto
import codes.yousef.aether.auth.RsaSha256Signature
import kotlin.time.Instant

internal const val SAML_PROTOCOL_NAMESPACE = "urn:oasis:names:tc:SAML:2.0:protocol"
internal const val SAML_ASSERTION_NAMESPACE = "urn:oasis:names:tc:SAML:2.0:assertion"
internal const val XMLDSIG_NAMESPACE = "http://www.w3.org/2000/09/xmldsig#"
internal const val EXCLUSIVE_C14N = "http://www.w3.org/2001/10/xml-exc-c14n#"
internal const val ENVELOPED_SIGNATURE = "http://www.w3.org/2000/09/xmldsig#enveloped-signature"
internal const val SHA256_DIGEST = "http://www.w3.org/2001/04/xmlenc#sha256"

internal data class VerifiedSamlSignature(
    val keyId: String,
    val algorithm: SamlSignatureAlgorithm
)

internal class SamlSignatureVerifier(private val crypto: IdentityCrypto) {
    suspend fun verify(
        document: SamlXmlDocument,
        target: SamlXmlElement,
        signature: SamlXmlElement,
        metadata: SamlProviderMetadata,
        now: Instant
    ): VerifiedSamlSignature {
        if (signature.name.namespaceUri != XMLDSIG_NAMESPACE || signature.name.localName != "Signature" ||
            signature !in target.directElements(XMLDSIG_NAMESPACE, "Signature")
        ) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        val targetId = target.attribute("ID") ?: samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        if (document.elementsById[targetId] !== target) samlAbort(SamlErrorCode.SIGNATURE_INVALID)

        val elementChildren = signature.children.filterIsInstance<SamlXmlElement>()
        if (elementChildren.any { it.name.namespaceUri != XMLDSIG_NAMESPACE } ||
            elementChildren.map { it.name.localName }.any { it !in setOf("SignedInfo", "SignatureValue", "KeyInfo") }
        ) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        val signedInfo = signature.singleDirectElement(XMLDSIG_NAMESPACE, "SignedInfo")
        val signatureValueElement = signature.singleDirectElement(XMLDSIG_NAMESPACE, "SignatureValue")
        val keyInfo = signature.optionalDirectElement(XMLDSIG_NAMESPACE, "KeyInfo")
        validateSignedInfoShape(signedInfo, targetId)

        val algorithmUri = signedInfo.singleDirectElement(XMLDSIG_NAMESPACE, "SignatureMethod").attribute("Algorithm")
            ?: samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        val algorithm = SamlSignatureAlgorithm.entries.singleOrNull { it.uri == algorithmUri }
            ?: samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        val reference = signedInfo.singleDirectElement(XMLDSIG_NAMESPACE, "Reference")
        val digestValue = decodeBase64Text(
            reference.singleDirectElement(XMLDSIG_NAMESPACE, "DigestValue"),
            maximumBytes = 32
        )
        if (digestValue.size != 32) samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        val canonicalTarget = canonicalizeExclusive(target, excludedElement = signature)
        val actualDigest = try {
            crypto.sha256(canonicalTarget)
        } finally {
            canonicalTarget.fill(0)
        }
        val digestMatches = try {
            actualDigest.size == 32 && crypto.constantTimeEquals(digestValue, actualDigest)
        } finally {
            digestValue.fill(0)
            actualDigest.fill(0)
        }
        if (!digestMatches) samlAbort(SamlErrorCode.SIGNATURE_INVALID)

        val keyHint = keyInfo?.let(::readKeyHint)
        val candidateKeys = metadata.verificationKeys.filter { key ->
            (keyHint == null || key.keyId == keyHint) && key.validAt(now) && when (algorithm) {
                SamlSignatureAlgorithm.RSA_SHA256 -> key is SamlVerificationKey.Rsa
                SamlSignatureAlgorithm.ECDSA_SHA256 -> key is SamlVerificationKey.Es256
            }
        }
        if (candidateKeys.isEmpty()) samlAbort(SamlErrorCode.SIGNATURE_INVALID)

        val canonicalSignedInfo = canonicalizeExclusive(signedInfo)
        val signatureBytes = decodeBase64Text(signatureValueElement, maximumBytes = 8_192)
        try {
            candidateKeys.forEach { key ->
                val verified = try {
                    when {
                        key is SamlVerificationKey.Rsa && algorithm == SamlSignatureAlgorithm.RSA_SHA256 ->
                            crypto.verifyRsaSha256(
                                key.publicKey,
                                canonicalSignedInfo,
                                RsaSha256Signature(signatureBytes)
                            )

                        key is SamlVerificationKey.Es256 && algorithm == SamlSignatureAlgorithm.ECDSA_SHA256 ->
                            crypto.verifyEs256(
                                key.publicKey,
                                canonicalSignedInfo,
                                Es256Signature(signatureBytes)
                            )

                        else -> false
                    }
                } catch (_: IllegalArgumentException) {
                    false
                }
                if (verified) return VerifiedSamlSignature(key.keyId, algorithm)
            }
        } finally {
            canonicalSignedInfo.fill(0)
            signatureBytes.fill(0)
        }
        samlAbort(SamlErrorCode.SIGNATURE_INVALID)
    }

    private fun validateSignedInfoShape(signedInfo: SamlXmlElement, targetId: String) {
        val children = signedInfo.children.filterIsInstance<SamlXmlElement>()
        if (children.size != 3 || children.any { it.name.namespaceUri != XMLDSIG_NAMESPACE }) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        if (children.map { it.name.localName } != listOf("CanonicalizationMethod", "SignatureMethod", "Reference")) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        val canonicalization = children[0]
        if (canonicalization.attribute("Algorithm") != EXCLUSIVE_C14N ||
            canonicalization.children.any { it is SamlXmlElement }
        ) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        val signatureMethod = children[1]
        if (signatureMethod.attribute("Algorithm") !in SamlSignatureAlgorithm.entries.map { it.uri } ||
            signatureMethod.children.any { it is SamlXmlElement }
        ) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        val reference = children[2]
        if (reference.attribute("URI") != "#$targetId") samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        val referenceChildren = reference.children.filterIsInstance<SamlXmlElement>()
        if (referenceChildren.size != 3 ||
            referenceChildren.map { it.name.localName } != listOf("Transforms", "DigestMethod", "DigestValue") ||
            referenceChildren.any { it.name.namespaceUri != XMLDSIG_NAMESPACE }
        ) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        val transforms = referenceChildren[0]
        val transformElements = transforms.children.filterIsInstance<SamlXmlElement>()
        if (transformElements.size != 2 || transformElements.any {
                it.name.namespaceUri != XMLDSIG_NAMESPACE || it.name.localName != "Transform" ||
                    it.children.any { child -> child is SamlXmlElement }
            } || transformElements.map { it.attribute("Algorithm") } != listOf(ENVELOPED_SIGNATURE, EXCLUSIVE_C14N)
        ) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        val digestMethod = referenceChildren[1]
        if (digestMethod.attribute("Algorithm") != SHA256_DIGEST || digestMethod.children.any { it is SamlXmlElement }) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        if (reference.attributes.any { it.name.localName != "URI" || it.name.namespaceUri.isNotEmpty() }) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
    }

    private fun readKeyHint(keyInfo: SamlXmlElement): String? {
        val children = keyInfo.children.filterIsInstance<SamlXmlElement>()
        if (children.any {
                it.name.namespaceUri != XMLDSIG_NAMESPACE || it.name.localName !in setOf("KeyName", "X509Data")
            }
        ) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        val keyNames = keyInfo.directElements(XMLDSIG_NAMESPACE, "KeyName")
        if (keyNames.size > 1) samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        return keyNames.singleOrNull()?.normalizedText(255)?.also {
            if (it.any(Char::isWhitespace)) samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
    }

    private fun decodeBase64Text(element: SamlXmlElement, maximumBytes: Int): ByteArray {
        if (element.children.any { it !is SamlXmlText }) samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        val lexical = element.children.filterIsInstance<SamlXmlText>().joinToString("") { it.value }
        if (lexical.length > maximumBytes * 2 + 64 || lexical.any { it.isWhitespace() && it !in " \t\r\n" }) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        val compact = lexical.filterNot(Char::isWhitespace)
        return try {
            SamlBase64.decode(compact, maximumBytes)
        } catch (_: IllegalArgumentException) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
    }

    private fun SamlVerificationKey.validAt(now: Instant): Boolean =
        (validFrom == null || now >= validFrom!!) && (validUntil == null || now < validUntil!!)
}
