@file:OptIn(nl.adaptivity.xmlutil.ExperimentalXmlUtilApi::class)

package codes.yousef.aether.auth.saml

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming

internal data class SamlXmlName(
    val namespaceUri: String,
    val localName: String,
    val prefix: String
) {
    val qualifiedName: String get() = if (prefix.isEmpty()) localName else "$prefix:$localName"
}

internal data class SamlXmlAttribute(val name: SamlXmlName, val value: String)

internal sealed interface SamlXmlNode

internal class SamlXmlText(val value: String) : SamlXmlNode

internal class SamlXmlElement(
    val name: SamlXmlName,
    val attributes: List<SamlXmlAttribute>,
    val namespaceDeclarations: Map<String, String>,
    val inScopeNamespaces: Map<String, String>,
    internal val mutableChildren: MutableList<SamlXmlNode> = mutableListOf()
) : SamlXmlNode {
    val children: List<SamlXmlNode> get() = mutableChildren

    fun attribute(localName: String, namespaceUri: String = ""): String? =
        attributes.singleOrNull { it.name.localName == localName && it.name.namespaceUri == namespaceUri }?.value

    fun directElements(namespaceUri: String, localName: String): List<SamlXmlElement> =
        children.filterIsInstance<SamlXmlElement>().filter {
            it.name.namespaceUri == namespaceUri && it.name.localName == localName
        }

    fun singleDirectElement(namespaceUri: String, localName: String): SamlXmlElement =
        directElements(namespaceUri, localName).singleOrNull() ?: samlAbort(SamlErrorCode.RESPONSE_INVALID)

    fun optionalDirectElement(namespaceUri: String, localName: String): SamlXmlElement? {
        val matches = directElements(namespaceUri, localName)
        if (matches.size > 1) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        return matches.singleOrNull()
    }

    fun normalizedText(maximumCharacters: Int = 8_192): String {
        require(maximumCharacters > 0)
        if (children.any { it !is SamlXmlText }) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        val value = children.filterIsInstance<SamlXmlText>().joinToString("") { it.value }.trim()
        if (value.isEmpty() || value.length > maximumCharacters) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        return value
    }
}

internal data class SamlXmlDocument(
    val root: SamlXmlElement,
    val elementsById: Map<String, SamlXmlElement>,
    val elementCount: Int,
    val textCharacters: Int
)

internal data class SamlXmlLimits(
    val maximumBytes: Int,
    val maximumDepth: Int,
    val maximumElements: Int,
    val maximumAttributesPerElement: Int,
    val maximumTextCharacters: Int,
    val maximumAttributeCharacters: Int = 16_384
)

internal object BoundedSamlXml {
    private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"

    fun parse(bytes: ByteArray, limits: SamlXmlLimits): SamlXmlDocument {
        if (bytes.isEmpty() || bytes.size > limits.maximumBytes || hasForbiddenMarkup(bytes)) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        val xml = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Throwable) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        if (xml.length > limits.maximumBytes) samlAbort(SamlErrorCode.RESPONSE_INVALID)

