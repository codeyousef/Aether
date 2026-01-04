package codes.yousef.aether.db.firestore

import codes.yousef.aether.db.*
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for FirestoreDriver using WireMock to simulate the Firestore REST API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirestoreDriverIntegrationTest {

    private lateinit var wireMockServer: WireMockServer
    private lateinit var driver: FirestoreDriver
    private val projectId = "test-project"

    @BeforeAll
    fun setup() {
        wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMockServer.start()
        
        // Create driver with custom base URL pointing to WireMock
        driver = FirestoreDriver.createForTesting(
            projectId = projectId,
            apiKey = "test-api-key",
            baseUrl = "http://localhost:${wireMockServer.port()}/v1/projects/$projectId/databases/(default)/documents"
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
        // Setup mock for runQuery endpoint
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents:runQuery"))
                .withQueryParam("key", equalTo("test-api-key"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [
                                {
                                    "document": {
                                        "name": "projects/$projectId/databases/(default)/documents/users/doc1",
                                        "fields": {
                                            "username": {"stringValue": "alice"},
                                            "age": {"integerValue": "30"},
                                            "active": {"booleanValue": true}
                                        }
                                    }
                                },
                                {
                                    "document": {
                                        "name": "projects/$projectId/databases/(default)/documents/users/doc2",
                                        "fields": {
                                            "username": {"stringValue": "bob"},
                                            "age": {"integerValue": "25"},
                                            "active": {"booleanValue": false}
                                        }
                                    }
                                }
                            ]
                        """.trimIndent())
                )
        )

        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users"
        )
        val rows = driver.executeQuery(query)

        assertEquals(2, rows.size)
        assertEquals("alice", rows[0].getString("username"))
        assertEquals(30L, rows[0].getLong("age"))
        assertEquals(true, rows[0].getBoolean("active"))
        assertEquals("doc1", rows[0].getString("id"))
        assertEquals("bob", rows[1].getString("username"))
    }

    @Test
    fun `executeQuery handles empty result`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents:runQuery"))
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
    fun `executeQuery with filter generates correct structured query`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents:runQuery"))
                .withRequestBody(matchingJsonPath("$.structuredQuery.where.fieldFilter.op", equalTo("EQUAL")))
                .withRequestBody(matchingJsonPath("$.structuredQuery.where.fieldFilter.field.fieldPath", equalTo("active")))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [{
                                "document": {
                                    "name": "projects/$projectId/databases/(default)/documents/users/doc1",
                                    "fields": {"username": {"stringValue": "alice"}, "active": {"booleanValue": true}}
                                }
                            }]
                        """.trimIndent())
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
    fun `executeQuery with ordering generates correct structured query`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents:runQuery"))
                .withRequestBody(matchingJsonPath("$.structuredQuery.orderBy[0].direction", equalTo("DESCENDING")))
                .withRequestBody(matchingJsonPath("$.structuredQuery.orderBy[0].field.fieldPath", equalTo("created_at")))
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
            orderBy = listOf(
                OrderByClause(Expression.ColumnRef(column = "created_at"), OrderDirection.DESC)
            )
        )
        driver.executeQuery(query)

        wireMockServer.verify(
            postRequestedFor(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents:runQuery"))
                .withRequestBody(matchingJsonPath("$.structuredQuery.orderBy[0].direction", equalTo("DESCENDING")))
        )
    }

    @Test
    fun `executeQuery with limit generates correct structured query`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents:runQuery"))
                .withRequestBody(matchingJsonPath("$.structuredQuery.limit", equalTo("10")))
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
            limit = 10
        )
        driver.executeQuery(query)

        wireMockServer.verify(
            postRequestedFor(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents:runQuery"))
                .withRequestBody(matchingJsonPath("$.structuredQuery.limit", equalTo("10")))
        )
    }

    @Test
    fun `executeQuery throws on HTTP error`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents:runQuery"))
                .willReturn(
                    aResponse()
                        .withStatus(403)
                        .withBody("""{"error": {"message": "Permission denied"}}""")
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
    fun `executeUpdate inserts document`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents/users"))
                .withRequestBody(matchingJsonPath("$.fields.username.stringValue", equalTo("newuser")))
                .withRequestBody(matchingJsonPath("$.fields.age.integerValue", equalTo("25")))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "name": "projects/$projectId/databases/(default)/documents/users/generated_id",
                                "fields": {
                                    "username": {"stringValue": "newuser"},
                                    "age": {"integerValue": "25"}
                                }
                            }
                        """.trimIndent())
                )
        )

        val query = InsertQuery(
            table = "users",
            columns = listOf("username", "age"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("newuser")),
                Expression.Literal(SqlValue.IntValue(25))
            )
        )
        val count = driver.executeUpdate(query)

        assertEquals(1, count)
    }

    // ============= UPDATE Tests =============

    @Test
    fun `executeUpdate updates document with PATCH`() = runBlocking {
        wireMockServer.stubFor(
            patch(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents/users/doc123"))
                .withRequestBody(matchingJsonPath("$.fields.username.stringValue", equalTo("updatedname")))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "name": "projects/$projectId/databases/(default)/documents/users/doc123",
                                "fields": {"username": {"stringValue": "updatedname"}}
                            }
                        """.trimIndent())
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
                right = Expression.Literal(SqlValue.StringValue("doc123"))
            )
        )
        val count = driver.executeUpdate(query)

        assertEquals(1, count)
    }

    // ============= DELETE Tests =============

    @Test
    fun `executeUpdate deletes document`() = runBlocking {
        wireMockServer.stubFor(
            delete(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents/users/doc456"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")
                )
        )

        val query = DeleteQuery(
            table = "users",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue("doc456"))
            )
        )
        val count = driver.executeUpdate(query)

        assertEquals(1, count)
    }

    // ============= Row Type Tests =============

    @Test
    fun `FirestoreRow handles various Firestore value types`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents:runQuery"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [{
                                "document": {
                                    "name": "projects/$projectId/databases/(default)/documents/mixed/doc1",
                                    "fields": {
                                        "string_col": {"stringValue": "hello"},
                                        "int_col": {"integerValue": "42"},
                                        "double_col": {"doubleValue": 3.14159},
                                        "bool_col": {"booleanValue": true},
                                        "null_col": {"nullValue": null},
                                        "array_col": {"arrayValue": {"values": [{"stringValue": "a"}, {"stringValue": "b"}]}},
                                        "map_col": {"mapValue": {"fields": {"nested": {"stringValue": "value"}}}}
                                    }
                                }
                            }]
                        """.trimIndent())
                )
        )

        val query = SelectQuery(columns = listOf(Expression.Star), from = "mixed")
        val rows = driver.executeQuery(query)

        assertEquals(1, rows.size)
        val row = rows[0]

        assertEquals("hello", row.getString("string_col"))
        assertEquals(42L, row.getLong("int_col"))
        assertEquals(3.14159, row.getDouble("double_col")!!, 0.00001)
        assertEquals(true, row.getBoolean("bool_col"))
        assertNull(row.getValue("null_col"))
        assertEquals("doc1", row.getString("id"))
        
        // Array value
        val array = row.getValue("array_col") as? List<*>
        assertNotNull(array)
        assertEquals(2, array?.size)

        // Map value
        val map = row.getValue("map_col") as? Map<*, *>
        assertNotNull(map)
        assertEquals("value", map?.get("nested"))

        assertTrue(row.hasColumn("string_col"))
        assertFalse(row.hasColumn("nonexistent"))
    }

    // ============= Direct Document Operations =============

    @Test
    fun `getDocument returns single document`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents/users/doc123"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "name": "projects/$projectId/databases/(default)/documents/users/doc123",
                                "fields": {
                                    "username": {"stringValue": "alice"},
                                    "email": {"stringValue": "alice@example.com"}
                                }
                            }
                        """.trimIndent())
                )
        )

        val row = driver.getDocument("users", "doc123")

        assertNotNull(row)
        assertEquals("alice", row?.getString("username"))
        assertEquals("alice@example.com", row?.getString("email"))
        assertEquals("doc123", row?.getString("id"))
    }

    @Test
    fun `getDocument returns null for non-existent document`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents/users/nonexistent"))
                .willReturn(
                    aResponse()
                        .withStatus(404)
                        .withBody("""{"error": {"message": "Document not found"}}""")
                )
        )

        val row = driver.getDocument("users", "nonexistent")

        assertNull(row)
    }

    @Test
    fun `createDocument creates with auto-generated ID`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents/users"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "name": "projects/$projectId/databases/(default)/documents/users/auto_generated_id",
                                "fields": {"username": {"stringValue": "testuser"}}
                            }
                        """.trimIndent())
                )
        )

        val docId = driver.createDocument("users", data = mapOf("username" to "testuser"))

        assertEquals("auto_generated_id", docId)
    }

    @Test
    fun `createDocument creates with specific ID`() = runBlocking {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/projects/$projectId/databases/(default)/documents/users"))
                .withQueryParam("documentId", equalTo("specific_id"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "name": "projects/$projectId/databases/(default)/documents/users/specific_id",
                                "fields": {"username": {"stringValue": "testuser"}}
                            }
                        """.trimIndent())
                )
        )

        val docId = driver.createDocument("users", documentId = "specific_id", data = mapOf("username" to "testuser"))

        assertEquals("specific_id", docId)
    }

    // ============= Error Handling Tests =============

    @Test
    fun `executeDDL throws DatabaseException`() {
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
    fun `getTables throws DatabaseException`() {
        assertThrows<DatabaseException> {
            runBlocking { driver.getTables() }
        }
    }

    @Test
    fun `getColumns throws DatabaseException`() {
        assertThrows<DatabaseException> {
            runBlocking { driver.getColumns("users") }
        }
    }

    // ============= Unsupported Operations =============

    @Test
    fun `executeQuery with JOIN throws`() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            joins = listOf(
                JoinClause(
                    type = JoinType.INNER,
                    table = "posts",
                    on = WhereClause.Condition(
                        left = Expression.ColumnRef(column = "id"),
                        operator = ComparisonOperator.EQUALS,
                        right = Expression.ColumnRef(column = "user_id")
                    )
                )
            )
        )

        assertThrows<UnsupportedOperationException> {
            runBlocking { driver.executeQuery(query) }
        }
    }

    @Test
    fun `executeQuery with DISTINCT throws`() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            distinct = true
        )

        assertThrows<UnsupportedOperationException> {
            runBlocking { driver.executeQuery(query) }
        }
    }

    @Test
    fun `executeQuery with LIKE throws`() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Like(
                column = Expression.ColumnRef(column = "email"),
                pattern = Expression.Literal(SqlValue.StringValue("%@gmail.com"))
            )
        )

        assertThrows<UnsupportedOperationException> {
            runBlocking { driver.executeQuery(query) }
        }
    }
}
