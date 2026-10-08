package codes.yousef.aether.auth

import codes.yousef.aether.core.HttpMethod

/**
 * Maps an application-owned HTTP path to one canonical [IdentityHttpApi] path.
 *
 * Return `null` for routes the adapter does not own. Mapping changes routing only: request
 * authority, cookies, CSRF, body limits, connection metadata, and the response remain attached to
 * the original exchange.
 */
fun interface IdentityHttpRouteAdapter {
    fun canonicalPath(method: HttpMethod, path: String): String?

    companion object {
        /** Uses only Aether Identity's canonical route paths. */
        val CANONICAL: IdentityHttpRouteAdapter = IdentityHttpRouteAdapter { _, path -> path }
    }
}
