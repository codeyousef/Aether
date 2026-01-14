package codes.yousef.aether.grpc.handler

import codes.yousef.aether.grpc.GrpcException
import codes.yousef.aether.grpc.GrpcMetadata
import codes.yousef.aether.grpc.GrpcStatus

/**
 * Represents a gRPC response to be sent back to the client.
 *
 * @param status The gRPC status code
 * @param body The response body (serialized message or error details)
 * @param contentType The content type for the response
 * @param metadata Response metadata (trailers)
 */
data class GrpcResponse(
    val status: GrpcStatus,
    val body: String,
    val contentType: String,
    val metadata: GrpcMetadata = GrpcMetadata()
) {
    /**
     * Whether this response indicates success.
     */
    val isSuccess: Boolean get() = status == GrpcStatus.OK

    /**
     * Whether this response indicates an error.
     */
    val isError: Boolean get() = status != GrpcStatus.OK

    companion object {
        /**
         * Creates a successful response.
         *
         * @param body The serialized response message
         * @param contentType The content type
         * @param metadata Optional response metadata
         */
        fun success(
            body: String,
            contentType: String,
            metadata: GrpcMetadata = GrpcMetadata()
        ): GrpcResponse {
            return GrpcResponse(
                status = GrpcStatus.OK,
                body = body,
                contentType = contentType,
                metadata = metadata
            )
        }

        /**
         * Creates an error response.
         *
         * @param status The error status
         * @param message The error message
         * @param contentType The content type (defaults to JSON)
         */
        fun error(
            status: GrpcStatus,
            message: String,
            contentType: String = "application/json"
        ): GrpcResponse {
            val errorBody = """{"code":${status.code},"message":"$message"}"""
            return GrpcResponse(
                status = status,
                body = errorBody,
                contentType = contentType
            )
        }

        /**
         * Creates a response from a GrpcException.
         *
         * @param exception The exception
         * @param contentType The content type (defaults to JSON)
         */
        fun fromException(
            exception: GrpcException,
            contentType: String = "application/json"
        ): GrpcResponse {
            return error(exception.status, exception.message ?: exception.status.name, contentType)
        }

        /**
         * Creates an INTERNAL error response from any exception.
         *
         * @param exception The exception
         * @param contentType The content type
         */
        fun fromThrowable(
            exception: Throwable,
            contentType: String = "application/json"
        ): GrpcResponse {
            return when (exception) {
                is GrpcException -> fromException(exception, contentType)
                else -> error(GrpcStatus.INTERNAL, exception.message ?: "Internal error", contentType)
            }
        }
    }
}
