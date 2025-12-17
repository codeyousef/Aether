package codes.yousef.aether.core

import kotlin.reflect.KClass

/**
 * Typed key for attribute storage.
 */
data class AttributeKey<T : Any>(val name: String, val type: KClass<T>)

/**
 * Type-safe attribute storage for request-scoped data.
 * Allows storing arbitrary data associated with a request.
 */
class Attributes {
    private val map = mutableMapOf<AttributeKey<*>, Any>()

    /**
     * Get an attribute value.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: AttributeKey<T>): T? = map[key] as? T

    /**
     * Put an attribute value.
     */
    fun <T : Any> put(key: AttributeKey<T>, value: T) {
        map[key] = value
    }

    /**
     * Get or put an attribute value.
     */
    fun <T : Any> getOrPut(key: AttributeKey<T>, defaultValue: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return map.getOrPut(key) { defaultValue() } as T
    }

    /**
     * Remove an attribute.
     */
    fun <T : Any> remove(key: AttributeKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return map.remove(key) as? T
    }

    /**
     * Check if an attribute exists.
     */
    fun contains(key: AttributeKey<*>): Boolean = map.containsKey(key)

    /**
     * Clear all attributes.
     */
    fun clear() {
        map.clear()
    }

    companion object {
        /**
         * Create a typed attribute key.
         */
        inline fun <reified T : Any> key(name: String): AttributeKey<T> = AttributeKey(name, T::class)
    }
}
