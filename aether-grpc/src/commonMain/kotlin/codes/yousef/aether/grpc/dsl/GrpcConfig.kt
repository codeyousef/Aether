package codes.yousef.aether.grpc.dsl

import codes.yousef.aether.grpc.GrpcMetadata
import codes.yousef.aether.grpc.GrpcMode
import codes.yousef.aether.grpc.service.GrpcServiceDefinition

/**
 * Represents a gRPC call context for interceptors.
 */
data class GrpcCall(
    val serviceName: String,
    val methodName: String,
    val metadata: GrpcMetadata,
    val attributes: MutableMap<String, Any> = mutableMapOf()
)

/**
 * Interceptor for gRPC calls.
 * Can modify the call, add metadata, or short-circuit the call chain.
 */
typealias GrpcInterceptor = suspend (call: GrpcCall, next: suspend (GrpcCall) -> Any?) -> Any?

/**
 * Configuration for the gRPC server.
 *
 * @param port The port to listen on for gRPC requests (default: 50051)
 * @param mode The gRPC mode to use (default: BEST_AVAILABLE)
 * @param reflection Whether to enable gRPC server reflection (default: false)
 * @param services The list of registered gRPC services
 * @param interceptors The list of interceptors to apply to all calls
 * @param maxMessageSize Maximum message size in bytes (default: 4MB)
 * @param keepAliveTime Time in milliseconds between keepalive pings (default: 2 hours)
 * @param keepAliveTimeout Timeout in milliseconds for keepalive ping response (default: 20 seconds)
 */
data class GrpcConfig(
    val port: Int = 50051,
    val mode: GrpcMode = GrpcMode.BEST_AVAILABLE,
    val reflection: Boolean = false,
    val services: List<GrpcServiceDefinition> = emptyList(),
    val interceptors: List<GrpcInterceptor> = emptyList(),
    val maxMessageSize: Int = 4 * 1024 * 1024, // 4MB
    val keepAliveTime: Long = 2 * 60 * 60 * 1000L, // 2 hours
    val keepAliveTimeout: Long = 20_000L // 20 seconds
)
