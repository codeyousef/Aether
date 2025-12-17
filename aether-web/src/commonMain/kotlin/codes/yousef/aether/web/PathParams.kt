package codes.yousef.aether.web

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Attributes
import codes.yousef.aether.core.Exchange

/**
 * Attribute key for storing path parameters in Exchange.attributes.
 */
private val PATH_PARAMS_KEY = Attributes.key<MutableMap<String, String>>("path_params")

/**
 * Internal function to set a path parameter on an Exchange.
 * Used by the Router to store extracted path parameters.
 */
internal fun Exchange.setPathParam(name: String, value: String) {
    val params = attributes.getOrPut(PATH_PARAMS_KEY) { mutableMapOf() }
    params[name] = value
}

/**
 * Get a single path parameter by name.
 *
 * Example:
 * ```
 * router {
 *     get("/users/:id") { exchange ->
 *         val id = exchange.pathParam("id")
 *         exchange.respond("User ID: $id")
 *     }
 * }
 * ```
 *
 * @param name The name of the path parameter (without the : prefix)
 * @return The parameter value, or null if not found
 */
fun Exchange.pathParam(name: String): String? {
    return attributes.get(PATH_PARAMS_KEY)?.get(name)
}

/**
 * Get a single path parameter by name, or throw an exception if not found.
 *
 * Example:
 * ```
 * router {
 *     get("/users/:id") { exchange ->
 *         val id = exchange.pathParamOrThrow("id")
 *         exchange.respond("User ID: $id")
 *     }
 * }
 * ```
 *
 * @param name The name of the path parameter (without the : prefix)
 * @return The parameter value
 * @throws IllegalStateException if the parameter is not found
 */
fun Exchange.pathParamOrThrow(name: String): String {
    return pathParam(name) ?: throw IllegalStateException("Path parameter '$name' not found")
}

/**
 * Get all path parameters as a map.
 *
 * Example:
 * ```
 * router {
 *     get("/users/:userId/posts/:postId") { exchange ->
 *         val params = exchange.pathParams()
 *         val userId = params["userId"]
 *         val postId = params["postId"]
 *         exchange.respond("User $userId, Post $postId")
 *     }
 * }
 * ```
 *
 * @return Map of parameter names to values (empty map if no parameters)
 */
fun Exchange.pathParams(): Map<String, String> {
    return attributes.get(PATH_PARAMS_KEY) ?: emptyMap()
}

/**
 * Get a path parameter as an Int, or null if not found or not a valid Int.
 *
 * Example:
 * ```
 * router {
 *     get("/users/:id") { exchange ->
 *         val id = exchange.pathParamInt("id")
 *         if (id != null) {
 *             exchange.respond("User ID: $id")
 *         } else {
 *             exchange.badRequest("Invalid user ID")
 *         }
 *     }
 * }
 * ```
 *
 * @param name The name of the path parameter
 * @return The parameter value as an Int, or null if not found or invalid
 */
fun Exchange.pathParamInt(name: String): Int? {
    return pathParam(name)?.toIntOrNull()
}

/**
 * Get a path parameter as a Long, or null if not found or not a valid Long.
 *
 * Example:
 * ```
 * router {
 *     get("/users/:id") { exchange ->
 *         val id = exchange.pathParamLong("id")
 *         if (id != null) {
 *             exchange.respond("User ID: $id")
 *         } else {
 *             exchange.badRequest("Invalid user ID")
 *         }
 *     }
 * }
 * ```
 *
 * @param name The name of the path parameter
 * @return The parameter value as a Long, or null if not found or invalid
 */
fun Exchange.pathParamLong(name: String): Long? {
    return pathParam(name)?.toLongOrNull()
}

/**
 * Get a path parameter as a Double, or null if not found or not a valid Double.
 *
 * @param name The name of the path parameter
 * @return The parameter value as a Double, or null if not found or invalid
 */
fun Exchange.pathParamDouble(name: String): Double? {
    return pathParam(name)?.toDoubleOrNull()
}

/**
 * Get a path parameter as a Boolean, or null if not found or not a valid Boolean.
 * Accepts "true" or "false" (case-insensitive).
 *
 * @param name The name of the path parameter
 * @return The parameter value as a Boolean, or null if not found or invalid
 */
fun Exchange.pathParamBoolean(name: String): Boolean? {
    return pathParam(name)?.toBooleanStrictOrNull()
}
