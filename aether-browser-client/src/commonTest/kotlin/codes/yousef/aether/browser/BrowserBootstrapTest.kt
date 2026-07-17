package codes.yousef.aether.browser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrowserBootstrapTest {
    @Test
    fun `stored JSON decodes through the same bounded path as DOM bootstrap data`() {
        val decoded = decodeStoredBootstrap(
            stored = """{"value":42}""",
            deserializer = BootstrapState.serializer(),
            encoding = BrowserBootstrapEncoding.JSON,
            maximumBytes = 1_024,
            json = Json
        )

        assertEquals(BootstrapState(42), decoded)
    }

    @Test
    fun `stored bootstrap hard limit is measured in UTF-8 bytes`() {
        val failure = assertFailsWith<BrowserBootstrapException> {
            decodeStoredBootstrap(
                stored = """{"value":"éé"}""",
                deserializer = BootstrapText.serializer(),
                encoding = BrowserBootstrapEncoding.JSON,
                maximumBytes = 15,
                json = Json
            )
        }

        assertEquals(BrowserBootstrapFailure.TOO_LARGE, failure.failure)
    }

    @Test
    fun `invalid stored JSON has a typed failure without payload disclosure`() {
        val failure = assertFailsWith<BrowserBootstrapException> {
            decodeStoredBootstrap(
                stored = "sensitive-not-json",
                deserializer = BootstrapState.serializer(),
                encoding = BrowserBootstrapEncoding.JSON,
                maximumBytes = 1_024,
                json = Json
            )
        }

        assertEquals(BrowserBootstrapFailure.INVALID_JSON, failure.failure)
        check("sensitive-not-json" !in failure.message.orEmpty())
    }

    @Serializable
    private data class BootstrapState(val value: Int)

    @Serializable
    private data class BootstrapText(val value: String)
}
