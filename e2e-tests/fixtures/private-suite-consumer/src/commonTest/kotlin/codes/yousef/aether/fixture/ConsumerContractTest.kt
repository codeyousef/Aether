package codes.yousef.aether.fixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class ConsumerContractTest {
    @Test
    fun healthPayloadRoundTripsThroughPinnedSerializationStack() {
        val encoded = Json.encodeToString(HealthPayload.serializer(), HealthPayload(EXPECTED_HEALTH_STATUS))
        assertEquals(HealthPayload(EXPECTED_HEALTH_STATUS), Json.decodeFromString(HealthPayload.serializer(), encoded))
    }
}