        val reader = try {
            // DTD/entity declarations were rejected above, so expanding the five predefined XML
            // entities is safe and keeps ordinary escaped issuer/attribute values interoperable.
            xmlStreaming.newReader(xml, expandEntities = true)
        } catch (_: Throwable) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        val stack = mutableListOf<SamlXmlElement>()
        val ids = mutableMapOf<String, SamlXmlElement>()
        var root: SamlXmlElement? = null
        var elementCount = 0
        var textCharacters = 0
        var documentEnded = false
        try {
            while (reader.hasNext()) {
                val event = try {
                    reader.next()
                } catch (_: Throwable) {
                    samlAbort(SamlErrorCode.RESPONSE_INVALID)
                }
                when (event) {
                    EventType.START_DOCUMENT -> if (root != null || stack.isNotEmpty()) {
                        samlAbort(SamlErrorCode.RESPONSE_INVALID)
                    }

                    EventType.START_ELEMENT -> {
                        if (documentEnded || stack.size + 1 > limits.maximumDepth) {
                            samlAbort(SamlErrorCode.RESPONSE_INVALID)
                        }
                        elementCount++
                        val namespaceDeclarationCount = reader.namespaceDecls.size
                        if (elementCount > limits.maximumElements ||
                            reader.attributeCount + namespaceDeclarationCount > limits.maximumAttributesPerElement
                        ) {
                            samlAbort(SamlErrorCode.RESPONSE_INVALID)
                        }
                        val declarations = linkedMapOf<String, String>()
                        reader.namespaceDecls.forEach { namespace ->
                            val prefix = namespace.prefix
                            val uri = namespace.namespaceURI
                            if (prefix.length > 128 || uri.length > 2_048 || declarations.put(prefix, uri) != null) {
                                samlAbort(SamlErrorCode.RESPONSE_INVALID)
                            }
                            if (prefix == "xml" && uri != XML_NAMESPACE) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                        }
                        val inScope = linkedMapOf("xml" to XML_NAMESPACE)
                        stack.lastOrNull()?.inScopeNamespaces?.let(inScope::putAll)
                        declarations.forEach { (prefix, uri) -> inScope[prefix] = uri }

                        val name = SamlXmlName(
                            namespaceUri = reader.namespaceURI,
                            localName = reader.localName,
                            prefix = reader.prefix
                        )
                        validateName(name, inScope)
                        val attributes = ArrayList<SamlXmlAttribute>(reader.attributeCount)
                        val expandedNames = mutableSetOf<Pair<String, String>>()
                        var idValue: String? = null
                        repeat(reader.attributeCount) { index ->
                            val attributeName = SamlXmlName(
                                namespaceUri = reader.getAttributeNamespace(index),
                                localName = reader.getAttributeLocalName(index),
                                prefix = reader.getAttributePrefix(index)
                            )
                            validateName(attributeName, inScope, attribute = true)
                            val value = reader.getAttributeValue(index)
                            if (value.length > limits.maximumAttributeCharacters ||
                                !expandedNames.add(attributeName.namespaceUri to attributeName.localName)
                            ) {
                                samlAbort(SamlErrorCode.RESPONSE_INVALID)
                            }
                            val attribute = SamlXmlAttribute(attributeName, value)
                            attributes += attribute
                            if (isIdAttribute(attributeName)) {
                                if (idValue != null || !isValidXmlId(value)) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                                idValue = value
                            }
                        }
                        val element = SamlXmlElement(name, attributes, declarations, inScope)
                        idValue?.let { id ->
                            if (ids.put(id, element) != null) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                        }
                        if (stack.isEmpty()) {
                            if (root != null) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                            root = element
                        } else {
                            stack.last().mutableChildren += element
                        }
                        stack += element
                    }

                    EventType.END_ELEMENT -> {
                        val current = stack.removeLastOrNull() ?: samlAbort(SamlErrorCode.RESPONSE_INVALID)
                        if (current.name.namespaceUri != reader.namespaceURI ||
                            current.name.localName != reader.localName || current.name.prefix != reader.prefix
                        ) {
                            samlAbort(SamlErrorCode.RESPONSE_INVALID)
                        }
                    }

                    EventType.TEXT, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE -> {
                        val value = reader.text
                        textCharacters += value.length
                        if (textCharacters > limits.maximumTextCharacters) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                        if (stack.isEmpty()) {
                            if (value.any { !it.isWhitespace() }) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                        } else if (value.isNotEmpty()) {
                            stack.last().mutableChildren += SamlXmlText(value)
                        }
                    }

                    EventType.COMMENT -> Unit // Exclusive c14n without comments intentionally omits comments.
                    EventType.END_DOCUMENT -> {
                        if (stack.isNotEmpty() || root == null) samlAbort(SamlErrorCode.RESPONSE_INVALID)
                        documentEnded = true
                    }

                    EventType.DOCDECL, EventType.ENTITY_REF, EventType.PROCESSING_INSTRUCTION,
                    EventType.ATTRIBUTE -> samlAbort(SamlErrorCode.RESPONSE_INVALID)
                }
            }
        } catch (abort: SamlAbort) {
            throw abort
        } catch (_: Throwable) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        } finally {
            try {
                reader.close()
            } catch (_: Throwable) {
                // A parser close failure cannot make rejected input valid.
            }
        }
        if (stack.isNotEmpty() || root == null || !documentEnded) samlAbort(SamlErrorCode.RESPONSE_INVALID)
        return SamlXmlDocument(root, ids.toMap(), elementCount, textCharacters)
    }

    private fun validateName(name: SamlXmlName, inScope: Map<String, String>, attribute: Boolean = false) {
        if (name.localName.isEmpty() || name.localName.length > 256 || name.prefix.length > 128 ||
            name.namespaceUri.length > 2_048
        ) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        if (name.prefix.isNotEmpty() && inScope[name.prefix] != name.namespaceUri) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        if (!attribute && name.prefix.isEmpty() && (inScope[""] ?: "") != name.namespaceUri) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        if (attribute && name.prefix.isEmpty() && name.namespaceUri.isNotEmpty()) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
    }

    private fun isIdAttribute(name: SamlXmlName): Boolean =
        (name.namespaceUri.isEmpty() && name.localName in setOf("ID", "Id", "id")) ||
            (name.namespaceUri == XML_NAMESPACE && name.localName == "id")

    private fun isValidXmlId(value: String): Boolean {
        if (value.isEmpty() || value.length > 255 || value[0] != '_' && !value[0].isLetter()) return false
        return value.drop(1).all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }

    private fun hasForbiddenMarkup(bytes: ByteArray): Boolean {
        // XML declarations may vary in case, so scan ASCII without allocating another full document.
        val patterns = listOf("<!DOCTYPE", "<!ENTITY")
        return patterns.any { pattern ->
            outer@ for (start in 0..bytes.size - pattern.length) {
                for (offset in pattern.indices) {
                    val actual = bytes[start + offset].toInt() and 0xff
                    val expected = pattern[offset].code
                    val folded = if (actual in 'a'.code..'z'.code) actual - 32 else actual
                    if (folded != expected) continue@outer
                }
                return@any true
            }
            false
        }
    }
}

