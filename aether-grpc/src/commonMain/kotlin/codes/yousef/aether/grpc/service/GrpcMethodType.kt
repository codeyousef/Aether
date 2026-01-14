package codes.yousef.aether.grpc.service

/**
 * Types of gRPC methods based on streaming behavior.
 */
enum class GrpcMethodType {
    /** Single request, single response */
    UNARY,

    /** Single request, streaming response */
    SERVER_STREAMING,

    /** Streaming request, single response */
    CLIENT_STREAMING,

    /** Streaming request, streaming response */
    BIDI_STREAMING
}
