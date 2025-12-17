package codes.yousef.aether.web

import codes.yousef.aether.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class PathParamsTest {

    private class TestRequest(
        override val method: HttpMethod = HttpMethod.GET,
        override val uri: String = "/",
        override val path: String = "/",
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
        override suspend fun write(data: ByteArray) {}
        override suspend fun write(data: String) {}
        override suspend fun end() {}
    }

    private class TestExchange(
        override val request: Request = TestRequest(),
        override val response: Response = TestResponse(),
        override val attributes: Attributes = Attributes()
    ) : Exchange

    @Test
    fun testPathParamBasic() {
        val exchange = TestExchange()
        exchange.setPathParam("id", "123")

        val id = exchange.pathParam("id")
        assertEquals("123", id)
    }

    @Test
    fun testPathParamNotFound() {
        val exchange = TestExchange()

        val id = exchange.pathParam("id")
        assertNull(id)
    }

    @Test
    fun testPathParamOrThrow() {
        val exchange = TestExchange()
        exchange.setPathParam("id", "456")

        val id = exchange.pathParamOrThrow("id")
        assertEquals("456", id)
    }

    @Test
    fun testPathParamOrThrowNotFound() {
        val exchange = TestExchange()

        assertFailsWith<IllegalStateException> {
            exchange.pathParamOrThrow("id")
        }
    }

    @Test
    fun testPathParamsMultiple() {
        val exchange = TestExchange()
        exchange.setPathParam("userId", "100")
        exchange.setPathParam("postId", "200")

        val params = exchange.pathParams()
        assertEquals(2, params.size)
        assertEquals("100", params["userId"])
        assertEquals("200", params["postId"])
    }

    @Test
    fun testPathParamsEmpty() {
        val exchange = TestExchange()

        val params = exchange.pathParams()
        assertEquals(0, params.size)
    }

    @Test
    fun testPathParamInt() {
        val exchange = TestExchange()
        exchange.setPathParam("id", "123")

        val id = exchange.pathParamInt("id")
        assertEquals(123, id)
    }

    @Test
    fun testPathParamIntInvalid() {
        val exchange = TestExchange()
        exchange.setPathParam("id", "abc")

        val id = exchange.pathParamInt("id")
        assertNull(id)
    }

    @Test
    fun testPathParamIntNotFound() {
        val exchange = TestExchange()

        val id = exchange.pathParamInt("id")
        assertNull(id)
    }

    @Test
    fun testPathParamLong() {
        val exchange = TestExchange()
        exchange.setPathParam("id", "9876543210")

        val id = exchange.pathParamLong("id")
        assertEquals(9876543210L, id)
    }

    @Test
    fun testPathParamLongInvalid() {
        val exchange = TestExchange()
        exchange.setPathParam("id", "not-a-number")

        val id = exchange.pathParamLong("id")
        assertNull(id)
    }

    @Test
    fun testPathParamDouble() {
        val exchange = TestExchange()
        exchange.setPathParam("price", "19.99")

        val price = exchange.pathParamDouble("price")
        assertEquals(19.99, price)
    }

    @Test
    fun testPathParamDoubleInvalid() {
        val exchange = TestExchange()
        exchange.setPathParam("price", "invalid")

        val price = exchange.pathParamDouble("price")
        assertNull(price)
    }

    @Test
    fun testPathParamBoolean() {
        val exchange = TestExchange()
        exchange.setPathParam("active", "true")

        val active = exchange.pathParamBoolean("active")
        assertEquals(true, active)
    }

    @Test
    fun testPathParamBooleanFalse() {
        val exchange = TestExchange()
        exchange.setPathParam("active", "false")

        val active = exchange.pathParamBoolean("active")
        assertEquals(false, active)
    }

    @Test
    fun testPathParamBooleanInvalid() {
        val exchange = TestExchange()
        exchange.setPathParam("active", "yes")

        val active = exchange.pathParamBoolean("active")
        assertNull(active)
    }

    @Test
    fun testPathParamOverwrite() {
        val exchange = TestExchange()
        exchange.setPathParam("id", "123")
        exchange.setPathParam("id", "456")

        val id = exchange.pathParam("id")
        assertEquals("456", id)
    }

    @Test
    fun testPathParamWithSpecialCharacters() {
        val exchange = TestExchange()
        exchange.setPathParam("slug", "hello-world_123")

        val slug = exchange.pathParam("slug")
        assertEquals("hello-world_123", slug)
    }

    @Test
    fun testPathParamIntNegative() {
        val exchange = TestExchange()
        exchange.setPathParam("value", "-42")

        val value = exchange.pathParamInt("value")
        assertEquals(-42, value)
    }

    @Test
    fun testPathParamDoubleScientificNotation() {
        val exchange = TestExchange()
        exchange.setPathParam("number", "1.23e-4")

        val number = exchange.pathParamDouble("number")
        assertEquals(1.23e-4, number)
    }
}
