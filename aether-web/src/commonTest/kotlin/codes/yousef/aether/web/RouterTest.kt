package codes.yousef.aether.web

import codes.yousef.aether.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouterTest {

    private class TestRequest(
        override val method: HttpMethod,
        override val uri: String,
        override val path: String,
        override val query: String? = null,
        override val headers: Headers = Headers.Empty,
        override val cookies: Cookies = Cookies.Empty
    ) : Request {
        override suspend fun bodyBytes(): ByteArray = ByteArray(0)
    }

    private class TestResponse : Response {
        override var statusCode: Int = 200
        override var statusMessage: String? = null
        override val headers: Headers.HeadersBuilder = Headers.HeadersBuilder()
        override val cookies: MutableList<Cookie> = mutableListOf()
        val writtenData = StringBuilder()
        var ended = false

        override suspend fun write(data: ByteArray) {
            writtenData.append(data.decodeToString())
        }

        override suspend fun write(data: String) {
            writtenData.append(data)
        }

        override suspend fun end() {
            ended = true
        }
    }

    private class TestExchange(
        override val request: Request,
        override val response: Response = TestResponse(),
        override val attributes: Attributes = Attributes()
    ) : Exchange

    @Test
    fun testGetRoute() {
        val router = router {
            get("/hello") { exchange ->
                exchange.respond(body = "Hello, World!")
            }
        }

        val request = TestRequest(HttpMethod.GET, "/hello", "/hello")
        val response = TestResponse()
        val exchange = TestExchange(request, response)

        val match = router.findRoute(HttpMethod.GET, "/hello")
        assertNotNull(match)
    }

    @Test
    fun testPostRoute() {
        val router = router {
            post("/users") { exchange ->
                exchange.respond(body = "User created")
            }
        }

        val match = router.findRoute(HttpMethod.POST, "/users")
        assertNotNull(match)
    }

    @Test
    fun testPutRoute() {
        val router = router {
            put("/users/:id") { exchange ->
                val id = exchange.pathParam("id")
                exchange.respond(body = "User $id updated")
            }
        }

        val match = router.findRoute(HttpMethod.PUT, "/users/123")
        assertNotNull(match)
        assertEquals("123", match.params["id"])
    }

    @Test
    fun testDeleteRoute() {
        val router = router {
            delete("/users/:id") { exchange ->
                val id = exchange.pathParam("id")
                exchange.respond(body = "User $id deleted")
            }
        }

        val match = router.findRoute(HttpMethod.DELETE, "/users/456")
        assertNotNull(match)
        assertEquals("456", match.params["id"])
    }

    @Test
    fun testPatchRoute() {
        val router = router {
            patch("/users/:id") { exchange ->
                exchange.respond(body = "User patched")
            }
        }

        val match = router.findRoute(HttpMethod.PATCH, "/users/789")
        assertNotNull(match)
    }

    @Test
    fun testHeadRoute() {
        val router = router {
            head("/status") { exchange ->
                exchange.respond(body = "")
            }
        }

        val match = router.findRoute(HttpMethod.HEAD, "/status")
        assertNotNull(match)
    }

    @Test
    fun testOptionsRoute() {
        val router = router {
            options("/api") { exchange ->
                exchange.respond(body = "OPTIONS")
            }
        }

        val match = router.findRoute(HttpMethod.OPTIONS, "/api")
        assertNotNull(match)
    }

    @Test
    fun testMultipleRoutes() {
        val router = router {
            get("/") { exchange -> exchange.respond(body = "home") }
            get("/about") { exchange -> exchange.respond(body = "about") }
            get("/users") { exchange -> exchange.respond(body = "users") }
            get("/users/:id") { exchange -> exchange.respond(body = "user") }
            post("/users") { exchange -> exchange.respond(body = "create") }
        }

        assertNotNull(router.findRoute(HttpMethod.GET, "/"))
        assertNotNull(router.findRoute(HttpMethod.GET, "/about"))
        assertNotNull(router.findRoute(HttpMethod.GET, "/users"))
        assertNotNull(router.findRoute(HttpMethod.GET, "/users/123"))
        assertNotNull(router.findRoute(HttpMethod.POST, "/users"))
    }

    @Test
    fun testPathParameterExtraction() {
        val router = router {
            get("/users/:userId/posts/:postId") { exchange ->
                val userId = exchange.pathParam("userId")
                val postId = exchange.pathParam("postId")
                exchange.respond(body = "User: $userId, Post: $postId")
            }
        }

        val match = router.findRoute(HttpMethod.GET, "/users/42/posts/99")
        assertNotNull(match)
        assertEquals("42", match.params["userId"])
        assertEquals("99", match.params["postId"])
    }

    @Test
    fun testMethodSeparation() {
        val router = router {
            get("/resource") { exchange -> exchange.respond(body = "GET") }
            post("/resource") { exchange -> exchange.respond(body = "POST") }
            put("/resource") { exchange -> exchange.respond(body = "PUT") }
            delete("/resource") { exchange -> exchange.respond(body = "DELETE") }
        }

        assertNotNull(router.findRoute(HttpMethod.GET, "/resource"))
        assertNotNull(router.findRoute(HttpMethod.POST, "/resource"))
        assertNotNull(router.findRoute(HttpMethod.PUT, "/resource"))
        assertNotNull(router.findRoute(HttpMethod.DELETE, "/resource"))
    }

    @Test
    fun testAsMiddleware() {
        var handlerCalled = false
        val router = router {
            get("/test") { exchange ->
                handlerCalled = true
                exchange.respond(body = "test")
            }
        }

        val middleware = router.asMiddleware()
        assertNotNull(middleware)
    }

    @Test
    fun testComplexRouting() {
        val router = router {
            get("/") { exchange -> exchange.respond(body = "home") }
            get("/api/v1/users") { exchange -> exchange.respond(body = "users_list") }
            get("/api/v1/users/:id") { exchange -> exchange.respond(body = "user_detail") }
            post("/api/v1/users") { exchange -> exchange.respond(body = "user_create") }
            put("/api/v1/users/:id") { exchange -> exchange.respond(body = "user_update") }
            delete("/api/v1/users/:id") { exchange -> exchange.respond(body = "user_delete") }
            get("/api/v1/users/:userId/posts") { exchange -> exchange.respond(body = "user_posts") }
            get("/api/v1/users/:userId/posts/:postId") { exchange -> exchange.respond(body = "post_detail") }
        }

        assertEquals(0, router.findRoute(HttpMethod.GET, "/")?.params?.size)
        assertEquals(0, router.findRoute(HttpMethod.GET, "/api/v1/users")?.params?.size)
        assertEquals(1, router.findRoute(HttpMethod.GET, "/api/v1/users/123")?.params?.size)
        assertEquals(0, router.findRoute(HttpMethod.POST, "/api/v1/users")?.params?.size)
        assertEquals(1, router.findRoute(HttpMethod.PUT, "/api/v1/users/456")?.params?.size)
        assertEquals(1, router.findRoute(HttpMethod.DELETE, "/api/v1/users/789")?.params?.size)
        assertEquals(1, router.findRoute(HttpMethod.GET, "/api/v1/users/100/posts")?.params?.size)
        assertEquals(2, router.findRoute(HttpMethod.GET, "/api/v1/users/100/posts/200")?.params?.size)
    }
}
