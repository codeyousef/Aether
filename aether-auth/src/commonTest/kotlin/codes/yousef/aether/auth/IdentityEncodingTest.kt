package codes.yousef.aether.auth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdentityEncodingTest {
    @Test
    fun `base64url matches RFC 4648 vectors without padding`() {
        val vectors = mapOf(
            "" to "",
            "f" to "Zg",
            "fo" to "Zm8",
            "foo" to "Zm9v",
            "foob" to "Zm9vYg",
            "fooba" to "Zm9vYmE",
            "foobar" to "Zm9vYmFy"
        )
        vectors.forEach { (plain, encoded) ->
            assertEquals(encoded, Base64Url.encode(plain.encodeToByteArray()))
            assertContentEquals(plain.encodeToByteArray(), Base64Url.decode(encoded))
        }
    }

    @Test
    fun `decoder rejects padding invalid alphabet and noncanonical trailing bits`() {
        listOf("Zg=", "Zg+", "Z", "Zh").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) { Base64Url.decode(invalid) }
        }
    }

    @Test
    fun `decoder enforces byte bound before allocation`() {
        assertFailsWith<IllegalArgumentException> { Base64Url.decode("Zm9v", maximumBytes = 2) }
    }
}
