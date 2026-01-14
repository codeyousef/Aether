package codes.yousef.aether.grpc.adapter

import codes.yousef.aether.grpc.service.GrpcMethod
import codes.yousef.aether.grpc.service.GrpcServiceDefinition

/**
 * Adapter that handles gRPC-Web and Connect protocol requests over HTTP.
 * This enables gRPC services to be called from browsers and environments
 * that don't support native HTTP/2 gRPC.
 *
 * Supported protocols:
 * - gRPC-Web (application/grpc-web, application/grpc-web+proto)
 * - Connect (application/json, application/connect+json)
 */
class GrpcAdapter(
    private val services: List<GrpcServiceDefinition>
) {
    // Index services by full name for fast lookup
    private val servicesByName: Map<String, GrpcServiceDefinition> =
        services.associateBy { it.fullName }

    /**
     * Route a request to the appropriate service and method.
     * Returns null if the service or method is not found.
     *
     * @param serviceName Full service name (e.g., "test.UserService")
     * @param methodName Method name (e.g., "GetUser")
     * @return Pair of service definition and method, or null if not found
     */
    fun route(serviceName: String, methodName: String): Pair<GrpcServiceDefinition, GrpcMethod<*, *>>? {
        val service = servicesByName[serviceName] ?: return null
        val method = service.findMethod(methodName) ?: return null
        return service to method
    }

    /**
     * Get all registered services.
     */
    fun services(): List<GrpcServiceDefinition> = services

    /**
     * Check if a service is registered.
     */
    fun hasService(serviceName: String): Boolean = serviceName in servicesByName

    companion object {
        /**
         * Parse a gRPC path to extract service and method names.
         * Path format: /[package.]ServiceName/MethodName
         *
         * @return Pair of (serviceName, methodName) or null if invalid format
         */
        fun parsePath(path: String): Pair<String, String>? {
            val normalized = path.trimStart('/')
            if (normalized.isEmpty()) return null

            val slashIndex = normalized.lastIndexOf('/')
            if (slashIndex <= 0 || slashIndex >= normalized.length - 1) return null

            val serviceName = normalized.substring(0, slashIndex)
            val methodName = normalized.substring(slashIndex + 1)

            if (serviceName.isEmpty() || methodName.isEmpty()) return null

            return serviceName to methodName
        }

        /**
         * Check if the content type indicates a gRPC-Web request.
         */
        fun isGrpcWeb(contentType: String?): Boolean {
            if (contentType == null) return false
            return contentType.startsWith("application/grpc-web", ignoreCase = true)
        }

        /**
         * Check if the content type indicates a Connect JSON request.
         */
        fun isConnectJson(contentType: String?): Boolean {
            if (contentType == null) return false
            return contentType.startsWith("application/json", ignoreCase = true) ||
                    contentType.startsWith("application/connect+json", ignoreCase = true)
        }

        /**
         * Check if the content type indicates a Connect protocol request (JSON or proto).
         */
        fun isConnect(contentType: String?): Boolean {
            if (contentType == null) return false
            return isConnectJson(contentType) ||
                    contentType.startsWith("application/connect+proto", ignoreCase = true)
        }

        /**
         * Check if the content type indicates any gRPC-compatible request.
         */
        fun isGrpcCompatible(contentType: String?): Boolean {
            return isGrpcWeb(contentType) || isConnect(contentType)
        }

        /** Content type for gRPC-Web binary */
        const val CONTENT_TYPE_GRPC_WEB = "application/grpc-web+proto"

        /** Content type for gRPC-Web text (base64) */
        const val CONTENT_TYPE_GRPC_WEB_TEXT = "application/grpc-web-text+proto"

        /** Content type for Connect JSON */
        const val CONTENT_TYPE_CONNECT_JSON = "application/json"

        /** Content type for Connect proto */
        const val CONTENT_TYPE_CONNECT_PROTO = "application/connect+proto"
    }
}
