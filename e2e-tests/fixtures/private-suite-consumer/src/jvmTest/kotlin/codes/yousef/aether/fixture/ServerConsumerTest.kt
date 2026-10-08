package codes.yousef.aether.fixture

import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.web.AetherServerConfig
import codes.yousef.aether.web.router
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServerConsumerTest {
    @Test
    fun compilesAndUsesPinnedAetherJvmServerSurface() {
        val routes = router { get("/health") { it.response.statusCode = 200 } }
        assertNotNull(routes.findRoute(HttpMethod.GET, "/health"))
        assertEquals(1_048_576, AetherServerConfig(maxHeaderSize = 1_048_576).maxHeaderSize)
    }
}
