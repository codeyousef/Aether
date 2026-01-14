package codes.yousef.aether.ksp

/**
 * Represents a Protocol Buffers field definition.
 *
 * @param name The field name (snake_case recommended)
 * @param type The proto type (e.g., "string", "int32", "User")
 * @param number The field number (must be unique within message)
 * @param optional Whether the field is optional (proto3 optional keyword)
 * @param repeated Whether the field is repeated (already included in type if true)
 * @param deprecated Whether the field is deprecated
 */
data class ProtoFieldInfo(
    val name: String,
    val type: String,
    val number: Int,
    val optional: Boolean = false,
    val repeated: Boolean = false,
    val deprecated: Boolean = false
)

/**
 * Represents a Protocol Buffers message definition.
 *
 * @param name The message name (PascalCase)
 * @param fields The list of fields in the message
 * @param nestedMessages Nested message definitions
 * @param nestedEnums Nested enum definitions
 */
data class ProtoMessageInfo(
    val name: String,
    val fields: List<ProtoFieldInfo> = emptyList(),
    val nestedMessages: List<ProtoMessageInfo> = emptyList(),
    val nestedEnums: List<ProtoEnumInfo> = emptyList()
)

/**
 * Represents a Protocol Buffers enum definition.
 *
 * @param name The enum name (PascalCase)
 * @param values The enum values with their numbers
 */
data class ProtoEnumInfo(
    val name: String,
    val values: List<ProtoEnumValue>
)

/**
 * Represents a Protocol Buffers enum value.
 *
 * @param name The value name (SCREAMING_SNAKE_CASE)
 * @param number The value number (first value must be 0)
 */
data class ProtoEnumValue(
    val name: String,
    val number: Int
)

/**
 * Represents a Protocol Buffers RPC method definition.
 *
 * @param name The method name (PascalCase)
 * @param inputType The request message type
 * @param outputType The response message type
 * @param clientStreaming Whether the client sends a stream of messages
 * @param serverStreaming Whether the server sends a stream of messages
 * @param deprecated Whether the method is deprecated
 */
data class ProtoMethodInfo(
    val name: String,
    val inputType: String,
    val outputType: String,
    val clientStreaming: Boolean = false,
    val serverStreaming: Boolean = false,
    val deprecated: Boolean = false
)

/**
 * Represents a Protocol Buffers service definition.
 *
 * @param name The service name (PascalCase)
 * @param methods The list of RPC methods
 */
data class ProtoServiceInfo(
    val name: String,
    val methods: List<ProtoMethodInfo> = emptyList()
)

/**
 * Represents a complete Protocol Buffers file.
 *
 * @param packageName The proto package name (e.g., "users.v1")
 * @param messages The message definitions
 * @param services The service definitions
 * @param imports The import paths for external proto files
 * @param options File-level options
 */
data class ProtoFileInfo(
    val packageName: String,
    val messages: List<ProtoMessageInfo> = emptyList(),
    val services: List<ProtoServiceInfo> = emptyList(),
    val imports: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap()
)
