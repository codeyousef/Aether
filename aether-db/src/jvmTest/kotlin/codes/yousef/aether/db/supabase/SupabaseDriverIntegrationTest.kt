package codes.yousef.aether.db.supabase

import codes.yousef.aether.db.*
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for SupabaseDriver using WireMock to simulate the Supabase API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupabaseDriverIntegrationTest {

    private lateinit var wireMockServer: WireMockServer
    private lateinit var driver: SupabaseDriver

    @BeforeAll
    fun setup() {
        wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMockServer.start()
        
        driver = SupabaseDriver.create(
            projectUrl = "http://localhost:${wireMockServer.port()}",
            apiKey = "test-api-key"
        )
    }

    @AfterAll
    fun teardown() {
        runBlocking { driver.close() }
        wireMockServer.stop()
    }

    @BeforeEach
    fun resetMocks() {
        wireMockServer.resetAll()
    }

    // ============= SELECT Tests =============

    @Test
    fun `executeQuery returns rows for simple SELECT`() = runBlocking {
        // Setup mock
        wireMockServer.stubFor(
            get(urlPathEqualTo("/rest/v1/users"))
                .withHeader("apikey", equalTo("test-api-key"))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [
                                {"id": 1, "username": "alice", "email": "alice@example.com", "age": 30},
                                {"id": 2, "username": "bob", "email": "bob@example.com", "age": 25}
                            ]
                        """.trimIndent())
                )
        )

        // Execute
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users"
        )
        val rows = driver.executeQuery(query)

        // Verify
        assertEquals(2, rows.size)
        assertEquals("alice", rows[0].getString("username"))
        assertEquals(1, rows[0].getInt("id"))
        assertEquals("bob", rows[1].getString("username"))
        assertEquals(25, rows[1].getInt("age"))
    }

    @Test
    fun `executeQuery handles empty result`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/rest/v1/users"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")
                )
        )

        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users"
        )
        val rows = driver.executeQuery(query)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `executeQuery with filter generates correct query params`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/rest/v1/users"))
                .withQueryParam("active", equalTo("eq.true"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""[{"id": 1, "username": "alice", "active": true}]""")
                )
        )

        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "active"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.BooleanValue(true))
            )
        )
        val rows = driver.executeQuery(query)

        assertEquals(1, rows.size)
        assertEquals(true, rows[0].getBoolean("active"))
    }

    @Test
    fun `executeQuery with ordering generates correct query params`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/rest/v1/users"))
                .withQueryParam("order", equalTo("created_at.desc"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""[{"id": 1, "username": "alice"}]""")
                )
        )

        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            orderBy = listOf(
                OrderByClause(Expression.ColumnRef(column = "created_at"), OrderDirection.DESC)
            )
        )
        driver.executeQuery(query)

        wireMockServer.verify(
            getRequestedFor(urlPathEqualTo("/rest/v1/users"))
                .withQueryParam("order", equalTo("created_at.desc"))
        )
    }

    @Test
    fun `executeQuery with pagination generates correct query params`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/rest/v1/users"))
                .withQueryParam("limit", equalTo("10"))
                .withQueryParam("offset", equalTo("20"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")
                )
        )

        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            limit = 10,
            offset = 20
        )
        driver.executeQuery(query)

        wireMockServer.verify(
            getRequestedFor(urlPathEqualTo("/rest/v1/users"))
                .withQueryParam("limit", equalTo("10"))
                .withQueryParam("offset", equalTo("20"))
        )
    }

    @Test
    fun `executeQuery throws on HTTP error`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/rest/v1/users"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withBody("""{"message": "Invalid API key"}""")
                )
        )

        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users"
        )

        assertThrows<DatabaseException> {
            runBlocking { driver.executeQuery(query) }
        }
    }

    // ============= INSERT Tests =============

    @Test
    fun `executeUpdate inserts row and returns count`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/rest/v1/users"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.username", equalTo("newuser")))
                .withRequestBody(matchingJsonPath("$.email", equalTo("new@example.com")))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""[{"id": 3, "username": "newuser", "email": "new@example.com"}]""")
                )
        )

        val query = InsertQuery(
            table = "users",
            columns = listOf("username", "email"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("newuser")),
                Expression.Literal(SqlValue.StringValue("new@example.com"))
            )
        )
        val count = driver.executeUpdate(query)

        assertEquals(1, count)
    }

    @Test
    fun `executeUpdate sends correct JSON body for insert`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/rest/v1/users"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""[{"id": 1}]""")
                )
        )

        val query = InsertQuery(
            table = "users",
            columns = listOf("username", "age", "active"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("testuser")),
                Expression.Literal(SqlValue.IntValue(30)),
                Expression.Literal(SqlValue.BooleanValue(true))
            )
        )
        driver.executeUpdate(query)

        wireMockServer.verify(
            postRequestedFor(urlPathEqualTo("/rest/v1/users"))
                .withRequestBody(matchingJsonPath("$.username", equalTo("testuser")))
                .withRequestBody(matchingJsonPath("$.age", equalTo("30")))
                .withRequestBody(matchingJsonPath("$.active", equalTo("true")))
        )
    }

    // ============= UPDATE Tests =============

    @Test
    fun `executeUpdate updates row with PATCH`() = runBlocking {
        wireMockServer.stubFor(
            patch(urlPathEqualTo("/rest/v1/users"))
                .withQueryParam("id", equalTo("eq.123"))
                .withRequestBody(matchingJsonPath("$.username", equalTo("updatedname")))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""[{"id": 123, "username": "updatedname"}]""")
                )
        )

        val query = UpdateQuery(
            table = "users",
            assignments = mapOf(
                "username" to Expression.Literal(SqlValue.StringValue("updatedname"))
            ),
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.IntValue(123))
            )
        )
        val count = driver.executeUpdate(query)

        assertEquals(1, count)
    }

    // ============= DELETE Tests =============

    @Test
    fun `executeUpdate deletes row`() = runBlocking {
        wireMockServer.stubFor(
            delete(urlPathEqualTo("/rest/v1/users"))
                .withQueryParam("id", equalTo("eq.456"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""[{"id": 456}]""")
                )
        )

        val query = DeleteQuery(
            table = "users",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.IntValue(456))
            )
        )
        val count = driver.executeUpdate(query)

        assertEquals(1, count)
    }

    // ============= Row Type Tests =============

    @Test
    fun `SupabaseRow handles various data types`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/rest/v1/mixed"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [{
                                "string_col": "hello",
                                "int_col": 42,
                                "long_col": 9876543210,
                                "double_col": 3.14159,
                                "bool_col": true,
                                "null_col": null
                            }]
                        """.trimIndent())
                )
        )

        val query = SelectQuery(columns = listOf(Expression.Star), from = "mixed")
        val rows = driver.executeQuery(query)

        assertEquals(1, rows.size)
        val row = rows[0]

        assertEquals("hello", row.getString("string_col"))
        assertEquals(42, row.getInt("int_col"))
        assertEquals(9876543210L, row.getLong("long_col"))
        assertEquals(3.14159, row.getDouble("double_col")!!, 0.00001)
        assertEquals(true, row.getBoolean("bool_col"))
        assertNull(row.getString("null_col"))
        assertTrue(row.hasColumn("string_col"))
        assertFalse(row.hasColumn("nonexistent"))
        assertTrue(row.getColumnNames().containsAll(listOf("string_col", "int_col", "bool_col")))
    }

    // ============= RPC Tests =============

    @Test
    fun `rpc calls function endpoint`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/rest/v1/rpc/get_user_stats"))
                .withRequestBody(matchingJsonPath("$.user_id", equalTo("123")))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""[{"total_posts": 10, "total_comments": 50}]""")
                )
        )

        val rows = driver.rpc("get_user_stats", mapOf("user_id" to "123"))

        assertEquals(1, rows.size)
        assertEquals(10, rows[0].getInt("total_posts"))
        assertEquals(50, rows[0].getInt("total_comments"))
    }

    // ============= Error Handling Tests =============

    @Test
    fun `executeDDL throws UnsupportedOperationException`() {
        val query = CreateTableQuery(
            table = "test",
            columns = listOf(ColumnDefinition("id", "INTEGER"))
        )

        assertThrows<DatabaseException> {
            runBlocking { driver.executeDDL(query) }
        }
    }

    @Test
    fun `executeQueryRaw throws for raw SQL`() {
        assertThrows<DatabaseException> {
            runBlocking { driver.executeQueryRaw("SELECT * FROM users") }
        }
    }

    @Test
    fun `execute throws for raw SQL with params`() {
        assertThrows<DatabaseException> {
            runBlocking { driver.execute("SELECT * FROM users WHERE id = \$1", listOf(SqlValue.IntValue(1))) }
        }
    }

    @Test
    fun `getColumns throws for introspection`() {
        assertThrows<DatabaseException> {
            runBlocking { driver.getColumns("users") }
        }
    }
}
