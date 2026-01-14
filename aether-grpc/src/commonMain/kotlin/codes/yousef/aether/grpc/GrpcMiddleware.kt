package codes.yousef.aether.grpc

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.grpc.adapter.GrpcAdapter
import codes.yousef.aether.grpc.dsl.GrpcConfig
import codes.yousef.aether.grpc.dsl.GrpcConfigBuilder
import codes.yousef.aether.grpc.dsl.grpc
import codes.yousef.aether.grpc.handler.GrpcHttpHandler
import codes.yousef.aether.grpc.service.GrpcServiceDefinition

/**
 * Middleware that handles gRPC-Web and Connect protocol requests.
 *
 * This middleware intercepts requests with gRPC-compatible content types
 * and routes them to the appropriate gRPC service handlers.
 *
 * @param config The gRPC configuration
 */
class GrpcMiddleware(
    private val config: GrpcConfig
) : Middleware {
    private val adapter = GrpcAdapter(config.services)
    private val handler = GrpcHttpHandler(adapter)

    override suspend fun invoke(exchange: Exchange, next: suspend () -> Unit) {
        val contentType = exchange.request.headers["Content-Type"]
        val path = exchange.request.path

        // Check if this is a gRPC request
        if (!isGrpcRequest(contentType, path)) {
            next()
            return
        }

        // Handle the gRPC request
        val requestBody = exchange.request.bodyText()
        val grpcResponse = handler.handle(
            path = path,
            body = requestBody,
            contentType = contentType ?: "application/json",
            metadata = extractMetadata(exchange)
        )

        // Set response headers
        exchange.response.setHeader("Content-Type", grpcResponse.contentType)
        exchange.response.setHeader("grpc-status", grpcResponse.status.code.toString())

        if (grpcResponse.isError) {
            exchange.response.setHeader("grpc-message", grpcResponse.status.name)
        }

        // Send response
        val httpStatus = grpcStatusToHttp(grpcResponse.status)
        exchange.response.statusCode = httpStatus
        exchange.response.write(grpcResponse.body)
        exchange.response.end()
    }

    private fun isGrpcRequest(contentType: String?, path: String): Boolean {
        // Check content type
        if (contentType != null && GrpcAdapter.isGrpcCompatible(contentType)) {
            return true
        }

        // Check path format (looks like gRPC path: /package.Service/Method)
        val pathParts = path.trimStart('/').split('/')
        if (pathParts.size == 2 && pathParts[0].contains('.')) {
            return true
        }

        return false
    }

    private fun extractMetadata(exchange: Exchange): GrpcMetadata {
        val metadata = GrpcMetadata()
        for ((name, values) in exchange.request.headers.entries()) {
            for (value in values) {
                metadata.add(name, value)
            }
        }
        return metadata
    }

    private fun grpcStatusToHttp(status: GrpcStatus): Int {
        return when (status) {
            GrpcStatus.OK -> 200
            GrpcStatus.CANCELLED -> 499
            GrpcStatus.UNKNOWN -> 500
            GrpcStatus.INVALID_ARGUMENT -> 400
            GrpcStatus.DEADLINE_EXCEEDED -> 504
            GrpcStatus.NOT_FOUND -> 404
            GrpcStatus.ALREADY_EXISTS -> 409
            GrpcStatus.PERMISSION_DENIED -> 403
            GrpcStatus.RESOURCE_EXHAUSTED -> 429
            GrpcStatus.FAILED_PRECONDITION -> 400
            GrpcStatus.ABORTED -> 409
            GrpcStatus.OUT_OF_RANGE -> 400
            GrpcStatus.UNIMPLEMENTED -> 501
            GrpcStatus.INTERNAL -> 500
            GrpcStatus.UNAVAILABLE -> 503
            GrpcStatus.DATA_LOSS -> 500
            GrpcStatus.UNAUTHENTICATED -> 401
        }
    }
}

/**
 * Install gRPC middleware into the pipeline.
 *
 * Example:
 * ```kotlin
 * val pipeline = pipeline {
 *     installGrpc {
 *         service(userService)
 *         service(orderService)
 *         reflection = true
 *     }
 * }
 * ```
 *
 * @param configure Configuration block for gRPC settings
 */
fun Pipeline.installGrpc(configure: GrpcConfigBuilder.() -> Unit) {
    val config = grpc(configure)
    use(GrpcMiddleware(config))
}

/**
 * Install gRPC middleware with a pre-built config.
 *
 * @param config The gRPC configuration
 */
fun Pipeline.installGrpc(config: GrpcConfig) {
    use(GrpcMiddleware(config))
}

/**
 * Install gRPC middleware with a list of services.
 *
 * @param services The gRPC services to register
 */
fun Pipeline.installGrpc(vararg services: GrpcServiceDefinition) {
    val config = GrpcConfig(services = services.toList())
    use(GrpcMiddleware(config))
}

/**
 * Create a gRPC middleware function.
 *
 * Example:
 * ```kotlin
 * pipeline.use(grpcMiddleware {
 *     service(userService)
 * })
 * ```
 *
 * @param configure Configuration block
 * @return The middleware function
 */
fun grpcMiddleware(configure: GrpcConfigBuilder.() -> Unit): Middleware {
    val config = grpc(configure)
    return GrpcMiddleware(config)
}
