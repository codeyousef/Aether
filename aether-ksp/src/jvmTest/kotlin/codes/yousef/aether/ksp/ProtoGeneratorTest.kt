package codes.yousef.aether.ksp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD Tests for proto generation - type mapping and proto output.
 */
class KotlinToProtoMapperTest {

    private val mapper = KotlinToProtoMapper()

    @Test
    fun `maps Int to int32`() {
        assertEquals("int32", mapper.mapType("kotlin.Int"))
    }

    @Test
    fun `maps Long to int64`() {
        assertEquals("int64", mapper.mapType("kotlin.Long"))
    }

    @Test
    fun `maps Float to float`() {
        assertEquals("float", mapper.mapType("kotlin.Float"))
    }

    @Test
    fun `maps Double to double`() {
        assertEquals("double", mapper.mapType("kotlin.Double"))
    }

    @Test
    fun `maps Boolean to bool`() {
        assertEquals("bool", mapper.mapType("kotlin.Boolean"))
    }

    @Test
    fun `maps String to string`() {
        assertEquals("string", mapper.mapType("kotlin.String"))
    }

    @Test
    fun `maps ByteArray to bytes`() {
        assertEquals("bytes", mapper.mapType("kotlin.ByteArray"))
    }

    @Test
    fun `maps List to repeated`() {
        assertEquals("repeated string", mapper.mapType("kotlin.collections.List", "kotlin.String"))
    }

    @Test
    fun `maps List of Int to repeated int32`() {
        assertEquals("repeated int32", mapper.mapType("kotlin.collections.List", "kotlin.Int"))
    }

    @Test
    fun `maps custom message type to message name`() {
        assertEquals("User", mapper.mapType("com.example.User"))
    }

    @Test
    fun `maps nullable type to optional`() {
        assertTrue(mapper.isOptional("kotlin.String?"))
    }

    @Test
    fun `non-nullable type is not optional`() {
        assertEquals(false, mapper.isOptional("kotlin.String"))
    }

    @Test
    fun `maps UInt to uint32`() {
        assertEquals("uint32", mapper.mapType("kotlin.UInt"))
    }

    @Test
    fun `maps ULong to uint64`() {
        assertEquals("uint64", mapper.mapType("kotlin.ULong"))
    }
}

class ProtoGeneratorTest {

    private val generator = ProtoGenerator()

    @Test
    fun `generates valid message syntax`() {
        val message = ProtoMessageInfo(
            name = "User",
            fields = listOf(
                ProtoFieldInfo("id", "string", 1),
                ProtoFieldInfo("name", "string", 2),
                ProtoFieldInfo("age", "int32", 3, optional = true)
            )
        )

        val proto = generator.generateMessage(message)

        assertContains(proto, "message User {")
        assertContains(proto, "string id = 1;")
        assertContains(proto, "string name = 2;")
        assertContains(proto, "optional int32 age = 3;")
        assertContains(proto, "}")
    }

    @Test
    fun `generates message with repeated field`() {
        val message = ProtoMessageInfo(
            name = "UserList",
            fields = listOf(
                ProtoFieldInfo("users", "User", 1, repeated = true)
            )
        )

        val proto = generator.generateMessage(message)

        assertContains(proto, "repeated User users = 1;")
    }

    @Test
    fun `generates service syntax`() {
        val service = ProtoServiceInfo(
            name = "UserService",
            methods = listOf(
                ProtoMethodInfo(
                    name = "GetUser",
                    inputType = "GetUserRequest",
                    outputType = "User"
                )
            )
        )

        val proto = generator.generateService(service)

        assertContains(proto, "service UserService {")
        assertContains(proto, "rpc GetUser(GetUserRequest) returns (User);")
        assertContains(proto, "}")
    }

    @Test
    fun `generates server streaming RPC`() {
        val service = ProtoServiceInfo(
            name = "UserService",
            methods = listOf(
                ProtoMethodInfo(
                    name = "ListUsers",
                    inputType = "ListUsersRequest",
                    outputType = "User",
                    serverStreaming = true
                )
            )
        )

        val proto = generator.generateService(service)

        assertContains(proto, "rpc ListUsers(ListUsersRequest) returns (stream User);")
    }

    @Test
    fun `generates client streaming RPC`() {
        val service = ProtoServiceInfo(
            name = "UploadService",
            methods = listOf(
                ProtoMethodInfo(
                    name = "Upload",
                    inputType = "Chunk",
                    outputType = "UploadResult",
                    clientStreaming = true
                )
            )
        )

        val proto = generator.generateService(service)

        assertContains(proto, "rpc Upload(stream Chunk) returns (UploadResult);")
    }

    @Test
    fun `generates bidi streaming RPC`() {
        val service = ProtoServiceInfo(
            name = "ChatService",
            methods = listOf(
                ProtoMethodInfo(
                    name = "Chat",
                    inputType = "ChatMessage",
                    outputType = "ChatMessage",
                    clientStreaming = true,
                    serverStreaming = true
                )
            )
        )

        val proto = generator.generateService(service)

        assertContains(proto, "rpc Chat(stream ChatMessage) returns (stream ChatMessage);")
    }

    @Test
    fun `generates complete proto file`() {
        val file = ProtoFileInfo(
            packageName = "users.v1",
            messages = listOf(
                ProtoMessageInfo(
                    name = "User",
                    fields = listOf(
                        ProtoFieldInfo("id", "string", 1),
                        ProtoFieldInfo("name", "string", 2)
                    )
                ),
                ProtoMessageInfo(
                    name = "GetUserRequest",
                    fields = listOf(
                        ProtoFieldInfo("id", "string", 1)
                    )
                )
            ),
            services = listOf(
                ProtoServiceInfo(
                    name = "UserService",
                    methods = listOf(
                        ProtoMethodInfo("GetUser", "GetUserRequest", "User")
                    )
                )
            )
        )

        val proto = generator.generateFile(file)

        assertContains(proto, "syntax = \"proto3\";")
        assertContains(proto, "package users.v1;")
        assertContains(proto, "message User {")
        assertContains(proto, "message GetUserRequest {")
        assertContains(proto, "service UserService {")
    }

    @Test
    fun `generates file with import`() {
        val file = ProtoFileInfo(
            packageName = "users.v1",
            imports = listOf("google/protobuf/timestamp.proto"),
            messages = listOf(
                ProtoMessageInfo(
                    name = "User",
                    fields = listOf(
                        ProtoFieldInfo("created_at", "google.protobuf.Timestamp", 1)
                    )
                )
            )
        )

        val proto = generator.generateFile(file)

        assertContains(proto, "import \"google/protobuf/timestamp.proto\";")
    }

    @Test
    fun `generates deprecated field option`() {
        val message = ProtoMessageInfo(
            name = "User",
            fields = listOf(
                ProtoFieldInfo("id", "string", 1),
                ProtoFieldInfo("old_field", "string", 2, deprecated = true)
            )
        )

        val proto = generator.generateMessage(message)

        assertContains(proto, "string old_field = 2 [deprecated = true];")
    }
}
