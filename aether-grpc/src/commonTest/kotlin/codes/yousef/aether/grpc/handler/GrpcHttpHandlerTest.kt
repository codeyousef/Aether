package codes.yousef.aether.grpc.handler

import codes.yousef.aether.grpc.GrpcException
import codes.yousef.aether.grpc.GrpcStatus
import codes.yousef.aether.grpc.adapter.GrpcAdapter
import codes.yousef.aether.grpc.service.grpcService
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TDD Tests for GrpcHttpHandler.
 */
class GrpcHttpHandlerTest {

    private val testService = grpcService("TestService", "test.v1") {
        unary<String, String>("Echo") { request ->
            "Echo: $request"
        }
        unary<String, String>("Reverse") { request ->
            request.reversed()
        }
        unary<String, Int>("Length") { request ->
            request.length
        }
    }

    private val adapter = GrpcAdapter(listOf(testService))
    private val handler = GrpcHttpHandler(adapter)

    @Test
    fun `parses standard gRPC path correctly`() {
        val (service, method) = GrpcHttpHandler.parsePath("/test.v1.TestService/Echo")!!
        assertEquals("test.v1.TestService", service)
        assertEquals("Echo", method)
    }

    @Test
    fun `parses path without package`() {
        val (service, method) = GrpcHttpHandler.parsePath("/TestService/Echo")!!
        assertEquals("TestService", service)
        assertEquals("Echo", method)
    }

    @Test
    fun `returns null for invalid path`() {
        assertEquals(null, GrpcHttpHandler.parsePath("/invalid"))
        assertEquals(null, GrpcHttpHandler.parsePath("no-slash"))
        assertEquals(null, GrpcHttpHandler.parsePath("/"))
    }

    @Test
    fun `detects gRPC-Web content type`() {
        assertTrue(GrpcHttpHandler.isGrpcWeb("application/grpc-web"))
        assertTrue(GrpcHttpHandler.isGrpcWeb("application/grpc-web+proto"))
        assertTrue(GrpcHttpHandler.isGrpcWeb("application/grpc-web-text"))
    }

    @Test
    fun `detects Connect JSON content type`() {
        assertTrue(GrpcHttpHandler.isConnectJson("application/connect+json"))
        assertTrue(GrpcHttpHandler.isConnectJson("application/json"))
    }

    @Test
    fun `detects Connect proto content type`() {
        assertTrue(GrpcHttpHandler.isConnectProto("application/connect+proto"))
        assertTrue(GrpcHttpHandler.isConnectProto("application/proto"))
    }

    @Test
    fun `routes to correct service and method`() = runTest {
        val result = handler.routeMethod("test.v1.TestService", "Echo")
        assertEquals("TestService", result.first.descriptor.name)
        assertEquals("Echo", result.second.descriptor.name)
    }

    @Test
    fun `throws UNIMPLEMENTED for unknown service`() = runTest {
        val exception = assertFailsWith<GrpcException> {
            handler.routeMethod("UnknownService", "Echo")
        }
        assertEquals(GrpcStatus.UNIMPLEMENTED, exception.status)
    }

    @Test
    fun `throws UNIMPLEMENTED for unknown method`() = runTest {
        val exception = assertFailsWith<GrpcException> {
            handler.routeMethod("test.v1.TestService", "UnknownMethod")
        }
        assertEquals(GrpcStatus.UNIMPLEMENTED, exception.status)
    }

    @Test
    fun `invokes unary method with JSON codec`() = runTest {
        val response = handler.invokeUnary(
            serviceName = "test.v1.TestService",
            methodName = "Echo",
            requestBody = """"Hello"""",
            contentType = "application/json"
        )

        assertEquals(""""Echo: Hello"""", response.body)
        assertEquals(GrpcStatus.OK, response.status)
    }

    @Test
    fun `handles method errors gracefully`() = runTest {
        val errorService = grpcService("ErrorService", "error.v1") {
            unary<String, String>("Fail") { _ ->
                throw GrpcException.invalidArgument("Bad input")
            }
        }
        val errorAdapter = GrpcAdapter(listOf(errorService))
        val errorHandler = GrpcHttpHandler(errorAdapter)

        val response = errorHandler.invokeUnary(
            serviceName = "error.v1.ErrorService",
            methodName = "Fail",
            requestBody = """"test"""",
            contentType = "application/json"
        )

        assertEquals(GrpcStatus.INVALID_ARGUMENT, response.status)
        assertTrue(response.body.contains("Bad input"))
    }

    @Test
    fun `returns correct content type for JSON requests`() = runTest {
        val response = handler.invokeUnary(
            serviceName = "test.v1.TestService",
            methodName = "Echo",
            requestBody = """"test"""",
            contentType = "application/json"
        )

        assertEquals("application/json", response.contentType)
    }

    @Test
    fun `returns correct content type for Connect JSON requests`() = runTest {
        val response = handler.invokeUnary(
            serviceName = "test.v1.TestService",
            methodName = "Echo",
            requestBody = """"test"""",
            contentType = "application/connect+json"
        )

        assertEquals("application/connect+json", response.contentType)
    }
}

/**
 * Tests for complex type serialization/deserialization in GrpcHttpHandler.
 * This verifies the fix for the bug where raw JSON strings were passed
 * to handlers instead of being deserialized to typed objects.
 */
class GrpcHttpHandlerComplexTypeTest {

