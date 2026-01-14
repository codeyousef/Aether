package codes.yousef.aether.ksp

/**
 * Maps Kotlin types to Protocol Buffers types.
 *
 * Handles:
 * - Primitive types (Int -> int32, Long -> int64, etc.)
 * - Collections (List<T> -> repeated T)
 * - Nullable types (String? -> optional string)
 * - Custom message types (keep class name)
 */
class KotlinToProtoMapper {

    private val typeMapping = mapOf(
        "kotlin.Int" to "int32",
        "kotlin.Long" to "int64",
        "kotlin.Float" to "float",
        "kotlin.Double" to "double",
        "kotlin.Boolean" to "bool",
        "kotlin.String" to "string",
        "kotlin.ByteArray" to "bytes",
        "kotlin.UInt" to "uint32",
        "kotlin.ULong" to "uint64",
        "kotlin.Short" to "int32",
        "kotlin.Byte" to "int32",
        "kotlin.UShort" to "uint32",
        "kotlin.UByte" to "uint32",
        // Java types
        "java.lang.Integer" to "int32",
        "java.lang.Long" to "int64",
        "java.lang.Float" to "float",
        "java.lang.Double" to "double",
        "java.lang.Boolean" to "bool",
        "java.lang.String" to "string"
    )

    /**
     * Maps a Kotlin type to its proto equivalent.
     *
     * @param kotlinType The fully qualified Kotlin type name
     * @param typeArgument Optional type argument for generic types (e.g., List<String>)
     * @return The proto type string
     */
    fun mapType(kotlinType: String, typeArgument: String? = null): String {
        // Remove nullability marker for type lookup
        val baseType = kotlinType.removeSuffix("?")

        // Handle collection types
        if (baseType == "kotlin.collections.List" || baseType == "java.util.List") {
            val elementType = typeArgument?.let { mapType(it) } ?: "string"
            return "repeated $elementType"
        }

        // Handle Map types
        if (baseType == "kotlin.collections.Map" || baseType == "java.util.Map") {
            return "map<string, string>" // Default map type
        }

        // Check primitive type mapping
        typeMapping[baseType]?.let { return it }

        // For custom types, extract simple class name
        return baseType.substringAfterLast('.')
    }

    /**
     * Maps a Kotlin type with multiple type arguments.
     *
     * @param kotlinType The fully qualified Kotlin type name
     * @param typeArguments The type arguments for generic types
     * @return The proto type string
     */
    fun mapType(kotlinType: String, typeArguments: List<String>): String {
        val baseType = kotlinType.removeSuffix("?")

        // Handle Map types
        if (baseType == "kotlin.collections.Map" || baseType == "java.util.Map") {
            if (typeArguments.size >= 2) {
                val keyType = mapType(typeArguments[0])
                val valueType = mapType(typeArguments[1])
                return "map<$keyType, $valueType>"
            }
        }

        // Handle List types
        if (baseType == "kotlin.collections.List" || baseType == "java.util.List") {
            val elementType = typeArguments.firstOrNull()?.let { mapType(it) } ?: "string"
            return "repeated $elementType"
        }

        return mapType(kotlinType, typeArguments.firstOrNull())
    }

    /**
     * Determines if a Kotlin type should be marked as optional in proto3.
     *
     * @param kotlinType The fully qualified Kotlin type name (may include ?)
     * @return true if the type is nullable and should be optional
     */
    fun isOptional(kotlinType: String): Boolean {
        return kotlinType.endsWith("?")
    }

    /**
     * Determines if a Kotlin type represents a streaming type (Flow).
     *
     * @param kotlinType The fully qualified Kotlin type name
     * @return true if the type is a Flow
     */
    fun isStreaming(kotlinType: String): Boolean {
        val baseType = kotlinType.removeSuffix("?")
        return baseType == "kotlinx.coroutines.flow.Flow" ||
                baseType.endsWith(".Flow")
    }

    /**
     * Extracts the element type from a Flow type.
     *
     * @param flowType The Flow type string
     * @param typeArgument The type argument of the Flow
     * @return The mapped element type
     */
    fun extractStreamType(flowType: String, typeArgument: String): String {
        return mapType(typeArgument)
    }
}
