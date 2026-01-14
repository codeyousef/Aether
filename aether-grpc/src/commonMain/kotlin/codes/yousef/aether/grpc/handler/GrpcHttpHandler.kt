package codes.yousef.aether.grpc.handler

import codes.yousef.aether.grpc.GrpcException
import codes.yousef.aether.grpc.GrpcMetadata
import codes.yousef.aether.grpc.GrpcStatus
import codes.yousef.aether.grpc.adapter.GrpcAdapter
import codes.yousef.aether.grpc.service.GrpcMethod
import codes.yousef.aether.grpc.service.GrpcMethodType
import codes.yousef.aether.grpc.service.GrpcServiceDefinition
import codes.yousef.aether.grpc.service.UnaryMethod
import codes.yousef.aether.grpc.streaming.LpmCodec
import codes.yousef.aether.grpc.streaming.SseCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * HTTP handler for gRPC requests.
 *
 * Routes gRPC-Web and Connect protocol requests through the HTTP stack,
 * handling serialization/deserialization and invoking the appropriate
 * service methods.
 *
 * Supports:
 * - gRPC-Web (application/grpc-web, application/grpc-web+proto)
 * - Connect JSON (application/connect+json, application/json)
 * - Connect Proto (application/connect+proto, application/proto)
 *
 * Example usage:
 * ```kotlin
 * val adapter = GrpcAdapter(listOf(userService, orderService))
 * val handler = GrpcHttpHandler(adapter)
 *
 * // In your HTTP handler
 * val response = handler.handle(
 *     path = "/users.v1.UserService/GetUser",
 *     body = requestBody,
 *     contentType = "application/json",
 *     metadata = GrpcMetadata()
 * )
 * ```
 */
