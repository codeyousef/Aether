package codes.yousef.aether.grpc.annotation

/**
 * Marks a data class as a gRPC message.
 * The KSP processor will generate a corresponding .proto message definition.
 *
 * Example:
 * ```kotlin
 * @AetherMessage
 * data class User(
 *     @ProtoField(1) val id: String,
 *     @ProtoField(2) val name: String,
 *     @ProtoField(3) val email: String? = null
 * )
 * ```
 *
 * Generates:
 * ```protobuf
 * message User {
 *     string id = 1;
 *     string name = 2;
 *     optional string email = 3;
 * }
 * ```
 *
 * @param name Optional custom message name. Defaults to the class name.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AetherMessage(
    val name: String = ""
)
