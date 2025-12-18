package codes.yousef.aether.web

import codes.yousef.aether.core.websocket.WebSocketHandler
import codes.yousef.aether.core.websocket.WebSocketHandlerBuilder
import codes.yousef.aether.core.websocket.webSocketHandler

/**
 * WebSocket route definition.
 */
data class WebSocketRoute(
    val path: String,
    val handler: WebSocketHandler
)

/**
 * Extension to add WebSocket routes to the Router.
 */
private val webSocketRoutes = mutableMapOf<Router, MutableList<WebSocketRoute>>()

/**
 * Register a WebSocket endpoint.
 */
fun Router.ws(path: String, handler: WebSocketHandler) {
    val routes = webSocketRoutes.getOrPut(this) { mutableListOf() }
    routes.add(WebSocketRoute(path, handler))
}

/**
 * Register a WebSocket endpoint with DSL builder.
 */
fun Router.ws(path: String, block: WebSocketHandlerBuilder.() -> Unit) {
    ws(path, webSocketHandler(block))
}

/**
 * Get WebSocket routes for this router.
 */
fun Router.getWebSocketRoutes(): List<WebSocketRoute> {
    return webSocketRoutes[this]?.toList() ?: emptyList()
}

/**
 * Find a WebSocket handler for the given path.
 */
fun Router.findWebSocketHandler(path: String): WebSocketHandler? {
    val routes = webSocketRoutes[this] ?: return null
    
    for (route in routes) {
        if (matchWebSocketPath(route.path, path)) {
            return route.handler
        }
    }
    
    return null
}

/**
 * Match WebSocket path with support for path parameters.
 */
private fun matchWebSocketPath(pattern: String, path: String): Boolean {
    val patternParts = pattern.split("/").filter { it.isNotEmpty() }
    val pathParts = path.split("/").filter { it.isNotEmpty() }
    
    if (patternParts.size != pathParts.size) {
        return false
    }
    
    for (i in patternParts.indices) {
        val patternPart = patternParts[i]
        val pathPart = pathParts[i]
        
        // Path parameter
        if (patternPart.startsWith(":") || patternPart.startsWith("{")) {
            continue
        }
        
        if (patternPart != pathPart) {
            return false
        }
    }
    
    return true
}

/**
 * Extract path parameters from a WebSocket path.
 */
fun extractWebSocketPathParams(pattern: String, path: String): Map<String, String> {
    val params = mutableMapOf<String, String>()
    val patternParts = pattern.split("/").filter { it.isNotEmpty() }
    val pathParts = path.split("/").filter { it.isNotEmpty() }
    
    for (i in patternParts.indices) {
        val patternPart = patternParts[i]
        
        when {
            patternPart.startsWith(":") -> {
                val paramName = patternPart.substring(1)
                params[paramName] = pathParts[i]
            }
            patternPart.startsWith("{") && patternPart.endsWith("}") -> {
                val paramName = patternPart.substring(1, patternPart.length - 1)
                params[paramName] = pathParts[i]
            }
        }
    }
    
    return params
}