    @Serializable
    data class StatusRequest(
        val serviceName: String,
        val includeDetails: Boolean = false
    )

    @Serializable
    data class StatusResponse(
        val serviceName: String,
        val status: String,
        val healthy: Boolean
    )

    @Serializable
    data class UserRequest(val id: Int, val name: String)

    @Serializable
    data class UserResponse(val id: Int, val name: String, val greeting: String)

    private val complexService = grpcService("StatusService", "health.v1") {
        unary<StatusRequest, StatusResponse>("GetStatus") { request ->
            // This handler expects a properly deserialized StatusRequest object
            StatusResponse(
                serviceName = request.serviceName,
                status = if (request.includeDetails) "OK with details" else "OK",
                healthy = true
            )
        }
        unary<UserRequest, UserResponse>("GreetUser") { request ->
            // This handler expects a properly deserialized UserRequest object
            UserResponse(
                id = request.id,
                name = request.name,
                greeting = "Hello, ${request.name}!"
            )
        }
    }

    private val adapter = GrpcAdapter(listOf(complexService))
    private val handler = GrpcHttpHandler(adapter)

    @Test
    fun `deserializes complex request type from JSON`() = runTest {
        val response = handler.invokeUnary(
            serviceName = "health.v1.StatusService",
            methodName = "GetStatus",
            requestBody = """{"serviceName":"my-service","includeDetails":true}""",
            contentType = "application/json"
        )

        assertEquals(GrpcStatus.OK, response.status)
        assertTrue(response.body.contains("my-service"))
        assertTrue(response.body.contains("OK with details"))
        assertTrue(response.body.contains("healthy"))
    }

