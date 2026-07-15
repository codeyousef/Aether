package codes.yousef.aether.auth.saml

import codes.yousef.aether.auth.ExternalSubject
import kotlin.time.Instant

internal data class ValidatedSamlResponse(
    val claims: SamlVerifiedClaims,
    val assertion: SamlXmlElement,
    val responseSignature: VerifiedSamlSignature?,
    val assertionSignature: VerifiedSamlSignature?
)

internal class SamlResponseValidator(
    private val config: SamlProviderConfig,
    private val signatureVerifier: SamlSignatureVerifier
) {
    suspend fun validate(
        document: SamlXmlDocument,
        expectedRequestId: String,
        metadata: SamlProviderMetadata,
        now: Instant
    ): ValidatedSamlResponse {
        val response = document.root
        if (response.name.namespaceUri != SAML_PROTOCOL_NAMESPACE || response.name.localName != "Response") {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        requireRootIdentity(document, response, "Response")
        requireExactAttribute(response, "Version", "2.0")
        requireExactAttribute(response, "Destination", config.assertionConsumerServiceUrl)
        requireExactAttribute(response, "InResponseTo", expectedRequestId)
        val responseIssuedAt = parseInstant(requireAttribute(response, "IssueInstant"))
        validateFreshIssueInstant(responseIssuedAt, now)
        requireIssuer(response)
        requireSuccessStatus(response)

        val assertions = response.directElements(SAML_ASSERTION_NAMESPACE, "Assertion")
        if (assertions.size != 1) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        val assertion = assertions.single()
        requireRootIdentity(document, assertion, "Assertion")
        if (allDescendants(response).count {
                it.name.namespaceUri == SAML_ASSERTION_NAMESPACE && it.name.localName == "Assertion"
            } != 1
        ) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }

        val responseSignatureElement = response.optionalDirectElement(XMLDSIG_NAMESPACE, "Signature")
        val assertionSignatureElement = assertion.optionalDirectElement(XMLDSIG_NAMESPACE, "Signature")
        val allowedSignatures = listOfNotNull(responseSignatureElement, assertionSignatureElement)
        val everySignature = allDescendants(response).filter {
            it.name.namespaceUri == XMLDSIG_NAMESPACE && it.name.localName == "Signature"
        }
        if (everySignature.size != allowedSignatures.size || everySignature.any { candidate ->
                allowedSignatures.none { it === candidate }
            }
        ) {
            samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        }
        requireSignaturePolicy(responseSignatureElement != null, assertionSignatureElement != null)
        val responseSignature = responseSignatureElement?.let {
            signatureVerifier.verify(document, response, it, metadata, now)
        }
        val assertionSignature = assertionSignatureElement?.let {
            signatureVerifier.verify(document, assertion, it, metadata, now)
        }

        requireExactAttribute(assertion, "Version", "2.0")
        val issuedAt = parseInstant(requireAttribute(assertion, "IssueInstant"))
        validateFreshIssueInstant(issuedAt, now)
        requireIssuer(assertion)

        val subject = assertion.singleDirectElement(SAML_ASSERTION_NAMESPACE, "Subject")
        val nameId = subject.singleDirectElement(SAML_ASSERTION_NAMESPACE, "NameID")
        val subjectValue = strictText(nameId, 1_024)
        val nameIdFormat = nameId.attribute("Format")
            ?: "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified"
        if (nameIdFormat !in config.allowedNameIdFormats) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        val subjectConfirmation = subject.singleDirectElement(SAML_ASSERTION_NAMESPACE, "SubjectConfirmation")
        requireExactAttribute(
            subjectConfirmation,
            "Method",
            "urn:oasis:names:tc:SAML:2.0:cm:bearer"
        )
        val confirmationData = subjectConfirmation.singleDirectElement(
            SAML_ASSERTION_NAMESPACE,
            "SubjectConfirmationData"
        )
        requireExactAttribute(confirmationData, "Recipient", config.assertionConsumerServiceUrl)
        requireExactAttribute(confirmationData, "InResponseTo", expectedRequestId)
        confirmationData.attribute("NotBefore")?.let { validateNotBefore(parseInstant(it), now) }
        val confirmationExpiresAt = parseInstant(requireAttribute(confirmationData, "NotOnOrAfter"))
        validateNotOnOrAfter(confirmationExpiresAt, now)

        val conditions = assertion.singleDirectElement(SAML_ASSERTION_NAMESPACE, "Conditions")
        conditions.attribute("NotBefore")?.let { validateNotBefore(parseInstant(it), now) }
        val conditionsExpiresAt = parseInstant(requireAttribute(conditions, "NotOnOrAfter"))
        validateNotOnOrAfter(conditionsExpiresAt, now)
        if (conditionsExpiresAt - issuedAt > config.maximumAssertionLifetime + config.clockSkew) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        validateAudiences(conditions)

        val authnStatement = assertion.singleDirectElement(SAML_ASSERTION_NAMESPACE, "AuthnStatement")
        val authenticatedAt = parseInstant(requireAttribute(authnStatement, "AuthnInstant"))
        if (authenticatedAt > now + config.clockSkew || authenticatedAt > issuedAt + config.clockSkew) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        val sessionExpiresAt = authnStatement.attribute("SessionNotOnOrAfter")?.let {
            parseInstant(it).also { instant -> validateNotOnOrAfter(instant, now) }
        }
        val sessionIndex = authnStatement.attribute("SessionIndex")?.also {
            if (it.isBlank() || it.length > 1_024) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        val authnContext = authnStatement.optionalDirectElement(SAML_ASSERTION_NAMESPACE, "AuthnContext")
            ?.optionalDirectElement(SAML_ASSERTION_NAMESPACE, "AuthnContextClassRef")
            ?.let { strictText(it, 1_024) }

        val expiresAt = listOfNotNull(confirmationExpiresAt, conditionsExpiresAt, sessionExpiresAt).min()
        val attributes = parseAttributes(assertion)
        return ValidatedSamlResponse(
            claims = SamlVerifiedClaims(
                issuer = config.idpEntityId,
                subject = try {
                    ExternalSubject(subjectValue)
                } catch (_: IllegalArgumentException) {
                    samlAbort(SamlErrorCode.RESPONSE_INVALID)
                },
                nameIdFormat = nameIdFormat,
                issuedAt = issuedAt,
                authenticatedAt = authenticatedAt,
                expiresAt = expiresAt,
                sessionIndex = sessionIndex,
                authenticationContext = authnContext,
                attributes = attributes
            ),
            assertion = assertion,
            responseSignature = responseSignature,
            assertionSignature = assertionSignature
        )
    }

    private fun requireRootIdentity(document: SamlXmlDocument, element: SamlXmlElement, localName: String) {
        val id = requireAttribute(element, "ID")
        if (document.elementsById[id] !== element || element.name.localName != localName) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
    }

    private fun requireIssuer(parent: SamlXmlElement) {
        val issuer = parent.singleDirectElement(SAML_ASSERTION_NAMESPACE, "Issuer")
        if (strictText(issuer, 2_048) != config.idpEntityId) samlAbort(SamlErrorCode.RESPONSE_INVALID)
    }

    private fun requireSuccessStatus(response: SamlXmlElement) {
        val status = response.singleDirectElement(SAML_PROTOCOL_NAMESPACE, "Status")
        val statusCode = status.singleDirectElement(SAML_PROTOCOL_NAMESPACE, "StatusCode")
        requireExactAttribute(
            statusCode,
            "Value",
            "urn:oasis:names:tc:SAML:2.0:status:Success"
        )
        if (statusCode.directElements(SAML_PROTOCOL_NAMESPACE, "StatusCode").isNotEmpty()) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
    }

    private fun validateAudiences(conditions: SamlXmlElement) {
        val allowedConditionChildren = setOf("AudienceRestriction", "OneTimeUse")
        if (conditions.children.filterIsInstance<SamlXmlElement>().any {
                it.name.namespaceUri != SAML_ASSERTION_NAMESPACE || it.name.localName !in allowedConditionChildren
            }
        ) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        val restrictions = conditions.directElements(SAML_ASSERTION_NAMESPACE, "AudienceRestriction")
        if (restrictions.isEmpty() || restrictions.size > 16) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        restrictions.forEach { restriction ->
            val audiences = restriction.directElements(SAML_ASSERTION_NAMESPACE, "Audience")
            if (audiences.isEmpty() || audiences.size > 32 ||
                audiences.map { strictText(it, 2_048) }.none { it == config.spEntityId }
            ) {
                samlAbort(SamlErrorCode.RESPONSE_INVALID)
            }
        }
    }

    private fun parseAttributes(assertion: SamlXmlElement): Map<String, List<String>> {
        val statements = assertion.directElements(SAML_ASSERTION_NAMESPACE, "AttributeStatement")
        if (statements.size > 16) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        val attributes = linkedMapOf<String, MutableList<String>>()
        var valueCount = 0
        statements.forEach { statement ->
            val children = statement.children.filterIsInstance<SamlXmlElement>()
            if (children.any {
                    it.name.namespaceUri != SAML_ASSERTION_NAMESPACE || it.name.localName != "Attribute"
                } || children.size > 128
            ) {
                samlAbort(SamlErrorCode.RESPONSE_INVALID)
            }
            children.forEach { attribute ->
                val name = requireAttribute(attribute, "Name")
                if (name.isBlank() || name.length > 1_024) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                val values = attribute.directElements(SAML_ASSERTION_NAMESPACE, "AttributeValue")
                if (values.isEmpty() || values.size > 128) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                val destination = attributes.getOrPut(name) { mutableListOf() }
                values.forEach { value ->
                    valueCount++
                    if (valueCount > 512) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                    destination += strictText(value, 4_096)
                }
            }
        }
        return attributes.mapValues { (_, values) -> values.toList() }
    }

    private fun requireSignaturePolicy(responseSigned: Boolean, assertionSigned: Boolean) {
        val valid = when (config.signaturePolicy) {
            SamlSignaturePolicy.ASSERTION_OR_RESPONSE -> responseSigned || assertionSigned
            SamlSignaturePolicy.ASSERTION -> assertionSigned
            SamlSignaturePolicy.RESPONSE -> responseSigned
            SamlSignaturePolicy.BOTH -> responseSigned && assertionSigned
        }
        if (!valid) samlAbort(SamlErrorCode.SIGNATURE_INVALID)
    }

    private fun requireAttribute(element: SamlXmlElement, name: String): String =
        element.attribute(name)?.also {
            if (it.isEmpty() || it.length > 4_096) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        } ?: samlAbort(SamlErrorCode.RESPONSE_INVALID)

    private fun requireExactAttribute(element: SamlXmlElement, name: String, expected: String) {
        if (requireAttribute(element, name) != expected) samlAbort(SamlErrorCode.RESPONSE_INVALID)
    }

    private fun strictText(element: SamlXmlElement, maximumCharacters: Int): String {
        if (element.children.any { it !is SamlXmlText }) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        val value = element.children.filterIsInstance<SamlXmlText>().joinToString("") { it.value }
        if (value.isEmpty() || value.length > maximumCharacters || value != value.trim()) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        return value
    }

    private fun parseInstant(value: String): Instant {
        if (!value.endsWith('Z') || value.length !in 20..40) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        return try {
            Instant.parse(value)
        } catch (_: IllegalArgumentException) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
    }

    private fun validateFreshIssueInstant(value: Instant, now: Instant) {
        if (value > now + config.clockSkew || now - value > config.maximumAssertionLifetime + config.clockSkew) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
    }

    private fun validateNotBefore(value: Instant, now: Instant) {
        if (now + config.clockSkew < value) samlAbort(SamlErrorCode.RESPONSE_INVALID)
    }

    private fun validateNotOnOrAfter(value: Instant, now: Instant) {
        if (now - config.clockSkew >= value) samlAbort(SamlErrorCode.RESPONSE_INVALID)
    }

    private fun allDescendants(root: SamlXmlElement): List<SamlXmlElement> {
        val result = mutableListOf<SamlXmlElement>()
        fun visit(element: SamlXmlElement) {
            element.children.filterIsInstance<SamlXmlElement>().forEach { child ->
                result += child
                visit(child)
            }
        }
        result += root
        visit(root)
        return result
    }
}
