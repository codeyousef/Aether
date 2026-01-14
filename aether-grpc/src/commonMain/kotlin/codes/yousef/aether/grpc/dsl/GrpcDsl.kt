package codes.yousef.aether.grpc.dsl

import codes.yousef.aether.grpc.GrpcMode
import codes.yousef.aether.grpc.service.GrpcServiceBuilder
import codes.yousef.aether.grpc.service.GrpcServiceDefinition
import codes.yousef.aether.grpc.service.grpcService

/**
 * DSL marker for gRPC configuration.
 */
@DslMarker
annotation class GrpcDslMarker

/**
 * Builder for gRPC configuration using DSL syntax.
 *
 * Example:
 * ```kotlin
 * val config = grpc {
 *     port = 50051
 *     mode = GrpcMode.BEST_AVAILABLE
 *     reflection = true
 *
 *     service(myUserService)
 *     service("EchoService", "echo.v1") {
 *         unary<String, String>("Echo") { request -> request }
 *     }
 *
 *     intercept { call, next ->
 *         // Log all calls
 *         println("Calling ${call.serviceName}/${call.methodName}")
 *         next(call)
 *     }
 * }
 * ```
 */
@GrpcDslMarker
class GrpcConfigBuilder {
    /**
     * The port to listen on for gRPC requests.
     * Default: 50051
     */
    var port: Int = 50051

    /**
     * The gRPC mode to use.
     * Default: BEST_AVAILABLE
     */
    var mode: GrpcMode = GrpcMode.BEST_AVAILABLE

    /**
     * Whether to enable gRPC server reflection.
     * Default: false
     */
    var reflection: Boolean = false

    /**
     * Maximum message size in bytes.
     * Default: 4MB
     */
    var maxMessageSize: Int = 4 * 1024 * 1024

    /**
     * Time in milliseconds between keepalive pings.
     * Default: 2 hours
     */
    var keepAliveTime: Long = 2 * 60 * 60 * 1000L

    /**
     * Timeout in milliseconds for keepalive ping response.
     * Default: 20 seconds
     */
    var keepAliveTimeout: Long = 20_000L

    private val _services = mutableListOf<GrpcServiceDefinition>()
    private val _interceptors = mutableListOf<GrpcInterceptor>()

    /**
     * The list of registered services.
     */
    val services: List<GrpcServiceDefinition> get() = _services

    /**
     * The list of registered interceptors.
     */
    val interceptors: List<GrpcInterceptor> get() = _interceptors

    /**
     * Register a pre-built gRPC service.
     *
     * @param service The service definition to register
     */
    fun service(service: GrpcServiceDefinition) {
        _services.add(service)
    }

    /**
     * Register a gRPC service using inline DSL.
     *
     * @param name The service name
     * @param packageName The proto package name (optional)
     * @param block The service builder DSL
     */
    fun service(
        name: String,
        packageName: String = "",
        block: GrpcServiceBuilder.() -> Unit
    ) {
        _services.add(grpcService(name, packageName, block))
    }

    /**
     * Add an interceptor to the gRPC call chain.
     * Interceptors are executed in the order they are added.
     *
     * @param interceptor The interceptor function
     */
    fun intercept(interceptor: GrpcInterceptor) {
        _interceptors.add(interceptor)
    }

    /**
     * Build the final GrpcConfig.
     */
    fun build(): GrpcConfig = GrpcConfig(
        port = port,
        mode = mode,
        reflection = reflection,
        services = _services.toList(),
        interceptors = _interceptors.toList(),
        maxMessageSize = maxMessageSize,
        keepAliveTime = keepAliveTime,
        keepAliveTimeout = keepAliveTimeout
    )
}

/**
 * Creates a gRPC configuration using DSL syntax.
 *
 * Example:
 * ```kotlin
 * val config = grpc {
 *     port = 50051
 *     service(myService)
 *     reflection = true
 * }
 * ```
 *
 * @param block The configuration block
 * @return The built GrpcConfig
 */
fun grpc(block: GrpcConfigBuilder.() -> Unit): GrpcConfig {
    return GrpcConfigBuilder().apply(block).build()
}
