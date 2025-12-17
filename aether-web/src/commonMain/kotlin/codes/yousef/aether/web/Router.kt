package codes.yousef.aether.web

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.pipeline.Middleware

/**
 * HTTP Router with DSL for defining routes.
 *
 * Uses a Radix Tree per HTTP method for O(k) route matching.
 * Supports path parameters which are extracted into Exchange.attributes.
 *
 * Example:
 * ```
 * val router = router {
 *     get("/users") { exchange -> ... }
 *     get("/users/:id") { exchange -> ... }
 *     post("/users") { exchange -> ... }
 *     put("/users/:id") { exchange -> ... }
 *     delete("/users/:id") { exchange -> ... }
 * }
 * ```
 */
class Router {
    private val trees = mutableMapOf<HttpMethod, RadixTree<RouteHandler>>()

    init {
        HttpMethod.entries.forEach { method ->
            trees[method] = RadixTree()
        }
    }

    /**
     * Register a GET route.
     */
    fun get(path: String, handler: RouteHandler) {
        addRoute(HttpMethod.GET, path, handler)
    }

    /**
     * Register a POST route.
     */
    fun post(path: String, handler: RouteHandler) {
        addRoute(HttpMethod.POST, path, handler)
    }

    /**
     * Register a PUT route.
     */
    fun put(path: String, handler: RouteHandler) {
        addRoute(HttpMethod.PUT, path, handler)
    }

    /**
     * Register a DELETE route.
     */
    fun delete(path: String, handler: RouteHandler) {
        addRoute(HttpMethod.DELETE, path, handler)
    }

    /**
     * Register a PATCH route.
     */
    fun patch(path: String, handler: RouteHandler) {
        addRoute(HttpMethod.PATCH, path, handler)
    }

    /**
     * Register a HEAD route.
     */
    fun head(path: String, handler: RouteHandler) {
        addRoute(HttpMethod.HEAD, path, handler)
    }

    /**
     * Register an OPTIONS route.
     */
    fun options(path: String, handler: RouteHandler) {
        addRoute(HttpMethod.OPTIONS, path, handler)
    }

    /**
     * Register a route for a specific HTTP method.
     */
    fun addRoute(method: HttpMethod, path: String, handler: RouteHandler) {
        val tree = trees[method] ?: throw IllegalArgumentException("Unsupported HTTP method: $method")
        tree.insert(path, handler)
    }

    /**
     * Find a route handler for the given method and path.
     *
     * @return RouteMatch containing the handler and extracted parameters, or null if not found
     */
    fun findRoute(method: HttpMethod, path: String): RouteMatch<RouteHandler>? {
        val tree = trees[method] ?: return null
        return tree.search(path)
    }

    /**
     * Convert this router to middleware that can be used in a pipeline.
     *
     * The middleware will:
     * 1. Look up the route based on method and path
     * 2. Extract path parameters into Exchange.attributes
     * 3. Call the route handler if found
     * 4. Call next() if no route is found (allows chaining multiple routers)
     */
    fun asMiddleware(): Middleware = { exchange, next ->
        val match = findRoute(exchange.request.method, exchange.request.path)

        if (match != null) {
            match.params.forEach { (name, value) ->
                exchange.setPathParam(name, value)
            }

            match.value.invoke(exchange)
        } else {
            next()
        }
    }

    /**
     * Execute a request directly against this router without middleware pipeline.
     * Useful for testing.
     *
     * @return true if a route was found and executed, false otherwise
     */
    suspend fun handle(exchange: Exchange): Boolean {
        val match = findRoute(exchange.request.method, exchange.request.path)

        if (match != null) {
            match.params.forEach { (name, value) ->
                exchange.setPathParam(name, value)
            }

            match.value.invoke(exchange)
            return true
        }

        return false
    }
}

/**
 * Type alias for route handler functions.
 */
typealias RouteHandler = suspend (Exchange) -> Unit

/**
 * DSL function for creating a Router.
 *
 * Example:
 * ```
 * val router = router {
 *     get("/") { exchange ->
 *         exchange.respond("Hello, World!")
 *     }
 *
 *     get("/users/:id") { exchange ->
 *         val id = exchange.pathParam("id")
 *         exchange.respond("User ID: $id")
 *     }
 * }
 * ```
 */
fun router(block: Router.() -> Unit): Router {
    return Router().apply(block)
}
