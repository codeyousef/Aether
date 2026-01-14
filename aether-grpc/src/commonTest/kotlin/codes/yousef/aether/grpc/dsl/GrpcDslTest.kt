package codes.yousef.aether.grpc.dsl

import codes.yousef.aether.grpc.GrpcMode
import codes.yousef.aether.grpc.service.grpcService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD Tests for gRPC DSL configuration.
 */
class GrpcDslTest {

    @Test
    fun `grpc DSL creates config with default values`() {
        val config = grpc { }

        assertEquals(50051, config.port)
        assertEquals(GrpcMode.BEST_AVAILABLE, config.mode)
        assertFalse(config.reflection)
        assertTrue(config.services.isEmpty())
    }

    @Test
    fun `grpc DSL configures port`() {
        val config = grpc {
            port = 9090
        }

        assertEquals(9090, config.port)
    }

    @Test
    fun `grpc DSL registers services`() {
        val testService = grpcService("TestService", "test.v1") {
            unary<String, String>("Test") { request ->
                "Response: $request"
            }
        }

        val config = grpc {
            service(testService)
        }

        assertEquals(1, config.services.size)
        assertEquals("TestService", config.services.first().descriptor.name)
    }

    @Test
    fun `grpc DSL registers multiple services`() {
        val service1 = grpcService("Service1", "test.v1") {
            unary<String, String>("Method1") { it }
        }
        val service2 = grpcService("Service2", "test.v1") {
            unary<String, String>("Method2") { it }
        }

        val config = grpc {
            service(service1)
            service(service2)
        }

        assertEquals(2, config.services.size)
    }

    @Test
    fun `grpc DSL configures reflection`() {
        val config = grpc {
            reflection = true
        }

        assertTrue(config.reflection)
    }

    @Test
    fun `grpc DSL selects ADAPTER_ONLY mode`() {
        val config = grpc {
            mode = GrpcMode.ADAPTER_ONLY
        }

        assertEquals(GrpcMode.ADAPTER_ONLY, config.mode)
    }

    @Test
    fun `grpc DSL selects NATIVE_ONLY mode`() {
        val config = grpc {
            mode = GrpcMode.NATIVE_ONLY
        }

        assertEquals(GrpcMode.NATIVE_ONLY, config.mode)
    }

    @Test
    fun `grpc DSL configures interceptors`() {
        val config = grpc {
            intercept { call, next ->
                next(call)
            }
        }

        assertEquals(1, config.interceptors.size)
    }

    @Test
    fun `grpc DSL configures max message size`() {
        val config = grpc {
            maxMessageSize = 16 * 1024 * 1024 // 16MB
        }

        assertEquals(16 * 1024 * 1024, config.maxMessageSize)
    }

    @Test
    fun `grpc DSL configures keepalive settings`() {
        val config = grpc {
            keepAliveTime = 30_000L
            keepAliveTimeout = 10_000L
        }

        assertEquals(30_000L, config.keepAliveTime)
        assertEquals(10_000L, config.keepAliveTimeout)
    }

    @Test
    fun `grpc DSL can be configured with inline service definition`() {
        val config = grpc {
            port = 50052
            service("InlineService", "inline.v1") {
                unary<String, String>("Echo") { request ->
                    request
                }
            }
            reflection = true
        }

        assertEquals(50052, config.port)
        assertEquals(1, config.services.size)
        assertEquals("InlineService", config.services.first().descriptor.name)
        assertTrue(config.reflection)
    }
}

/**
 * TDD Tests for GrpcConfig data class.
 */
class GrpcConfigTest {

    @Test
    fun `GrpcConfig has sensible defaults`() {
        val config = GrpcConfig()

        assertEquals(50051, config.port)
        assertEquals(GrpcMode.BEST_AVAILABLE, config.mode)
        assertFalse(config.reflection)
        assertEquals(4 * 1024 * 1024, config.maxMessageSize) // 4MB default
    }

    @Test
    fun `GrpcConfig allows custom values`() {
        val config = GrpcConfig(
            port = 8080,
            mode = GrpcMode.ADAPTER_ONLY,
            reflection = true,
            maxMessageSize = 8 * 1024 * 1024
        )

        assertEquals(8080, config.port)
        assertEquals(GrpcMode.ADAPTER_ONLY, config.mode)
        assertTrue(config.reflection)
        assertEquals(8 * 1024 * 1024, config.maxMessageSize)
    }
}
