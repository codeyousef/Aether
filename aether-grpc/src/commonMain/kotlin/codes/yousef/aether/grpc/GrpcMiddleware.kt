package codes.yousef.aether.grpc

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.grpc.adapter.GrpcAdapter
import codes.yousef.aether.grpc.dsl.GrpcConfig
import codes.yousef.aether.grpc.dsl.GrpcConfigBuilder
import codes.yousef.aether.grpc.dsl.grpc
import codes.yousef.aether.grpc.handler.GrpcHttpHandler
import codes.yousef.aether.grpc.service.GrpcMethodType
import codes.yousef.aether.grpc.service.GrpcServiceDefinition
import codes.yousef.aether.grpc.streaming.SseCodec

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
    private val sseCodec = SseCodec()

    override suspend fun invoke(exchange: Exchange, next: suspend () -> Unit) {
        val contentType = exchange.request.headers["Content-Type"]
        val path = exchange.request.path

        // Check if this is a gRPC request
        if (!isGrpcRequest(contentType, path)) {
            next()
            return
        }

        // Parse path to get service and method names
        val pathParts = path.trimStart('/').split('/')
        if (pathParts.size != 2) {
            next()
            return
        }
        val serviceName = pathParts[0]
        val methodName = pathParts[1]

        // Check if this is a server streaming method with SSE transport
        try {
            val (_, method) = handler.routeMethod(serviceName, methodName)

            if (method.descriptor.type == GrpcMethodType.SERVER_STREAMING) {
                if (!shouldUseSSE(exchange)) {
                    // Return error: SSE transport required for streaming
                    exchange.response.statusCode = 501
                    exchange.response.setHeader("Content-Type", "application/json")
                    exchange.response.setHeader("grpc-status", GrpcStatus.UNIMPLEMENTED.code.toString())
                    exchange.response.write("""{"error":"Server streaming requires SSE transport (Accept: text/event-stream)"}""")
                    exchange.response.end()
                    return
                }

                // Handle SSE streaming
                val requestBody = exchange.request.bodyText()
                handleSSEStreaming(exchange, serviceName, methodName, requestBody, contentType ?: "application/json")
                return
            }
        } catch (_: GrpcException) {
            // Method not found, fall through to normal handling which will return proper error
        }

        // Handle the gRPC request (unary or non-SSE)
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

    /**
     * Check if the client wants SSE transport.
     */
    private fun shouldUseSSE(exchange: Exchange): Boolean {
        // Check Accept header for SSE
        val accept = exchange.request.headers["Accept"]
        if (accept?.contains("text/event-stream") == true) return true

        // Check query parameter for SSE transport
        val transport = exchange.request.queryParameter("transport")
        if (transport == "sse") return true

        return false
    }

    /**
     * Handle server streaming with SSE transport.
     */
    private suspend fun handleSSEStreaming(
        exchange: Exchange,
        serviceName: String,
        methodName: String,
        body: String,
        contentType: String
    ) {
        // Set SSE response headers
        exchange.response.statusCode = 200
        exchange.response.setHeader("Content-Type", SseCodec.CONTENT_TYPE)
        exchange.response.setHeader("Cache-Control", "no-cache")
        exchange.response.setHeader("Connection", "keep-alive")
        exchange.response.setHeader("grpc-status", GrpcStatus.OK.code.toString())

        try {
            handler.handleServerStreamingSSE(serviceName, methodName, body, contentType)
                .collect { event ->
                    exchange.response.write(event)
                }
            exchange.response.end()
        } catch (e: GrpcException) {
            // Send error as final SSE event
            val errorJson = """{"error":"${e.message?.replace("\"", "\\\"")}","code":"${e.status.name}"}"""
            exchange.response.write(sseCodec.formatEvent(errorJson, "error"))
            exchange.response.end()
        } catch (e: Exception) {
            // Send generic error as final SSE event
            val errorJson = """{"error":"${e.message?.replace("\"", "\\\"") ?: "Internal error"}","code":"INTERNAL"}"""
            exchange.response.write(sseCodec.formatEvent(errorJson, "error"))
            exchange.response.end()
        }
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
