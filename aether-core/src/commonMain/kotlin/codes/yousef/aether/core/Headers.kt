package codes.yousef.aether.core

/**
 * Immutable HTTP headers with case-insensitive key lookup.
 * Headers can have multiple values per key.
 */
data class Headers(
    private val map: Map<String, List<String>>
) {
    /**
     * Get the first value for the given header name (case-insensitive).
     */
    operator fun get(name: String): String? = getAll(name).firstOrNull()

    /**
     * Get all values for the given header name (case-insensitive).
     */
    fun getAll(name: String): List<String> {
        val normalizedName = name.lowercase()
        return map.entries
            .firstOrNull { it.key.lowercase() == normalizedName }
            ?.value
            ?: emptyList()
    }

    /**
     * Check if a header exists (case-insensitive).
     */
    fun contains(name: String): Boolean {
        val normalizedName = name.lowercase()
        return map.keys.any { it.lowercase() == normalizedName }
    }

    /**
     * Get all header names.
     */
    fun names(): Set<String> = map.keys

    /**
     * Get all header entries.
     */
    fun entries(): Set<Map.Entry<String, List<String>>> = map.entries

    /**
     * Convert to a regular map.
     */
    fun toMap(): Map<String, List<String>> = map.toMap()

    companion object {
        val Empty = Headers(emptyMap())

        /**
         * Create headers from pairs.
         */
        fun of(vararg pairs: Pair<String, String>): Headers {
            val map = mutableMapOf<String, MutableList<String>>()
            for ((key, value) in pairs) {
                map.getOrPut(key) { mutableListOf() }.add(value)
            }
            return Headers(map)
        }

        /**
         * Create headers using a builder.
         */
        fun build(block: HeadersBuilder.() -> Unit): Headers {
            return HeadersBuilder().apply(block).build()
        }
    }

    /**
     * Builder for constructing Headers.
     */
    class HeadersBuilder {
        private val map = mutableMapOf<String, MutableList<String>>()

        /**
         * Add a header value (allows duplicates).
         */
        fun add(name: String, value: String) {
            map.getOrPut(name) { mutableListOf() }.add(value)
        }

        /**
         * Set a header value (replaces existing).
         */
        fun set(name: String, value: String) {
            map[name] = mutableListOf(value)
        }

        /**
         * Remove a header.
         */
        fun remove(name: String) {
            map.remove(name)
        }

        /**
         * Build the immutable Headers instance.
         */
        fun build(): Headers = Headers(map)
    }
}
