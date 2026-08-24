package codes.yousef.aether.browser

import kotlinx.serialization.Serializable

/** Immutable, typed projection of the current browser location. */
@Serializable
data class BrowserLocationSnapshot(
    val href: String,
    val origin: String,
    val pathname: String,
    val search: String,
    val hash: String
)

object BrowserLocation {
    fun current(): BrowserLocationSnapshot = readPlatformBrowserLocation()
}

/** History operations which keep application code independent of DOM/browser globals. */
object BrowserHistory {
    fun push(path: String) {
        requireSameOriginNavigationPath(path)
        pushPlatformBrowserHistory(path)
    }

    fun replace(path: String) {
        requireSameOriginNavigationPath(path)
        replacePlatformBrowserHistory(path)
    }

    fun back() = go(-1)

    fun forward() = go(1)

    fun go(delta: Int) {
        require(delta in -100..100) { "Browser history delta must be between -100 and 100" }
        goPlatformBrowserHistory(delta)
    }
}

internal fun requireSameOriginNavigationPath(path: String) {
    require(path.isNotBlank()) { "Browser navigation path must not be blank" }
    require(path.startsWith('/') || path.startsWith('?') || path.startsWith('#')) {
        "Browser navigation paths must be same-origin relative paths"
    }
    require(!path.startsWith("//")) { "Protocol-relative browser navigation is not allowed" }
    require('\\' !in path) { "Browser navigation paths must not contain backslashes" }
    require(path.none(Char::isISOControl)) { "Browser navigation paths must not contain control characters" }
}
