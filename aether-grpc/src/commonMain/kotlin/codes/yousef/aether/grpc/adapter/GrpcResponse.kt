package codes.yousef.aether.grpc.adapter

import codes.yousef.aether.grpc.GrpcStatus

/**
 * Represents a gRPC response with status, optional message, and optional data.
 */
data class GrpcResponse(
    /** gRPC status code */
    val status: GrpcStatus,

    /** Error message (for non-OK responses) */
    val message: String? = null,

    /** Response data (JSON or binary, depending on protocol) */
    val data: String? = null
) {
    /** Returns true if the status is OK */
    val isOk: Boolean get() = status == GrpcStatus.OK

    companion object {
        /** Create an OK response with data */
        fun ok(data: String): GrpcResponse =
            GrpcResponse(GrpcStatus.OK, data = data)

        /** Create a NOT_FOUND response */
        fun notFound(message: String): GrpcResponse =
            GrpcResponse(GrpcStatus.NOT_FOUND, message = message)

        /** Create an INVALID_ARGUMENT response */
        fun invalidArgument(message: String): GrpcResponse =
            GrpcResponse(GrpcStatus.INVALID_ARGUMENT, message = message)

        /** Create an UNAUTHENTICATED response */
        fun unauthenticated(message: String): GrpcResponse =
            GrpcResponse(GrpcStatus.UNAUTHENTICATED, message = message)

        /** Create a PERMISSION_DENIED response */
        fun permissionDenied(message: String): GrpcResponse =
            GrpcResponse(GrpcStatus.PERMISSION_DENIED, message = message)

        /** Create an UNIMPLEMENTED response */
        fun unimplemented(message: String): GrpcResponse =
            GrpcResponse(GrpcStatus.UNIMPLEMENTED, message = message)

        /** Create an INTERNAL error response */
        fun internal(message: String): GrpcResponse =
            GrpcResponse(GrpcStatus.INTERNAL, message = message)

        /** Create an UNAVAILABLE response */
        fun unavailable(message: String): GrpcResponse =
            GrpcResponse(GrpcStatus.UNAVAILABLE, message = message)

        /** Create a response from status and optional message */
        fun fromStatus(status: GrpcStatus, message: String? = null): GrpcResponse =
            GrpcResponse(status, message = message)
    }
}
