package codes.yousef.aether.grpc

/**
 * Standard gRPC status codes as defined in the gRPC specification.
 * https://grpc.github.io/grpc/core/md_doc_statuscodes.html
 */
enum class GrpcStatus(val code: Int) {
    /** The operation completed successfully. */
    OK(0),

    /** The operation was cancelled (typically by the caller). */
    CANCELLED(1),

    /** Unknown error. */
    UNKNOWN(2),

    /** Client specified an invalid argument. */
    INVALID_ARGUMENT(3),

    /** Deadline expired before operation could complete. */
    DEADLINE_EXCEEDED(4),

    /** Some requested entity was not found. */
    NOT_FOUND(5),

    /** Some entity that we attempted to create already exists. */
    ALREADY_EXISTS(6),

    /** The caller does not have permission to execute the specified operation. */
    PERMISSION_DENIED(7),

    /** Some resource has been exhausted. */
    RESOURCE_EXHAUSTED(8),

    /** Operation was rejected because the system is not in a required state. */
    FAILED_PRECONDITION(9),

    /** The operation was aborted. */
    ABORTED(10),

    /** Operation was attempted past the valid range. */
    OUT_OF_RANGE(11),

    /** Operation is not implemented or not supported. */
    UNIMPLEMENTED(12),

    /** Internal errors. */
    INTERNAL(13),

    /** The service is currently unavailable. */
    UNAVAILABLE(14),

    /** Unrecoverable data loss or corruption. */
    DATA_LOSS(15),

    /** The request does not have valid authentication credentials. */
    UNAUTHENTICATED(16);

    /** Returns true if this status represents a successful operation. */
    val isOk: Boolean get() = this == OK

    /** Returns true if this status represents an error. */
    val isError: Boolean get() = this != OK

    companion object {
        private val byCode = entries.associateBy { it.code }

        /**
         * Returns the GrpcStatus for the given numeric code.
         * Returns UNKNOWN if the code is not recognized.
         */
        fun fromCode(code: Int): GrpcStatus = byCode[code] ?: UNKNOWN
    }
}
