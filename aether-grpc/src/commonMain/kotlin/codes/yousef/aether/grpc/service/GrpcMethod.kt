package codes.yousef.aether.grpc.service

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Base interface for gRPC method handlers.
 */
sealed interface GrpcMethod<REQ, RESP> {
    val descriptor: GrpcMethodDescriptor
    val requestSerializer: KSerializer<REQ>
    val responseSerializer: KSerializer<RESP>
}

/**
 * Handler for unary (single request, single response) gRPC methods.
 */
class UnaryMethod<REQ, RESP>(
    override val descriptor: GrpcMethodDescriptor,
    override val requestSerializer: KSerializer<REQ>,
    override val responseSerializer: KSerializer<RESP>,
    private val handler: suspend (REQ) -> RESP
) : GrpcMethod<REQ, RESP> {

    /**
     * Invoke the handler with the request.
     */
    suspend fun invoke(request: REQ): RESP = handler(request)
}

/**
 * Handler for server streaming (single request, streaming response) gRPC methods.
 */
class ServerStreamingMethod<REQ, RESP>(
    override val descriptor: GrpcMethodDescriptor,
    override val requestSerializer: KSerializer<REQ>,
    override val responseSerializer: KSerializer<Flow<RESP>>,
    private val elementSerializer: KSerializer<RESP>,
    private val handler: suspend (REQ) -> Flow<RESP>
) : GrpcMethod<REQ, Flow<RESP>> {

    /** Serializer for individual response elements in the stream */
    val elementResponseSerializer: KSerializer<RESP> get() = elementSerializer

    /**
     * Invoke the handler with the request, returning a Flow of responses.
     */
    suspend fun invoke(request: REQ): Flow<RESP> = handler(request)
}

/**
 * Handler for client streaming (streaming request, single response) gRPC methods.
 */
class ClientStreamingMethod<REQ, RESP>(
    override val descriptor: GrpcMethodDescriptor,
    override val requestSerializer: KSerializer<Flow<REQ>>,
    override val responseSerializer: KSerializer<RESP>,
    private val elementSerializer: KSerializer<REQ>,
    private val handler: suspend (Flow<REQ>) -> RESP
) : GrpcMethod<Flow<REQ>, RESP> {

    /** Serializer for individual request elements in the stream */
    val elementRequestSerializer: KSerializer<REQ> get() = elementSerializer

    /**
     * Invoke the handler with a Flow of requests.
     */
    suspend fun invoke(requests: Flow<REQ>): RESP = handler(requests)
}

/**
 * Handler for bidirectional streaming gRPC methods.
 */
class BidiStreamingMethod<REQ, RESP>(
    override val descriptor: GrpcMethodDescriptor,
    override val requestSerializer: KSerializer<Flow<REQ>>,
    override val responseSerializer: KSerializer<Flow<RESP>>,
    private val elementReqSerializer: KSerializer<REQ>,
    private val elementRespSerializer: KSerializer<RESP>,
    private val handler: suspend (Flow<REQ>) -> Flow<RESP>
) : GrpcMethod<Flow<REQ>, Flow<RESP>> {

    /** Serializer for individual request elements in the stream */
    val elementRequestSerializer: KSerializer<REQ> get() = elementReqSerializer

    /** Serializer for individual response elements in the stream */
    val elementResponseSerializer: KSerializer<RESP> get() = elementRespSerializer

    /**
     * Invoke the handler with a Flow of requests, returning a Flow of responses.
     */
    suspend fun invoke(requests: Flow<REQ>): Flow<RESP> = handler(requests)
}

/**
 * Create a unary method.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified REQ, reified RESP> unaryMethod(
    name: String,
    serviceName: String,
    noinline handler: suspend (REQ) -> RESP
): UnaryMethod<REQ, RESP> {
    val descriptor = GrpcMethodDescriptor(
        name = name,
        fullName = "$serviceName/$name",
        type = GrpcMethodType.UNARY,
        inputType = REQ::class.simpleName ?: "Unknown",
        outputType = RESP::class.simpleName ?: "Unknown"
    )
    return UnaryMethod(
        descriptor = descriptor,
        requestSerializer = serializer<REQ>(),
        responseSerializer = serializer<RESP>(),
        handler = handler
    )
}

/**
 * Create a server streaming method.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified REQ, reified RESP> serverStreamingMethod(
    name: String,
    serviceName: String,
    noinline handler: suspend (REQ) -> Flow<RESP>
): ServerStreamingMethod<REQ, RESP> {
    val descriptor = GrpcMethodDescriptor(
        name = name,
        fullName = "$serviceName/$name",
        type = GrpcMethodType.SERVER_STREAMING,
        inputType = REQ::class.simpleName ?: "Unknown",
        outputType = RESP::class.simpleName ?: "Unknown"
    )
    val reqSerializer = serializer<REQ>()
    val respSerializer = serializer<RESP>()
    // Flow cannot be serialized directly, so we use the element serializer cast
    return ServerStreamingMethod(
        descriptor = descriptor,
        requestSerializer = reqSerializer,
        responseSerializer = respSerializer as KSerializer<Flow<RESP>>,
        elementSerializer = respSerializer,
        handler = handler
    )
}

/**
 * Create a client streaming method.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified REQ, reified RESP> clientStreamingMethod(
    name: String,
    serviceName: String,
    noinline handler: suspend (Flow<REQ>) -> RESP
): ClientStreamingMethod<REQ, RESP> {
    val descriptor = GrpcMethodDescriptor(
        name = name,
        fullName = "$serviceName/$name",
        type = GrpcMethodType.CLIENT_STREAMING,
        inputType = REQ::class.simpleName ?: "Unknown",
        outputType = RESP::class.simpleName ?: "Unknown"
    )
    val reqSerializer = serializer<REQ>()
    val respSerializer = serializer<RESP>()
    // Flow cannot be serialized directly, so we use the element serializer cast
    return ClientStreamingMethod(
        descriptor = descriptor,
        requestSerializer = reqSerializer as KSerializer<Flow<REQ>>,
        responseSerializer = respSerializer,
        elementSerializer = reqSerializer,
        handler = handler
    )
}

/**
 * Create a bidirectional streaming method.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified REQ, reified RESP> bidiStreamingMethod(
    name: String,
    serviceName: String,
    noinline handler: suspend (Flow<REQ>) -> Flow<RESP>
): BidiStreamingMethod<REQ, RESP> {
    val descriptor = GrpcMethodDescriptor(
        name = name,
        fullName = "$serviceName/$name",
        type = GrpcMethodType.BIDI_STREAMING,
        inputType = REQ::class.simpleName ?: "Unknown",
        outputType = RESP::class.simpleName ?: "Unknown"
    )
    val reqSerializer = serializer<REQ>()
    val respSerializer = serializer<RESP>()
    // Flow cannot be serialized directly, so we use the element serializer cast
    return BidiStreamingMethod(
        descriptor = descriptor,
        requestSerializer = reqSerializer as KSerializer<Flow<REQ>>,
        responseSerializer = respSerializer as KSerializer<Flow<RESP>>,
        elementReqSerializer = reqSerializer,
        elementRespSerializer = respSerializer,
        handler = handler
    )
}
