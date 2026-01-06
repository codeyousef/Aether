package codes.yousef.aether.web

import codes.yousef.aether.core.auth.requirePermissions
import codes.yousef.aether.core.auth.requireRoles
import codes.yousef.aether.core.pipeline.Middleware

/**
 * Extension for Router to support RBAC and Permission-based routes.
 */



/**
 * Wrapper for routes that require specific permissions.
 *
 * Usage:
 * ```kotlin
 * get("/admin", requirePermission("admin.access") { exchange ->
 *     exchange.respond("Secret Admin Area")
 * })
 * ```
 */
fun requirePermission(permission: String, handler: RouteHandler): RouteHandler {
    val middleware = requirePermissions(permission)
    return { exchange ->
        var nextCalled = false
        middleware(exchange) {
            nextCalled = true
        }
        if (nextCalled) {
            handler(exchange)
        }
    }
}

/**
 * Wrapper for routes that require any of the specific permissions.
 */
fun requireAnyPermission(vararg permissions: String, handler: RouteHandler): RouteHandler {
    val middleware = requirePermissions(*permissions, requireAll = false)
    return { exchange ->
        var nextCalled = false
        middleware(exchange) {
            nextCalled = true
        }
        if (nextCalled) {
            handler(exchange)
        }
    }
}

/**
 * Wrapper for routes that require all of the specific permissions.
 */
fun requireAllPermissions(vararg permissions: String, handler: RouteHandler): RouteHandler {
    val middleware = requirePermissions(*permissions, requireAll = true)
    return { exchange ->
        var nextCalled = false
        middleware(exchange) {
            nextCalled = true
        }
        if (nextCalled) {
            handler(exchange)
        }
    }
}

/**
 * Wrapper for routes that require specific roles.
 */
fun requireRole(role: String, handler: RouteHandler): RouteHandler {
    val middleware = requireRoles(role)
    return { exchange ->
        var nextCalled = false
        middleware(exchange) {
            nextCalled = true
        }
        if (nextCalled) {
            handler(exchange)
        }
    }
}

/**
 * Wrapper for routes that require any of the specific roles.
 */
fun requireAnyRole(vararg roles: String, handler: RouteHandler): RouteHandler {
    val middleware = requireRoles(*roles, requireAll = false)
    return { exchange ->
        var nextCalled = false
        middleware(exchange) {
            nextCalled = true
        }
        if (nextCalled) {
            handler(exchange)
        }
    }
}

