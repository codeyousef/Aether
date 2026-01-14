package codes.yousef.aether.grpc

/**
 * Exception representing a gRPC error with a status code and message.
 *
 * @param status The gRPC status code.
 * @param message The error message. Defaults to the status name if not provided.
 * @param cause The underlying cause of this exception, if any.
 */
class GrpcException(
    val status: GrpcStatus,
    override val message: String = status.name,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /** Returns the numeric status code. */
    val statusCode: Int get() = status.code

    companion object {
        /** Creates a NOT_FOUND exception with the given message. */
        fun notFound(message: String): GrpcException =
            GrpcException(GrpcStatus.NOT_FOUND, message)

        /** Creates an INVALID_ARGUMENT exception with the given message. */
        fun invalidArgument(message: String): GrpcException =
            GrpcException(GrpcStatus.INVALID_ARGUMENT, message)

        /** Creates an INTERNAL exception with the given message. */
        fun internal(message: String): GrpcException =
            GrpcException(GrpcStatus.INTERNAL, message)

        /** Creates an UNAUTHENTICATED exception with the given message. */
        fun unauthenticated(message: String): GrpcException =
            GrpcException(GrpcStatus.UNAUTHENTICATED, message)

        /** Creates a PERMISSION_DENIED exception with the given message. */
        fun permissionDenied(message: String): GrpcException =
            GrpcException(GrpcStatus.PERMISSION_DENIED, message)

        /** Creates an UNIMPLEMENTED exception with the given message. */
        fun unimplemented(message: String): GrpcException =
            GrpcException(GrpcStatus.UNIMPLEMENTED, message)

        /** Creates a CANCELLED exception with the given message. */
        fun cancelled(message: String): GrpcException =
            GrpcException(GrpcStatus.CANCELLED, message)

        /** Creates a DEADLINE_EXCEEDED exception with the given message. */
        fun deadlineExceeded(message: String): GrpcException =
            GrpcException(GrpcStatus.DEADLINE_EXCEEDED, message)

        /** Creates an ALREADY_EXISTS exception with the given message. */
        fun alreadyExists(message: String): GrpcException =
            GrpcException(GrpcStatus.ALREADY_EXISTS, message)

        /** Creates a RESOURCE_EXHAUSTED exception with the given message. */
        fun resourceExhausted(message: String): GrpcException =
            GrpcException(GrpcStatus.RESOURCE_EXHAUSTED, message)

        /** Creates an UNAVAILABLE exception with the given message. */
        fun unavailable(message: String): GrpcException =
            GrpcException(GrpcStatus.UNAVAILABLE, message)
    }
}
