package codes.yousef.aether.grpc.service

/**
 * Describes a single gRPC method.
 */
data class GrpcMethodDescriptor(
    /** Method name (e.g., "GetUser") */
    val name: String,

    /** Full method name including service (e.g., "users.UserService/GetUser") */
    val fullName: String,

    /** Type of the method (unary, streaming, etc.) */
    val type: GrpcMethodType,

    /** Fully qualified input message type */
    val inputType: String,

    /** Fully qualified output message type */
    val outputType: String
)

/**
 * Describes a gRPC service.
 */
data class GrpcServiceDescriptor(
    /** Service name (e.g., "UserService") */
    val name: String,

    /** Full service name including package (e.g., "users.UserService") */
    val fullName: String,

    /** List of method descriptors in this service */
    val methods: List<GrpcMethodDescriptor>
) {
    /**
     * Find a method by name.
     */
    fun findMethod(name: String): GrpcMethodDescriptor? =
        methods.find { it.name == name }
}
