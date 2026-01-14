package codes.yousef.aether.grpc.annotation

/**
 * Marks a property as a protobuf field with a specific field number.
 * Field numbers must be unique within a message and should not be reused
 * once a message is deployed.
 *
 * Example:
 * ```kotlin
 * @AetherMessage
 * data class User(
 *     @ProtoField(1) val id: String,
 *     @ProtoField(2) val name: String,
 *     @ProtoField(3, deprecated = true) val oldField: String = ""
 * )
 * ```
 *
 * @param id The field number (must be positive, unique within the message)
 * @param deprecated Whether the field is deprecated
 * @param json Custom JSON field name for serialization
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class ProtoField(
    val id: Int,
    val deprecated: Boolean = false,
    val json: String = ""
)