internal fun canonicalizeExclusive(
    element: SamlXmlElement,
    excludedElement: SamlXmlElement? = null
): ByteArray {
    val output = StringBuilder()
    appendCanonicalElement(element, excludedElement, emptyMap(), output)
    return output.toString().encodeToByteArray()
}

private fun appendCanonicalElement(
    element: SamlXmlElement,
    excludedElement: SamlXmlElement?,
    renderedNamespaces: Map<String, String>,
    output: StringBuilder
) {
    if (element === excludedElement) return
    output.append('<').append(element.name.qualifiedName)

    val visiblyUsedPrefixes = linkedSetOf(element.name.prefix)
    element.attributes.forEach { attribute ->
        if (attribute.name.prefix.isNotEmpty() && attribute.name.prefix != "xml") {
            visiblyUsedPrefixes += attribute.name.prefix
        }
    }
    val declarations = visiblyUsedPrefixes.map { prefix ->
        val uri = element.inScopeNamespaces[prefix]
            ?: if (prefix.isEmpty()) "" else samlAbort(SamlErrorCode.SIGNATURE_INVALID)
        prefix to uri
    }.filter { (prefix, uri) -> renderedNamespaces[prefix] != uri }
        .sortedBy { it.first }
    val nextRendered = renderedNamespaces.toMutableMap()
    declarations.forEach { (prefix, uri) ->
        if (prefix.isEmpty()) output.append(" xmlns=\"")
        else output.append(" xmlns:").append(prefix).append("=\"")
        output.append(escapeXmlAttribute(uri)).append('"')
        nextRendered[prefix] = uri
    }

    element.attributes.sortedWith(
        compareBy<SamlXmlAttribute>({ it.name.namespaceUri }, { it.name.localName })
    ).forEach { attribute ->
        output.append(' ').append(attribute.name.qualifiedName).append("=\"")
            .append(escapeXmlAttribute(attribute.value)).append('"')
    }
    output.append('>')
    element.children.forEach { child ->
        when (child) {
            is SamlXmlElement -> appendCanonicalElement(child, excludedElement, nextRendered, output)
            is SamlXmlText -> output.append(escapeXmlText(child.value))
        }
    }
    output.append("</").append(element.name.qualifiedName).append('>')
}