class GrpcHttpHandler(
    private val adapter: GrpcAdapter,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val lpmCodec = LpmCodec()
    private val sseCodec = SseCodec()

    /**
     * Handle a gRPC request.
     *
     * @param path The request path (e.g., "/package.Service/Method")
     * @param body The request body
     * @param contentType The content type header
     * @param metadata Request metadata (headers)
     * @return The gRPC response
     */
    suspend fun handle(
        path: String,
        body: String,
        contentType: String,
        metadata: GrpcMetadata = GrpcMetadata()
    ): GrpcResponse {
        return try {
            // Parse path to get service and method names
            val (serviceName, methodName) = parsePath(path)
                ?: throw GrpcException.invalidArgument("Invalid gRPC path: $path")

            // Route to the correct service/method
            val (service, method) = routeMethod(serviceName, methodName)

            // Handle based on method type
            when (method.descriptor.type) {
                GrpcMethodType.UNARY -> handleUnary(method, body, contentType)
                GrpcMethodType.SERVER_STREAMING -> handleServerStreaming(method, body, contentType)
                GrpcMethodType.CLIENT_STREAMING -> throw GrpcException.unimplemented(
                    "Client streaming not supported over HTTP/1.1"
                )

                GrpcMethodType.BIDI_STREAMING -> throw GrpcException.unimplemented(
                    "Bidirectional streaming not supported over HTTP/1.1"
                )
            }
        } catch (e: GrpcException) {
            GrpcResponse.fromException(e, normalizeContentType(contentType))
        } catch (e: Exception) {
            GrpcResponse.error(
                GrpcStatus.INTERNAL,
                e.message ?: "Internal error",
                normalizeContentType(contentType)
            )
        }
    }

    /**
     * Handle a gRPC request with binary body (for gRPC-Web).
     *
     * @param path The request path
     * @param body The request body as bytes
     * @param contentType The content type header
     * @param metadata Request metadata
     * @return The gRPC response
     */
    suspend fun handleBinary(
        path: String,
        body: ByteArray,
        contentType: String,
        metadata: GrpcMetadata = GrpcMetadata()
    ): GrpcResponse {
        return try {
            if (isGrpcWeb(contentType)) {
                // Unframe the LPM message
                val message = if (body.size >= 5) {
                    lpmCodec.unframe(body)
                } else {
                    body
                }
                // For now, convert to string and handle as JSON
                // In a full implementation, this would use protobuf serialization
                handle(path, message.decodeToString(), contentType, metadata)
            } else {
                handle(path, body.decodeToString(), contentType, metadata)
            }
        } catch (e: Exception) {
            GrpcResponse.fromThrowable(e, normalizeContentType(contentType))
        }
    }

    /**
     * Route to the correct service and method.
     *
     * @param serviceName The service name
     * @param methodName The method name
     * @return Pair of service definition and method
     * @throws GrpcException if service/method not found
     */
    fun routeMethod(serviceName: String, methodName: String): Pair<GrpcServiceDefinition, GrpcMethod<*, *>> {
        val result = adapter.route(serviceName, methodName)
            ?: throw GrpcException.unimplemented("Method not found: $serviceName/$methodName")
        return result
    }

    /**
     * Invoke a unary method.
     *
     * @param serviceName The service name
     * @param methodName The method name
     * @param requestBody The serialized request
     * @param contentType The content type
     * @return The response
     */
    suspend fun invokeUnary(
        serviceName: String,
        methodName: String,
        requestBody: String,
        contentType: String
    ): GrpcResponse {
        return try {
            val (_, method) = routeMethod(serviceName, methodName)
            handleUnary(method, requestBody, contentType)
        } catch (e: GrpcException) {
            GrpcResponse.fromException(e, normalizeContentType(contentType))
        } catch (e: Exception) {
            GrpcResponse.fromThrowable(e, normalizeContentType(contentType))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun handleUnary(
        method: GrpcMethod<*, *>,
        body: String,
        contentType: String
    ): GrpcResponse {
        val unaryMethod = method as? UnaryMethod<*, *>
            ?: throw GrpcException.internal("Expected unary method")

        // Deserialize request - for now using simple string handling
        // In a full implementation, this would use kotlinx.serialization with reified types
        val request = deserializeRequest(body, contentType)

        // Invoke the method
        val response = try {
            @Suppress("UNCHECKED_CAST")
            (unaryMethod as UnaryMethod<Any?, Any?>).invoke(request)
        } catch (e: GrpcException) {
            throw e
        } catch (e: Exception) {
            throw GrpcException.internal(e.message ?: "Method invocation failed")
        }

        // Serialize response
        val responseBody = serializeResponse(response, contentType)
        return GrpcResponse.success(responseBody, normalizeContentType(contentType))
    }

    private suspend fun handleServerStreaming(
        method: GrpcMethod<*, *>,
        body: String,
        contentType: String
    ): GrpcResponse {
        // For server streaming over HTTP/1.1, we'd return SSE
        // This is a simplified implementation
        throw GrpcException.unimplemented("Server streaming requires SSE transport")
    }

    /**
     * Create a Flow of SSE events for server streaming.
     *
     * @param path The request path
     * @param body The request body
     * @param contentType The content type
     * @return Flow of SSE event strings
     */
    fun serverStreamingEvents(
        path: String,
        body: String,
        contentType: String
    ): Flow<String> = flow {
        val (serviceName, methodName) = parsePath(path)
            ?: throw GrpcException.invalidArgument("Invalid gRPC path: $path")

        val (_, method) = routeMethod(serviceName, methodName)

        if (method.descriptor.type != GrpcMethodType.SERVER_STREAMING) {
            throw GrpcException.invalidArgument("Method is not server streaming")
        }

        // This would need proper implementation with reified types
        // For now, emit a placeholder
        emit(sseCodec.formatEvent("""{"error":"Streaming not fully implemented"}""", "error"))
    }

    private fun deserializeRequest(body: String, contentType: String): Any? {
        // Simple deserialization - in production would use kotlinx.serialization
        return when {
            isConnectJson(contentType) || contentType.contains("json") -> {
                // Remove quotes for simple string types
                body.trim().removeSurrounding("\"")
            }

            else -> body
        }
    }

    private fun serializeResponse(response: Any?, contentType: String): String {
        // Simple serialization - in production would use kotlinx.serialization
        return when {
            isConnectJson(contentType) || contentType.contains("json") -> {
                when (response) {
                    is String -> "\"$response\""
                    is Number -> response.toString()
                    is Boolean -> response.toString()
                    null -> "null"
                    else -> "\"$response\""
                }
            }

            else -> response?.toString() ?: ""
        }
    }

    private fun normalizeContentType(contentType: String): String {
        return when {
            isConnectJson(contentType) -> contentType
            contentType.contains("json") -> "application/json"
            isGrpcWeb(contentType) -> "application/grpc-web+proto"
            isConnectProto(contentType) -> "application/connect+proto"
            else -> "application/json"
        }
    }

    companion object {
        /**
         * Parse a gRPC path to extract service and method names.
         *
         * @param path The path (e.g., "/package.Service/Method")
         * @return Pair of (serviceName, methodName) or null if invalid
         */
        fun parsePath(path: String): Pair<String, String>? {
            if (!path.startsWith("/")) return null

            val parts = path.removePrefix("/").split("/")
            if (parts.size != 2) return null

            val serviceName = parts[0]
            val methodName = parts[1]

            if (serviceName.isBlank() || methodName.isBlank()) return null

            return serviceName to methodName
        }

        /**
         * Check if content type is gRPC-Web.
         */
        fun isGrpcWeb(contentType: String?): Boolean {
            if (contentType == null) return false
            return contentType.startsWith("application/grpc-web")
        }

        /**
         * Check if content type is Connect JSON.
         */
        fun isConnectJson(contentType: String?): Boolean {
            if (contentType == null) return false
            return contentType == "application/connect+json" ||
                    contentType == "application/json"
        }

        /**
         * Check if content type is Connect Proto.
         */
        fun isConnectProto(contentType: String?): Boolean {
            if (contentType == null) return false
            return contentType == "application/connect+proto" ||
                    contentType == "application/proto"
        }
    }
}
