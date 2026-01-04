package codes.yousef.aether.db.firestore

import codes.yousef.aether.db.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for FirestoreTranslator.
 * Tests QueryAST to Firestore REST API format translation.
 */
class FirestoreTranslatorTest {

    // ============= SELECT Tests =============

    @Test
    fun testSimpleSelect() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users"
        )

        val result = FirestoreTranslator.translate(query)

        assertEquals("users", result.collection)
        assertEquals(FirestoreQuery.HttpMethod.POST, result.method)
        assertTrue(result.body?.contains("structuredQuery") == true)
        assertTrue(result.body?.contains("\"collectionId\":\"users\"") == true)
    }

    @Test
    fun testSelectWithColumns() {
        val query = SelectQuery(
            columns = listOf(
                Expression.ColumnRef(column = "id"),
                Expression.ColumnRef(column = "username")
            ),
            from = "users"
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"fieldPath\":\"id\"") == true)
        assertTrue(result.body?.contains("\"fieldPath\":\"username\"") == true)
    }

    @Test
    fun testSelectWithEqualsFilter() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "active"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.BooleanValue(true))
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"fieldFilter\"") == true)
        assertTrue(result.body?.contains("\"op\":\"EQUAL\"") == true)
        assertTrue(result.body?.contains("\"booleanValue\":true") == true)
    }

    @Test
    fun testSelectWithNotEqualsFilter() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "status"),
                operator = ComparisonOperator.NOT_EQUALS,
                right = Expression.Literal(SqlValue.StringValue("deleted"))
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"op\":\"NOT_EQUAL\"") == true)
        assertTrue(result.body?.contains("\"stringValue\":\"deleted\"") == true)
    }

    @Test
    fun testSelectWithGreaterThanFilter() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "products",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "price"),
                operator = ComparisonOperator.GREATER_THAN,
                right = Expression.Literal(SqlValue.DoubleValue(100.0))
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"op\":\"GREATER_THAN\"") == true)
        assertTrue(result.body?.contains("\"doubleValue\":100.0") == true)
    }

    @Test
    fun testSelectWithLessThanOrEqualFilter() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "age"),
                operator = ComparisonOperator.LESS_THAN_OR_EQUAL,
                right = Expression.Literal(SqlValue.IntValue(65))
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"op\":\"LESS_THAN_OR_EQUAL\"") == true)
        assertTrue(result.body?.contains("\"integerValue\":\"65\"") == true)
    }

    @Test
    fun testSelectWithAndCondition() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.And(
                listOf(
                    WhereClause.Condition(
                        left = Expression.ColumnRef(column = "active"),
                        operator = ComparisonOperator.EQUALS,
                        right = Expression.Literal(SqlValue.BooleanValue(true))
                    ),
                    WhereClause.Condition(
                        left = Expression.ColumnRef(column = "verified"),
                        operator = ComparisonOperator.EQUALS,
                        right = Expression.Literal(SqlValue.BooleanValue(true))
                    )
                )
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"compositeFilter\"") == true)
        assertTrue(result.body?.contains("\"op\":\"AND\"") == true)
    }

    @Test
    fun testSelectWithOrCondition() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Or(
                listOf(
                    WhereClause.Condition(
                        left = Expression.ColumnRef(column = "role"),
                        operator = ComparisonOperator.EQUALS,
                        right = Expression.Literal(SqlValue.StringValue("admin"))
                    ),
                    WhereClause.Condition(
                        left = Expression.ColumnRef(column = "role"),
                        operator = ComparisonOperator.EQUALS,
                        right = Expression.Literal(SqlValue.StringValue("superadmin"))
                    )
                )
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"compositeFilter\"") == true)
        assertTrue(result.body?.contains("\"op\":\"OR\"") == true)
    }

    @Test
    fun testSelectWithInClause() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.In(
                column = Expression.ColumnRef(column = "status"),
                values = listOf(
                    Expression.Literal(SqlValue.StringValue("active")),
                    Expression.Literal(SqlValue.StringValue("pending"))
                )
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"op\":\"IN\"") == true)
        assertTrue(result.body?.contains("\"arrayValue\"") == true)
    }

    @Test
    fun testSelectWithIsNull() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.IsNull(Expression.ColumnRef(column = "deleted_at"))
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"unaryFilter\"") == true)
        assertTrue(result.body?.contains("\"op\":\"IS_NULL\"") == true)
    }

    @Test
    fun testSelectWithIsNotNull() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.IsNotNull(Expression.ColumnRef(column = "email"))
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"unaryFilter\"") == true)
        assertTrue(result.body?.contains("\"op\":\"IS_NOT_NULL\"") == true)
    }

    @Test
    fun testSelectWithBetween() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "products",
            where = WhereClause.Between(
                column = Expression.ColumnRef(column = "price"),
                lower = Expression.Literal(SqlValue.DoubleValue(10.0)),
                upper = Expression.Literal(SqlValue.DoubleValue(100.0))
            )
        )

        val result = FirestoreTranslator.translate(query)

        // BETWEEN is translated to AND with >= and <=
        assertTrue(result.body?.contains("\"compositeFilter\"") == true)
        assertTrue(result.body?.contains("\"op\":\"AND\"") == true)
        assertTrue(result.body?.contains("\"GREATER_THAN_OR_EQUAL\"") == true)
        assertTrue(result.body?.contains("\"LESS_THAN_OR_EQUAL\"") == true)
    }

    @Test
    fun testSelectWithOrderBy() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            orderBy = listOf(
                OrderByClause(Expression.ColumnRef(column = "created_at"), OrderDirection.DESC)
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"orderBy\"") == true)
        assertTrue(result.body?.contains("\"direction\":\"DESCENDING\"") == true)
        assertTrue(result.body?.contains("\"fieldPath\":\"created_at\"") == true)
    }

    @Test
    fun testSelectWithLimitAndOffset() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            limit = 10,
            offset = 5
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"limit\":10") == true)
        assertTrue(result.body?.contains("\"offset\":5") == true)
    }

    // ============= INSERT Tests =============

    @Test
    fun testInsert() {
        val query = InsertQuery(
            table = "users",
            columns = listOf("username", "email", "age"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("johndoe")),
                Expression.Literal(SqlValue.StringValue("john@example.com")),
                Expression.Literal(SqlValue.IntValue(25))
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertEquals("users", result.collection)
        assertEquals(FirestoreQuery.HttpMethod.POST, result.method)
        assertTrue(result.body?.contains("\"fields\"") == true)
        assertTrue(result.body?.contains("\"username\"") == true)
        assertTrue(result.body?.contains("\"stringValue\":\"johndoe\"") == true)
        assertTrue(result.body?.contains("\"integerValue\":\"25\"") == true)
    }

    @Test
    fun testInsertWithNullValue() {
        val query = InsertQuery(
            table = "users",
            columns = listOf("username", "bio"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("johndoe")),
                Expression.Literal(SqlValue.NullValue)
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"nullValue\"") == true)
    }

    @Test
    fun testInsertWithBooleanValue() {
        val query = InsertQuery(
            table = "users",
            columns = listOf("username", "active"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("johndoe")),
                Expression.Literal(SqlValue.BooleanValue(true))
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertTrue(result.body?.contains("\"booleanValue\":true") == true)
    }

    // ============= UPDATE Tests =============

    @Test
    fun testUpdate() {
        val query = UpdateQuery(
            table = "users",
            assignments = mapOf(
                "username" to Expression.Literal(SqlValue.StringValue("newname"))
            ),
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue("doc123"))
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertEquals("users", result.collection)
        assertEquals(FirestoreQuery.HttpMethod.PATCH, result.method)
        assertEquals("doc123", result.documentId)
        assertTrue(result.body?.contains("\"username\"") == true)
    }

    @Test
    fun testUpdateWithoutIdThrows() {
        val query = UpdateQuery(
            table = "users",
            assignments = mapOf(
                "username" to Expression.Literal(SqlValue.StringValue("newname"))
            ),
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "active"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.BooleanValue(true))
            )
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    @Test
    fun testUpdateWithoutWhereThrows() {
        val query = UpdateQuery(
            table = "users",
            assignments = mapOf(
                "active" to Expression.Literal(SqlValue.BooleanValue(false))
            ),
            where = null
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    // ============= DELETE Tests =============

    @Test
    fun testDelete() {
        val query = DeleteQuery(
            table = "users",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue("doc456"))
            )
        )

        val result = FirestoreTranslator.translate(query)

        assertEquals("users", result.collection)
        assertEquals(FirestoreQuery.HttpMethod.DELETE, result.method)
        assertEquals("doc456", result.documentId)
    }

    @Test
    fun testDeleteWithoutIdThrows() {
        val query = DeleteQuery(
            table = "users",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "active"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.BooleanValue(false))
            )
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    @Test
    fun testDeleteWithoutWhereThrows() {
        val query = DeleteQuery(
            table = "users",
            where = null
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    // ============= Unsupported Operations =============

    @Test
    fun testCreateTableThrows() {
        val query = CreateTableQuery(
            table = "users",
            columns = listOf(
                ColumnDefinition("id", "INTEGER", primaryKey = true)
            )
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    @Test
    fun testRawQueryThrows() {
        val query = RawQuery("SELECT * FROM users")

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    @Test
    fun testJoinThrows() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            joins = listOf(
                JoinClause(
                    type = JoinType.INNER,
                    table = "posts",
                    on = WhereClause.Condition(
                        left = Expression.ColumnRef(table = "users", column = "id"),
                        operator = ComparisonOperator.EQUALS,
                        right = Expression.ColumnRef(table = "posts", column = "user_id")
                    )
                )
            )
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    @Test
    fun testDistinctThrows() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            distinct = true
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    @Test
    fun testLikeThrows() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Like(
                column = Expression.ColumnRef(column = "email"),
                pattern = Expression.Literal(SqlValue.StringValue("%@gmail.com"))
            )
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    @Test
    fun testNotOperatorThrows() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Not(
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "active"),
                    operator = ComparisonOperator.EQUALS,
                    right = Expression.Literal(SqlValue.BooleanValue(true))
                )
            )
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    @Test
    fun testSubqueryThrows() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.InSubQuery(
                column = Expression.ColumnRef(column = "department_id"),
                subQuery = SelectQuery(
                    columns = listOf(Expression.ColumnRef(column = "id")),
                    from = "departments",
                    where = WhereClause.Condition(
                        left = Expression.ColumnRef(column = "active"),
                        operator = ComparisonOperator.EQUALS,
                        right = Expression.Literal(SqlValue.BooleanValue(true))
                    )
                )
            )
        )

        assertFailsWith<UnsupportedOperationException> {
            FirestoreTranslator.translate(query)
        }
    }

    // ============= URL Building Tests =============

    @Test
    fun testBuildUrlForQuery() {
        val query = FirestoreQuery(
            collection = "users",
            method = FirestoreQuery.HttpMethod.POST,
            body = """{"structuredQuery":{}}"""
        )

        val url = query.buildUrl("test-project")

        assertEquals(
            "https://firestore.googleapis.com/v1/projects/test-project/databases/(default)/documents:runQuery",
            url
        )
    }

    @Test
    fun testBuildUrlForDocument() {
        val query = FirestoreQuery(
            collection = "users",
            method = FirestoreQuery.HttpMethod.GET,
            body = null,
            documentId = "doc123"
        )

        val url = query.buildUrl("test-project")

        assertEquals(
            "https://firestore.googleapis.com/v1/projects/test-project/databases/(default)/documents/users/doc123",
            url
        )
    }

    @Test
    fun testBuildUrlForCollection() {
        val query = FirestoreQuery(
            collection = "users",
            method = FirestoreQuery.HttpMethod.POST,
            body = """{"fields":{}}"""
        )

        val url = query.buildUrl("test-project")

        assertEquals(
            "https://firestore.googleapis.com/v1/projects/test-project/databases/(default)/documents/users",
            url
        )
    }

    @Test
    fun testBuildUrlWithCustomDatabase() {
        val query = FirestoreQuery(
            collection = "users",
            method = FirestoreQuery.HttpMethod.GET,
            body = null,
            documentId = "doc123"
        )

        val url = query.buildUrl("test-project", "my-database")

        assertEquals(
            "https://firestore.googleapis.com/v1/projects/test-project/databases/my-database/documents/users/doc123",
            url
        )
    }
}
