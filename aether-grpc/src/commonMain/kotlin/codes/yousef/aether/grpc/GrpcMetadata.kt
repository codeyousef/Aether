package codes.yousef.aether.grpc

/**
 * Represents gRPC metadata (headers/trailers) with case-insensitive keys.
 * Supports multi-value entries for the same key.
 */
class GrpcMetadata {
    // Internal storage: lowercase key -> list of values
    // We also store the original key case for iteration
    private val entries = mutableMapOf<String, MutableList<String>>()
    private val originalKeys = mutableMapOf<String, String>()

    /** Returns true if the metadata contains no entries. */
    fun isEmpty(): Boolean = entries.isEmpty()

    /** Returns the number of unique keys. */
    val size: Int get() = entries.size

    /**
     * Gets the first value for the given key, or null if not present.
     * Key lookup is case-insensitive.
     */
    fun get(key: String): String? = entries[key.lowercase()]?.firstOrNull()

    /**
     * Gets all values for the given key.
     * Returns an empty list if the key is not present.
     * Key lookup is case-insensitive.
     */
    fun getAll(key: String): List<String> = entries[key.lowercase()]?.toList() ?: emptyList()

    /**
     * Sets a single value for the given key, replacing any existing values.
     * Key lookup is case-insensitive.
     */
    fun put(key: String, value: String) {
        val lowerKey = key.lowercase()
        entries[lowerKey] = mutableListOf(value)
        originalKeys[lowerKey] = key
    }

    /**
     * Adds a value for the given key without replacing existing values.
     * Key lookup is case-insensitive.
     */
    fun add(key: String, value: String) {
        val lowerKey = key.lowercase()
        entries.getOrPut(lowerKey) { mutableListOf() }.add(value)
        if (lowerKey !in originalKeys) {
            originalKeys[lowerKey] = key
        }
    }

    /**
     * Returns true if the metadata contains the given key.
     * Key lookup is case-insensitive.
     */
    fun contains(key: String): Boolean = key.lowercase() in entries

    /**
     * Removes all values for the given key.
     * Key lookup is case-insensitive.
     */
    fun remove(key: String) {
        val lowerKey = key.lowercase()
        entries.remove(lowerKey)
        originalKeys.remove(lowerKey)
    }

    /**
     * Returns all keys in the metadata.
     * Returns the original key casing.
     */
    fun keys(): Set<String> = originalKeys.values.toSet()

    /**
     * Copies all entries from another metadata into this one.
     */
    fun putAll(other: GrpcMetadata) {
        for (key in other.keys()) {
            val values = other.getAll(key)
            val lowerKey = key.lowercase()
            entries[lowerKey] = values.toMutableList()
            originalKeys[lowerKey] = key
        }
    }

    /**
     * Returns an immutable map representation.
     * Keys are in their original casing.
     */
    fun toMap(): Map<String, List<String>> {
        return originalKeys.mapValues { (lowerKey, _) ->
            entries[lowerKey]?.toList() ?: emptyList()
        }.mapKeys { (lowerKey, _) ->
            originalKeys[lowerKey] ?: lowerKey
        }
    }

    /**
     * Removes all entries from the metadata.
     */
    fun clear() {
        entries.clear()
        originalKeys.clear()
    }

    companion object {
        /**
         * Creates a GrpcMetadata from key-value pairs.
         */
        fun of(vararg pairs: Pair<String, String>): GrpcMetadata {
            val metadata = GrpcMetadata()
            for ((key, value) in pairs) {
                metadata.put(key, value)
            }
            return metadata
        }
    }
}
