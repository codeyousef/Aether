package codes.yousef.aether.auth

import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityIdFactoryTest {
    @Test
    fun `factory emits canonical UUIDv7 with RFC variant`() {
        val instant = Instant.parse("2026-07-14T00:00:00Z")
        val factory = IdentityIdFactory(
            clock = IdentityClock { instant },
            random = IdentitySecureRandom { size -> ByteArray(size) { it.toByte() } }
        )

        val id = factory.newUserId().value

        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id))
        val timestampHex = id.replace("-", "").take(12)
        assertEquals(instant.toEpochMilliseconds(), timestampHex.toLong(16))
    }
}
