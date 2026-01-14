package codes.yousef.aether.core.context

import codes.yousef.aether.core.auth.Principal
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Coroutine context element that holds the authenticated principal.
 * This allows authentication information to propagate through coroutine context,
 * making it accessible from any suspend function without passing Exchange around.
 *
 * Usage:
 * ```kotlin
 * withContext(UserContext(principal)) {
 *     val user = currentUser()
 *     // ... do work with authenticated user
 * }
 * ```
 */
class UserContext(
    val principal: Principal
) : AbstractCoroutineContextElement(UserContext) {
    companion object Key : CoroutineContext.Key<UserContext>
}

/**
 * Returns the current authenticated user from the coroutine context, or null if not authenticated.
 * This function can be called from any suspend function to get the current user.
 */
suspend fun currentUser(): Principal? {
    return coroutineContext[UserContext]?.principal
}

/**
 * Returns the current authenticated user from the coroutine context.
 * Throws [IllegalStateException] if no user is authenticated.
 */
suspend fun requireUser(): Principal {
    return currentUser() ?: throw IllegalStateException("No authenticated user in context")
}

/**
 * Returns true if there is an authenticated user in the current coroutine context.
 */
suspend fun isAuthenticated(): Boolean {
    return currentUser() != null
}

/**
 * Checks if the current user has the specified role.
 * Returns false if no user is authenticated.
 */
suspend fun hasRole(role: String): Boolean {
    return currentUser()?.hasRole(role) == true
}

/**
 * Checks if the current user has any of the specified roles.
 * Returns false if no user is authenticated.
 */
suspend fun hasAnyRole(vararg roles: String): Boolean {
    return currentUser()?.hasAnyRole(*roles) == true
}

/**
 * Checks if the current user has all of the specified roles.
 * Returns false if no user is authenticated.
 */
suspend fun hasAllRoles(vararg roles: String): Boolean {
    return currentUser()?.hasAllRoles(*roles) == true
}

/**
 * Checks if the current user has the specified permission.
 * Returns false if no user is authenticated.
 */
suspend fun hasPermission(permission: String): Boolean {
    return currentUser()?.hasPermission(permission) == true
}

/**
 * Checks if the current user has any of the specified permissions.
 * Returns false if no user is authenticated.
 */
suspend fun hasAnyPermission(vararg permissions: String): Boolean {
    return currentUser()?.hasAnyPermission(*permissions) == true
}

/**
 * Checks if the current user has all of the specified permissions.
 * Returns false if no user is authenticated.
 */
suspend fun hasAllPermissions(vararg permissions: String): Boolean {
    return currentUser()?.hasAllPermissions(*permissions) == true
}
