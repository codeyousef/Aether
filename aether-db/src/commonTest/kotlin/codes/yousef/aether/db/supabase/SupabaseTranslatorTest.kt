package codes.yousef.aether.db.supabase

import codes.yousef.aether.db.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for SupabaseTranslator.
 * Tests QueryAST to PostgREST format translation.
 */
class SupabaseTranslatorTest {

    // ============= SELECT Tests =============

    @Test
    fun testSimpleSelect() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users"
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("users", result.table)
        assertEquals(SupabaseQuery.HttpMethod.GET, result.method)
        assertTrue(result.queryParams.isEmpty() || !result.queryParams.containsKey("select"))
    }

    @Test
    fun testSelectWithColumns() {
        val query = SelectQuery(
            columns = listOf(
                Expression.ColumnRef(column = "id"),
                Expression.ColumnRef(column = "username"),
                Expression.ColumnRef(column = "email")
            ),
            from = "users"
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("users", result.table)
        assertEquals("id,username,email", result.queryParams["select"])
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

        val result = SupabaseTranslator.translate(query)

        assertEquals("eq.true", result.queryParams["active"])
    }

    @Test
    fun testSelectWithGreaterThanFilter() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "age"),
                operator = ComparisonOperator.GREATER_THAN,
                right = Expression.Literal(SqlValue.IntValue(18))
            )
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("gt.18", result.queryParams["age"])
    }

    @Test
    fun testSelectWithLessThanOrEqualFilter() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "products",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "price"),
                operator = ComparisonOperator.LESS_THAN_OR_EQUAL,
                right = Expression.Literal(SqlValue.DoubleValue(99.99))
            )
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("lte.99.99", result.queryParams["price"])
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
                        left = Expression.ColumnRef(column = "age"),
                        operator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
                        right = Expression.Literal(SqlValue.IntValue(21))
                    )
                )
            )
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("eq.true", result.queryParams["active"])
        assertEquals("gte.21", result.queryParams["age"])
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
                        right = Expression.Literal(SqlValue.StringValue("moderator"))
                    )
                )
            )
        )

        val result = SupabaseTranslator.translate(query)

        assertTrue(result.queryParams["or"]?.contains("role.eq.admin") == true)
        assertTrue(result.queryParams["or"]?.contains("role.eq.moderator") == true)
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
                    Expression.Literal(SqlValue.StringValue("pending")),
                    Expression.Literal(SqlValue.StringValue("verified"))
                )
            )
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("in.(active,pending,verified)", result.queryParams["status"])
    }

    @Test
    fun testSelectWithIsNull() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.IsNull(Expression.ColumnRef(column = "deleted_at"))
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("is.null", result.queryParams["deleted_at"])
    }

    @Test
    fun testSelectWithIsNotNull() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.IsNotNull(Expression.ColumnRef(column = "email_verified_at"))
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("not.is.null", result.queryParams["email_verified_at"])
    }

    @Test
    fun testSelectWithLike() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            where = WhereClause.Like(
                column = Expression.ColumnRef(column = "email"),
                pattern = Expression.Literal(SqlValue.StringValue("%@gmail.com"))
            )
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("like.*@gmail.com", result.queryParams["email"])
    }

    @Test
    fun testSelectWithOrderBy() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            orderBy = listOf(
                OrderByClause(Expression.ColumnRef(column = "created_at"), OrderDirection.DESC),
                OrderByClause(Expression.ColumnRef(column = "username"), OrderDirection.ASC)
            )
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("created_at.desc,username.asc", result.queryParams["order"])
    }

    @Test
    fun testSelectWithLimitAndOffset() {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "users",
            limit = 10,
            offset = 20
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("10", result.queryParams["limit"])
        assertEquals("20", result.queryParams["offset"])
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

        val result = SupabaseTranslator.translate(query)

        assertEquals("users", result.table)
        assertEquals(SupabaseQuery.HttpMethod.POST, result.method)
        assertTrue(result.body?.contains("\"username\":\"johndoe\"") == true)
        assertTrue(result.body?.contains("\"email\":\"john@example.com\"") == true)
        assertTrue(result.body?.contains("\"age\":25") == true)
        assertEquals("return=representation", result.headers["Prefer"])
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

        val result = SupabaseTranslator.translate(query)

        assertTrue(result.body?.contains("\"bio\":null") == true)
    }

    // ============= UPDATE Tests =============

    @Test
    fun testUpdate() {
        val query = UpdateQuery(
            table = "users",
            assignments = mapOf(
                "username" to Expression.Literal(SqlValue.StringValue("newname")),
                "updated_at" to Expression.Literal(SqlValue.StringValue("2024-01-01T00:00:00Z"))
            ),
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.IntValue(123))
            )
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("users", result.table)
        assertEquals(SupabaseQuery.HttpMethod.PATCH, result.method)
        assertEquals("eq.123", result.queryParams["id"])
        assertTrue(result.body?.contains("\"username\":\"newname\"") == true)
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
            SupabaseTranslator.translate(query)
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
                right = Expression.Literal(SqlValue.IntValue(456))
            )
        )

        val result = SupabaseTranslator.translate(query)

        assertEquals("users", result.table)
        assertEquals(SupabaseQuery.HttpMethod.DELETE, result.method)
        assertEquals("eq.456", result.queryParams["id"])
    }

    @Test
    fun testDeleteWithoutWhereThrows() {
        val query = DeleteQuery(
            table = "users",
            where = null
        )

        assertFailsWith<UnsupportedOperationException> {
            SupabaseTranslator.translate(query)
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
            SupabaseTranslator.translate(query)
        }
    }

    @Test
    fun testRawQueryThrows() {
        val query = RawQuery("SELECT * FROM users")

        assertFailsWith<UnsupportedOperationException> {
            SupabaseTranslator.translate(query)
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
            SupabaseTranslator.translate(query)
        }
    }

    // ============= URL Building Tests =============

    @Test
    fun testBuildUrl() {
        val query = SupabaseQuery(
            table = "users",
            method = SupabaseQuery.HttpMethod.GET,
            queryParams = mapOf("active" to "eq.true", "limit" to "10")
        )

        val url = query.buildUrl("https://project.supabase.co/rest/v1")

        assertEquals("https://project.supabase.co/rest/v1/users?active=eq.true&limit=10", url)
    }

    @Test
    fun testBuildUrlNoParams() {
        val query = SupabaseQuery(
            table = "users",
            method = SupabaseQuery.HttpMethod.GET
        )

        val url = query.buildUrl("https://project.supabase.co/rest/v1")

        assertEquals("https://project.supabase.co/rest/v1/users", url)
    }
}
