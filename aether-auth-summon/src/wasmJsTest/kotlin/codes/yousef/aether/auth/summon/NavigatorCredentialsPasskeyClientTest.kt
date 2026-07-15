package codes.yousef.aether.auth.summon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NavigatorCredentialsPasskeyClientTest {
    @Test
    fun arrayBufferConversionPreservesCanonicalUnpaddedBase64Url() {
        val vectors = listOf(
            "AA",
            "AAE",
            "AAEC",
            "AAECAwQFBgcICQ",
            "-_8AAQ"
        )

        vectors.forEach { value ->
            val roundTripped = roundTripBrowserBase64Url(value)
            assertEquals(value, roundTripped)
            assertFalse('=' in roundTripped)
        }
    }
}
