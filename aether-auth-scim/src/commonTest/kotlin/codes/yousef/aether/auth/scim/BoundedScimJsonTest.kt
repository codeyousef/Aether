package codes.yousef.aether.auth.scim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BoundedScimJsonTest {
    @Test
    fun rejectsDuplicateKeysIncludingEscapedAliases() {
        val parser = BoundedScimJson()
        assertFailsWith<ScimJsonException> {
            parser.parse("{\"userName\":\"a\",\"user\\u004eame\":\"b\"}".encodeToByteArray())
        }
    }

    @Test
    fun rejectsExcessiveDepthAndInputBeforeTypedDecoding() {
        val parser = BoundedScimJson(ScimJsonLimits(maximumBytes = 64, maximumDepth = 3))
        assertFailsWith<ScimJsonException> { parser.parse("[[[[]]]]".encodeToByteArray()) }
        assertFailsWith<ScimJsonException> { parser.parse(ByteArray(65) { 'x'.code.toByte() }) }
    }

    @Test
    fun decodesUnicodeAndNumbersWithoutTurningNumbersIntoStrings() {
        val parsed = BoundedScimJson().parse("{\"name\":\"A\\u006cice\",\"n\":2}".encodeToByteArray()).jsonObject
        assertEquals("Alice", parsed.getValue("name").jsonPrimitive.content)
        assertEquals(2, parsed.getValue("n").jsonPrimitive.content.toInt())
        assertEquals(false, parsed.getValue("n").jsonPrimitive.isString)
    }
}
