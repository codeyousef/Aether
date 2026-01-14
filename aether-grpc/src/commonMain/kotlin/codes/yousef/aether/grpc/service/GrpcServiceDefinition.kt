package codes.yousef.aether.grpc.service

/**
 * Combines a service descriptor with its method implementations.
 * This is the runtime representation of a gRPC service.
 */
class GrpcServiceDefinition(
    /** Metadata about the service */
    val descriptor: GrpcServiceDescriptor,

    /** Map of method name to method implementation */
    val methods: Map<String, GrpcMethod<*, *>>
) {
    /**
     * Find a method by name.
     * Returns null if the method doesn't exist.
     */
    fun findMethod(name: String): GrpcMethod<*, *>? = methods[name]

    /**
     * Get the full service name.
     */
    val fullName: String get() = descriptor.fullName

    /**
     * Get the simple service name.
     */
    val name: String get() = descriptor.name
}

/**
 * Builder for creating GrpcServiceDefinition instances.
 */
class GrpcServiceBuilder(
    private val serviceName: String,
    private val packageName: String = ""
) {
    @PublishedApi
    internal val methods = mutableMapOf<String, GrpcMethod<*, *>>()

    @PublishedApi
    internal val methodDescriptors = mutableListOf<GrpcMethodDescriptor>()

    val fullName: String
        get() = if (packageName.isNotEmpty()) "$packageName.$serviceName" else serviceName

    /**
     * Add a unary method to the service.
     */
    inline fun <reified REQ, reified RESP> unary(
        name: String,
        noinline handler: suspend (REQ) -> RESP
    ) {
        val method = unaryMethod<REQ, RESP>(name, fullName, handler)
        methods[name] = method
        methodDescriptors.add(method.descriptor)
    }

    /**
     * Add a server streaming method to the service.
     */
    inline fun <reified REQ, reified RESP> serverStreaming(
        name: String,
        noinline handler: suspend (REQ) -> kotlinx.coroutines.flow.Flow<RESP>
    ) {
        val method = serverStreamingMethod<REQ, RESP>(name, fullName, handler)
        methods[name] = method
        methodDescriptors.add(method.descriptor)
    }

    /**
     * Add a client streaming method to the service.
     */
    inline fun <reified REQ, reified RESP> clientStreaming(
        name: String,
        noinline handler: suspend (kotlinx.coroutines.flow.Flow<REQ>) -> RESP
    ) {
        val method = clientStreamingMethod<REQ, RESP>(name, fullName, handler)
        methods[name] = method
        methodDescriptors.add(method.descriptor)
    }

    /**
     * Add a bidirectional streaming method to the service.
     */
    inline fun <reified REQ, reified RESP> bidiStreaming(
        name: String,
        noinline handler: suspend (kotlinx.coroutines.flow.Flow<REQ>) -> kotlinx.coroutines.flow.Flow<RESP>
    ) {
        val method = bidiStreamingMethod<REQ, RESP>(name, fullName, handler)
        methods[name] = method
        methodDescriptors.add(method.descriptor)
    }

    /**
     * Build the service definition.
     */
    fun build(): GrpcServiceDefinition {
        val descriptor = GrpcServiceDescriptor(
            name = serviceName,
            fullName = fullName,
            methods = methodDescriptors.toList()
        )
        return GrpcServiceDefinition(descriptor, methods.toMap())
    }
}

/**
 * DSL for creating a gRPC service definition.
 */
fun grpcService(
    name: String,
    packageName: String = "",
    block: GrpcServiceBuilder.() -> Unit
): GrpcServiceDefinition {
    return GrpcServiceBuilder(name, packageName).apply(block).build()
}
