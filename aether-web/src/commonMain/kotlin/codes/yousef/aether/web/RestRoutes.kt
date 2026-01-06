package codes.yousef.aether.web

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.respondJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Interface for a RESTful resource (ViewSet).
 */
interface ViewSet<T> {
    suspend fun list(exchange: Exchange): List<T>
    suspend fun create(exchange: Exchange): T
    suspend fun retrieve(exchange: Exchange, id: String): T?
    suspend fun update(exchange: Exchange, id: String): T
    suspend fun delete(exchange: Exchange, id: String): Boolean
}

class MethodNotAllowedException : Exception("Method Not Allowed")

/**
 * Base adapter for ViewSet with default implementations returning 405/404.
 */
abstract class BaseViewSet<T> : ViewSet<T> {
    override suspend fun list(exchange: Exchange): List<T> {
        throw MethodNotAllowedException()
    }

    override suspend fun create(exchange: Exchange): T {
        throw MethodNotAllowedException()
    }

    override suspend fun retrieve(exchange: Exchange, id: String): T? {
        // Default implementation for retrieve can simply return null (404)
        return null
    }

    override suspend fun update(exchange: Exchange, id: String): T {
        throw MethodNotAllowedException()
    }

    override suspend fun delete(exchange: Exchange, id: String): Boolean {
        throw MethodNotAllowedException()
    }
}

/**
 * Register a RESTful resource.
 *
 * Maps:
 * GET path -> list
 * POST path -> create
 * GET path/{id} -> retrieve
 * PUT path/{id} -> update
 * DELETE path/{id} -> delete
 */
fun <T> Router.resource(path: String, viewSet: ViewSet<T>, serializer: KSerializer<T>) {
    // Normalize path to remove trailing slash
    val basePath = if (path.endsWith("/")) path.dropLast(1) else path

    get(basePath) { exchange ->
        try {
            val list = viewSet.list(exchange)
            exchange.respondJson(200, list, serializer = ListSerializer(serializer))
        } catch (e: MethodNotAllowedException) {
            exchange.respond(405, "Method Not Allowed")
        }
    }

    post(basePath) { exchange ->
        try {
            val item = viewSet.create(exchange)
            exchange.respondJson(201, item, serializer = serializer)
        } catch (e: MethodNotAllowedException) {
            exchange.respond(405, "Method Not Allowed")
        } catch (e: Exception) {
            exchange.badRequest(e.message ?: "Bad Request")
        }
    }

    get("$basePath/:id") { exchange ->
        val id = exchange.pathParam("id")
        if (id == null) {
            exchange.badRequest("Missing ID")
        } else {
            try {
                val item = viewSet.retrieve(exchange, id)
                if (item != null) {
                    exchange.respondJson(200, item, serializer = serializer)
                } else {
                    exchange.notFound()
                }
            } catch (e: MethodNotAllowedException) {
                exchange.respond(405, "Method Not Allowed")
            }
        }
    }

    put("$basePath/:id") { exchange ->
        val id = exchange.pathParam("id")
        if (id == null) {
            exchange.badRequest("Missing ID")
        } else {
            try {
                val item = viewSet.update(exchange, id)
                exchange.respondJson(200, item, serializer = serializer)
            } catch (e: MethodNotAllowedException) {
                exchange.respond(405, "Method Not Allowed")
            } catch (e: Exception) {
                exchange.badRequest(e.message ?: "Bad Request")
            }
        }
    }

    delete("$basePath/:id") { exchange ->
        val id = exchange.pathParam("id")
        if (id == null) {
            exchange.badRequest("Missing ID")
        } else {
            try {
                val deleted = viewSet.delete(exchange, id)
                if (deleted) {
                    exchange.respond(204, "")
                } else {
                    exchange.notFound()
                }
            } catch (e: MethodNotAllowedException) {
                exchange.respond(405, "Method Not Allowed")
            }
        }
    }
}
