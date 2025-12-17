package codes.yousef.aether.core

import kotlinx.serialization.Serializable

/**
 * Represents an HTTP cookie with all standard attributes.
 */
@Serializable
data class Cookie(
    val name: String,
    val value: String,
    val path: String? = null,
    val domain: String? = null,
    val maxAge: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: SameSite? = null
) {
    enum class SameSite {
        STRICT,
        LAX,
        NONE
    }

    /**
     * Convert to Set-Cookie header value.
     */
    fun toSetCookieHeader(): String = buildString {
        append("$name=$value")
        path?.let { append("; Path=$it") }
        domain?.let { append("; Domain=$it") }
        maxAge?.let { append("; Max-Age=$it") }
        if (secure) append("; Secure")
        if (httpOnly) append("; HttpOnly")
        sameSite?.let {
            append("; SameSite=")
            append(when (it) {
                SameSite.STRICT -> "Strict"
                SameSite.LAX -> "Lax"
                SameSite.NONE -> "None"
            })
        }
    }
}

/**
 * Immutable collection of cookies.
 */
data class Cookies(
    private val map: Map<String, Cookie>
) {
    /**
     * Get a cookie by name.
     */
    operator fun get(name: String): Cookie? = map[name]

    /**
     * Check if a cookie exists.
     */
    fun contains(name: String): Boolean = map.containsKey(name)

    /**
     * Get all cookie names.
     */
    fun names(): Set<String> = map.keys

    /**
     * Get all cookies.
     */
    fun all(): Collection<Cookie> = map.values

    /**
     * Convert to a regular map.
     */
    fun toMap(): Map<String, Cookie> = map.toMap()

    companion object {
        val Empty = Cookies(emptyMap())

        /**
         * Create cookies from varargs.
         */
        fun of(vararg cookies: Cookie): Cookies {
            return Cookies(cookies.associateBy { it.name })
        }

        /**
         * Parse cookies from Cookie header value.
         * Format: "name1=value1; name2=value2"
         */
        fun parse(cookieHeader: String?): Cookies {
            if (cookieHeader.isNullOrBlank()) return Empty

            val cookiesMap = cookieHeader.split(";")
                .mapNotNull { part ->
                    val trimmed = part.trim()
                    val index = trimmed.indexOf('=')
                    if (index > 0) {
                        val name = trimmed.substring(0, index).trim()
                        val value = trimmed.substring(index + 1).trim()
                        Cookie(name, value)
                    } else {
                        null
                    }
                }
                .associateBy { it.name }

            return Cookies(cookiesMap)
        }
    }
}
