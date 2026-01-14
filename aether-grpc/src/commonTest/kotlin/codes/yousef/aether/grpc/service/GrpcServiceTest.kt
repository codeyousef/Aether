package codes.yousef.aether.grpc.service

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * TDD Tests for GrpcService abstraction.
 */
class GrpcServiceTest {

    // Test messages
    @Serializable
    data class GetUserRequest(val id: String)

    @Serializable
    data class User(val id: String, val name: String)

    @Serializable
    data class ListUsersRequest(val pageSize: Int = 10)

    @Serializable
    data class Empty(val dummy: String = "")

    @Test
    fun `GrpcMethodType has all standard types`() {
        // Verify all gRPC method types exist
        assertEquals("UNARY", GrpcMethodType.UNARY.name)
        assertEquals("SERVER_STREAMING", GrpcMethodType.SERVER_STREAMING.name)
        assertEquals("CLIENT_STREAMING", GrpcMethodType.CLIENT_STREAMING.name)
        assertEquals("BIDI_STREAMING", GrpcMethodType.BIDI_STREAMING.name)
    }

    @Test
    fun `GrpcMethodDescriptor holds method metadata`() {
        val descriptor = GrpcMethodDescriptor(
            name = "GetUser",
            fullName = "users.UserService/GetUser",
            type = GrpcMethodType.UNARY,
            inputType = "users.GetUserRequest",
            outputType = "users.User"
        )

        assertEquals("GetUser", descriptor.name)
        assertEquals("users.UserService/GetUser", descriptor.fullName)
        assertEquals(GrpcMethodType.UNARY, descriptor.type)
        assertEquals("users.GetUserRequest", descriptor.inputType)
        assertEquals("users.User", descriptor.outputType)
    }

    @Test
    fun `GrpcServiceDescriptor holds service metadata`() {
        val descriptor = GrpcServiceDescriptor(
            name = "UserService",
            fullName = "users.UserService",
            methods = listOf(
                GrpcMethodDescriptor(
                    name = "GetUser",
                    fullName = "users.UserService/GetUser",
                    type = GrpcMethodType.UNARY,
                    inputType = "users.GetUserRequest",
                    outputType = "users.User"
                )
            )
        )

        assertEquals("UserService", descriptor.name)
        assertEquals("users.UserService", descriptor.fullName)
        assertEquals(1, descriptor.methods.size)
    }

    @Test
    fun `unaryMethod creates correct descriptor`() {
        val method = unaryMethod<GetUserRequest, User>(
            name = "GetUser",
            serviceName = "users.UserService"
        ) { request ->
            User(id = request.id, name = "Test User")
        }

        assertEquals("GetUser", method.descriptor.name)
        assertEquals(GrpcMethodType.UNARY, method.descriptor.type)
    }

    @Test
    fun `unaryMethod handler is invokable`() = runTest {
        val method = unaryMethod<GetUserRequest, User>(
            name = "GetUser",
            serviceName = "users.UserService"
        ) { request ->
            User(id = request.id, name = "User ${request.id}")
        }

        val request = GetUserRequest(id = "123")
        val response = method.invoke(request)

        assertEquals("123", response.id)
        assertEquals("User 123", response.name)
    }

    @Test
    fun `serverStreamingMethod creates correct descriptor`() {
        val method = serverStreamingMethod<ListUsersRequest, User>(
            name = "ListUsers",
            serviceName = "users.UserService"
        ) { request ->
            flowOf(
                User("1", "User 1"),
                User("2", "User 2")
            )
        }

        assertEquals("ListUsers", method.descriptor.name)
        assertEquals(GrpcMethodType.SERVER_STREAMING, method.descriptor.type)
    }

    @Test
    fun `serverStreamingMethod handler returns Flow`() = runTest {
        val method = serverStreamingMethod<ListUsersRequest, User>(
            name = "ListUsers",
            serviceName = "users.UserService"
        ) { request ->
            flowOf(
                User("1", "User 1"),
                User("2", "User 2"),
                User("3", "User 3")
            )
        }

        val request = ListUsersRequest(pageSize = 3)
        val responses = method.invoke(request).toList()

        assertEquals(3, responses.size)
        assertEquals("1", responses[0].id)
        assertEquals("2", responses[1].id)
        assertEquals("3", responses[2].id)
    }

    @Test
    fun `GrpcServiceDefinition combines service descriptor and methods`() {
        val descriptor = GrpcServiceDescriptor(
            name = "UserService",
            fullName = "users.UserService",
            methods = emptyList()
        )

        val unary = unaryMethod<GetUserRequest, User>(
            name = "GetUser",
            serviceName = "users.UserService"
        ) { User(it.id, "Test") }

        val streaming = serverStreamingMethod<ListUsersRequest, User>(
            name = "ListUsers",
            serviceName = "users.UserService"
        ) { flowOf(User("1", "User 1")) }

        val service = GrpcServiceDefinition(
            descriptor = descriptor,
            methods = mapOf(
                "GetUser" to unary,
                "ListUsers" to streaming
            )
        )

        assertEquals("UserService", service.descriptor.name)
        assertEquals(2, service.methods.size)
        assertNotNull(service.methods["GetUser"])
        assertNotNull(service.methods["ListUsers"])
    }

    @Test
    fun `GrpcServiceDefinition findMethod returns method by name`() {
        val unary = unaryMethod<GetUserRequest, User>(
            name = "GetUser",
            serviceName = "users.UserService"
        ) { User(it.id, "Test") }

        val service = GrpcServiceDefinition(
            descriptor = GrpcServiceDescriptor(
                name = "UserService",
                fullName = "users.UserService",
                methods = listOf(unary.descriptor)
            ),
            methods = mapOf("GetUser" to unary)
        )

        val method = service.findMethod("GetUser")
        assertNotNull(method)
        assertEquals("GetUser", method.descriptor.name)
    }

    @Test
    fun `GrpcServiceDefinition findMethod returns null for unknown method`() {
        val service = GrpcServiceDefinition(
            descriptor = GrpcServiceDescriptor(
                name = "UserService",
                fullName = "users.UserService",
                methods = emptyList()
            ),
            methods = emptyMap()
        )

        val method = service.findMethod("UnknownMethod")
        assertEquals(null, method)
    }
}
