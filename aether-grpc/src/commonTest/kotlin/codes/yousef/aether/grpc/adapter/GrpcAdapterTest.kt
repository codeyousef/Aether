package codes.yousef.aether.grpc.adapter

import codes.yousef.aether.grpc.GrpcStatus
import codes.yousef.aether.grpc.service.grpcService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD Tests for GrpcAdapter - handles gRPC-Web/Connect over HTTP.
 */
class GrpcAdapterTest {

    // Test messages
    @Serializable
    data class GetUserRequest(val id: String)

    @Serializable
    data class User(val id: String, val name: String)

    @Serializable
    data class Empty(val dummy: String = "")

    @Test
    fun `adapter routes request to correct service and method`() = runTest {
        val service = grpcService("UserService", "test") {
            unary<GetUserRequest, User>("GetUser") { request ->
                User(id = request.id, name = "User ${request.id}")
            }
        }

        val adapter = GrpcAdapter(listOf(service))

        // Path format: /package.ServiceName/MethodName
        val result = adapter.route("test.UserService", "GetUser")

        assertNotNull(result)
        assertEquals("UserService", result.first.name)
        assertEquals("GetUser", result.second.descriptor.name)
    }

    @Test
    fun `adapter returns null for unknown service`() = runTest {
        val adapter = GrpcAdapter(emptyList())

        val result = adapter.route("unknown.Service", "Method")
        assertEquals(null, result)
    }

    @Test
    fun `adapter returns null for unknown method`() = runTest {
        val service = grpcService("UserService", "test") {
            unary<GetUserRequest, User>("GetUser") { User("1", "User") }
        }

        val adapter = GrpcAdapter(listOf(service))

        val result = adapter.route("test.UserService", "UnknownMethod")
        assertEquals(null, result)
    }

    @Test
    fun `adapter parses path to extract service and method`() {
        val path = "/test.UserService/GetUser"
        val parsed = GrpcAdapter.parsePath(path)

        assertNotNull(parsed)
        assertEquals("test.UserService", parsed.first)
        assertEquals("GetUser", parsed.second)
    }

    @Test
    fun `adapter parses path without leading slash`() {
        val path = "test.UserService/GetUser"
        val parsed = GrpcAdapter.parsePath(path)

        assertNotNull(parsed)
        assertEquals("test.UserService", parsed.first)
        assertEquals("GetUser", parsed.second)
    }

    @Test
    fun `adapter returns null for invalid path format`() {
        assertEquals(null, GrpcAdapter.parsePath("/invalid"))
        assertEquals(null, GrpcAdapter.parsePath(""))
        assertEquals(null, GrpcAdapter.parsePath("/"))
    }

    @Test
    fun `adapter detects Connect JSON content type`() {
        assertTrue(GrpcAdapter.isConnectJson("application/json"))
        assertTrue(GrpcAdapter.isConnectJson("application/json; charset=utf-8"))
        assertTrue(GrpcAdapter.isConnectJson("application/connect+json"))
    }

    @Test
    fun `adapter detects gRPC-Web content type`() {
        assertTrue(GrpcAdapter.isGrpcWeb("application/grpc-web"))
        assertTrue(GrpcAdapter.isGrpcWeb("application/grpc-web+proto"))
        assertTrue(GrpcAdapter.isGrpcWeb("application/grpc-web-text"))
        assertTrue(GrpcAdapter.isGrpcWeb("application/grpc-web-text+proto"))
    }

    @Test
    fun `adapter does not match non-gRPC content types`() {
        assertEquals(false, GrpcAdapter.isGrpcWeb("text/html"))
        assertEquals(false, GrpcAdapter.isGrpcWeb("application/xml"))
        assertEquals(false, GrpcAdapter.isConnectJson("text/plain"))
    }

    @Test
    fun `GrpcResponse holds status and data`() {
        val response = GrpcResponse(
            status = GrpcStatus.OK,
            message = null,
            data = """{"id":"1","name":"Test"}"""
        )

        assertEquals(GrpcStatus.OK, response.status)
        assertEquals("""{"id":"1","name":"Test"}""", response.data)
        assertTrue(response.isOk)
    }

    @Test
    fun `GrpcResponse with error status`() {
        val response = GrpcResponse(
            status = GrpcStatus.NOT_FOUND,
            message = "User not found",
            data = null
        )

        assertEquals(GrpcStatus.NOT_FOUND, response.status)
        assertEquals("User not found", response.message)
        assertEquals(false, response.isOk)
    }

    @Test
    fun `adapter creates UNIMPLEMENTED response for unknown method`() {
        val response = GrpcResponse.unimplemented("Method not found")
        assertEquals(GrpcStatus.UNIMPLEMENTED, response.status)
        assertEquals("Method not found", response.message)
    }

    @Test
    fun `adapter creates INTERNAL response for errors`() {
        val response = GrpcResponse.internal("Server error")
        assertEquals(GrpcStatus.INTERNAL, response.status)
        assertEquals("Server error", response.message)
    }

    @Test
    fun `adapter creates OK response with data`() {
        val response = GrpcResponse.ok("""{"result":"success"}""")
        assertEquals(GrpcStatus.OK, response.status)
        assertEquals("""{"result":"success"}""", response.data)
    }
}