    @Test
    fun `deserializes request with default values`() = runTest {
        val response = handler.invokeUnary(
            serviceName = "health.v1.StatusService",
            methodName = "GetStatus",
            requestBody = """{"serviceName":"test-service"}""",
            contentType = "application/json"
        )

        assertEquals(GrpcStatus.OK, response.status)
        assertTrue(response.body.contains("test-service"))
        // includeDetails defaults to false, so status should be "OK" not "OK with details"
        assertTrue(response.body.contains(""""status":"OK""""))
    }

    @Test
    fun `handles nested request fields correctly`() = runTest {
        val response = handler.invokeUnary(
            serviceName = "health.v1.StatusService",
            methodName = "GreetUser",
            requestBody = """{"id":42,"name":"Alice"}""",
            contentType = "application/json"
        )

        assertEquals(GrpcStatus.OK, response.status)
        assertTrue(response.body.contains("42"))
        assertTrue(response.body.contains("Alice"))
        assertTrue(response.body.contains("Hello, Alice!"))
    }

    @Test
    fun `serializes complex response type to JSON`() = runTest {
        val response = handler.invokeUnary(
            serviceName = "health.v1.StatusService",
            methodName = "GetStatus",
            requestBody = """{"serviceName":"json-test","includeDetails":false}""",
            contentType = "application/json"
        )

        assertEquals(GrpcStatus.OK, response.status)
        // Verify the response is properly serialized JSON
        assertTrue(response.body.contains(""""serviceName":"json-test""""))
        assertTrue(response.body.contains(""""healthy":true"""))
    }

    @Test
    fun `handles full request-response cycle via handle method`() = runTest {
        val response = handler.handle(
            path = "/health.v1.StatusService/GetStatus",
            body = """{"serviceName":"handle-test","includeDetails":true}""",
            contentType = "application/json"
        )

        assertEquals(GrpcStatus.OK, response.status)
        assertTrue(response.body.contains("handle-test"))
        assertTrue(response.body.contains("OK with details"))
    }
}

/**
 * TDD Tests for GrpcResponse.
 */
class GrpcResponseTest {

    @Test
    fun `creates success response`() {
        val response = GrpcResponse.success("""{"result":"ok"}""", "application/json")

        assertEquals(GrpcStatus.OK, response.status)
        assertEquals("""{"result":"ok"}""", response.body)
        assertEquals("application/json", response.contentType)
    }

    @Test
    fun `creates error response`() {
        val response = GrpcResponse.error(GrpcStatus.NOT_FOUND, "Resource not found")

        assertEquals(GrpcStatus.NOT_FOUND, response.status)
        assertTrue(response.body.contains("Resource not found"))
    }

    @Test
    fun `creates error response from exception`() {
        val exception = GrpcException.permissionDenied("Access denied")
        val response = GrpcResponse.fromException(exception)

        assertEquals(GrpcStatus.PERMISSION_DENIED, response.status)
        assertTrue(response.body.contains("Access denied"))
    }
}

/**
 * Tests for server streaming SSE support in GrpcHttpHandler.
 */
class GrpcHttpHandlerStreamingTest {

    @Serializable
    data class StreamRequest(val count: Int)

    @Serializable
    data class StreamItem(val index: Int, val message: String)

    private val streamingService = grpcService("StreamService", "stream.v1") {
        serverStreaming<StreamRequest, StreamItem>("GetItems") { request ->
            flow {
                repeat(request.count) { i ->
                    emit(StreamItem(i, "Item $i"))
                }
            }
        }
        serverStreaming<StreamRequest, StreamItem>("GetItemsEmpty") { _ ->
            flow { }
        }
        serverStreaming<StreamRequest, StreamItem>("GetItemsError") { _ ->
            flow {
                emit(StreamItem(0, "First item"))
                throw GrpcException.invalidArgument("Simulated error")
            }
        }
    }

    private val adapter = GrpcAdapter(listOf(streamingService))
    private val handler = GrpcHttpHandler(adapter)

    @Test
    fun `streams SSE events for server streaming method`() = runTest {
        val events = handler.handleServerStreamingSSE(
            serviceName = "stream.v1.StreamService",
            methodName = "GetItems",
            requestBody = """{"count":3}""",
            contentType = "application/json"
        ).toList()

        assertEquals(3, events.size)
        // Verify SSE format
        assertTrue(events[0].contains("data:"))
        assertTrue(events[0].contains("index"))
        assertTrue(events[0].contains("\"index\":0"))
        assertTrue(events[0].endsWith("\n\n"))

        assertTrue(events[1].contains("\"index\":1"))
        assertTrue(events[2].contains("\"index\":2"))
    }

    @Test
    fun `returns empty for empty streaming response`() = runTest {
        val events = handler.handleServerStreamingSSE(
            serviceName = "stream.v1.StreamService",
            methodName = "GetItemsEmpty",
            requestBody = """{"count":0}""",
            contentType = "application/json"
        ).toList()

        assertEquals(0, events.size)
    }

    @Test
    fun `streams single item correctly`() = runTest {
        val events = handler.handleServerStreamingSSE(
            serviceName = "stream.v1.StreamService",
            methodName = "GetItems",
            requestBody = """{"count":1}""",
            contentType = "application/json"
        ).toList()

        assertEquals(1, events.size)
        assertTrue(events[0].contains("data:"))
        assertTrue(events[0].contains("\"index\":0"))
        assertTrue(events[0].contains("\"message\":\"Item 0\""))
    }

    @Test
    fun `throws exception for non-streaming method`() = runTest {
        val unaryService = grpcService("UnaryService", "unary.v1") {
            unary<String, String>("Echo") { it }
        }
        val unaryAdapter = GrpcAdapter(listOf(unaryService))
        val unaryHandler = GrpcHttpHandler(unaryAdapter)

        val exception = assertFailsWith<GrpcException> {
            unaryHandler.handleServerStreamingSSE(
                serviceName = "unary.v1.UnaryService",
                methodName = "Echo",
                requestBody = """"test"""",
                contentType = "application/json"
            ).toList()
        }
        assertEquals(GrpcStatus.INTERNAL, exception.status)
    }

    @Test
    fun `serverStreamingEvents works with path format`() = runTest {
        val events = handler.serverStreamingEvents(
            path = "/stream.v1.StreamService/GetItems",
            body = """{"count":2}""",
            contentType = "application/json"
        ).toList()

        assertEquals(2, events.size)
        assertTrue(events[0].contains("\"index\":0"))
        assertTrue(events[1].contains("\"index\":1"))
    }

    @Test
    fun `serverStreamingEvents throws for invalid path`() = runTest {
        val exception = assertFailsWith<GrpcException> {
            handler.serverStreamingEvents(
                path = "/invalid",
                body = "{}",
                contentType = "application/json"
            ).toList()
        }
        assertEquals(GrpcStatus.INVALID_ARGUMENT, exception.status)
    }

    @Test
    fun `serverStreamingEvents throws for non-streaming method`() = runTest {
        val mixedService = grpcService("MixedService", "mixed.v1") {
            unary<String, String>("Echo") { it }
            serverStreaming<StreamRequest, StreamItem>("Stream") { request ->
                flow { repeat(request.count) { emit(StreamItem(it, "Item")) } }
            }
        }
        val mixedAdapter = GrpcAdapter(listOf(mixedService))
        val mixedHandler = GrpcHttpHandler(mixedAdapter)

        val exception = assertFailsWith<GrpcException> {
            mixedHandler.serverStreamingEvents(
                path = "/mixed.v1.MixedService/Echo",
                body = """"test"""",
                contentType = "application/json"
            ).toList()
        }
        assertEquals(GrpcStatus.INVALID_ARGUMENT, exception.status)
    }
}
