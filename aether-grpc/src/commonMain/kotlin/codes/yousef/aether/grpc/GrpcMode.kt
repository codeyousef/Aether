package codes.yousef.aether.grpc

/**
 * Defines how gRPC requests should be handled.
 *
 * The Aether gRPC module supports two modes of operation:
 * 1. Adapter mode: Routes gRPC-Web and Connect protocol requests through the HTTP stack
 * 2. Native mode: Uses a native HTTP/2 gRPC server (JVM only with Netty)
 */
enum class GrpcMode {
    /**
     * Automatically selects the best available mode:
     * - Uses native gRPC server on JVM with Netty if available
     * - Falls back to adapter mode otherwise
     *
     * This is the recommended mode for most applications.
     */
    BEST_AVAILABLE,

    /**
     * Uses only the HTTP adapter for gRPC-Web and Connect protocol.
     * Works on all platforms (JVM, WASM, etc.) by routing gRPC
     * requests through the existing HTTP infrastructure.
     *
     * Use this mode for:
     * - Serverless deployments
     * - WASM targets
     * - Testing without native dependencies
     */
    ADAPTER_ONLY,

    /**
     * Uses only the native gRPC server (JVM with Netty).
     * Provides full HTTP/2 gRPC support with better performance.
     *
     * Use this mode for:
     * - High-performance JVM deployments
     * - Full gRPC feature support (trailers, flow control, etc.)
     * - Interoperability with native gRPC clients
     *
     * Note: This mode is only available on JVM. Using it on other
     * platforms will throw an UnsupportedOperationException.
     */
    NATIVE_ONLY
}
