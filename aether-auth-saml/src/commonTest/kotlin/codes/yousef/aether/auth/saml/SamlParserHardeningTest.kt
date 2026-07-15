package codes.yousef.aether.auth.saml

import codes.yousef.aether.auth.OrganizationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SamlParserHardeningTest {
    @Test
    fun providerConfigurationCannotExceedAuthorityWideXmlCeilings() {
        assertFailsWith<IllegalArgumentException> { providerConfig(maximumXmlDepth = 26) }
        assertFailsWith<IllegalArgumentException> { providerConfig(maximumAttributesPerElement = 31) }

        providerConfig(maximumXmlDepth = 25, maximumAttributesPerElement = 30)
    }

    @Test
    fun parserRejectsDepthAndAttributeExhaustionAtTheBoundary() {
        val limits = SamlXmlLimits(
            maximumBytes = 16_384,
            maximumDepth = 25,
            maximumElements = 128,
            maximumAttributesPerElement = 30,
            maximumTextCharacters = 4_096
        )
        val tooDeep = buildString {
            repeat(26) { append("<n>") }
            repeat(26) { append("</n>") }
        }
        val depthFailure = assertFailsWith<SamlAbort> {
            BoundedSamlXml.parse(tooDeep.encodeToByteArray(), limits)
        }
        assertEquals(SamlErrorCode.RESPONSE_INVALID, depthFailure.code)

        val tooManyAttributes = buildString {
            append("<n")
            repeat(31) { index -> append(" a").append(index).append("=\"").append(index).append('"') }
            append("/>")
        }
        val attributeFailure = assertFailsWith<SamlAbort> {
            BoundedSamlXml.parse(tooManyAttributes.encodeToByteArray(), limits)
        }
        assertEquals(SamlErrorCode.RESPONSE_INVALID, attributeFailure.code)
    }

    private fun providerConfig(
        maximumXmlDepth: Int = 25,
        maximumAttributesPerElement: Int = 30
    ): SamlProviderConfig = SamlProviderConfig(
        tenantId = OrganizationId("00000000-0000-7000-8000-000000000001"),
        providerId = "workforce",
        spEntityId = "https://sp.example.test/saml",
        idpEntityId = "https://idp.example.test/saml",
        assertionConsumerServiceUrl = "https://sp.example.test/identity/v1/federation/" +
            "00000000-0000-7000-8000-000000000001/workforce/callback",
        maximumXmlDepth = maximumXmlDepth,
        maximumAttributesPerElement = maximumAttributesPerElement
    )
}
