package codes.yousef.aether.fixture

import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.web.AetherServerConfig
import codes.yousef.aether.web.router

fun main() {
    val routes = router {
        get("/health") { exchange ->
            exchange.response.statusCode = 200
        }
    }
    check(routes.findRoute(HttpMethod.GET, "/health") != null)
    check(AetherServerConfig(host = "127.0.0.1", port = 8080).port == 8080)
}
