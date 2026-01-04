package codes.yousef.aether.db.supabase

import codes.yousef.aether.db.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * End-to-end tests for SupabaseDriver against a real Supabase instance.
 * 
 * These tests are SKIPPED by default. To run them:
 * 1. Set environment variables:
 *    - SUPABASE_URL: Your Supabase project URL
 *    - SUPABASE_KEY: Your Supabase service role key (for full access)
 * 2. Create a test table in your Supabase database:
 *    ```sql
 *    CREATE TABLE IF NOT EXISTS aether_test_users (
 *        id SERIAL PRIMARY KEY,
 *        username VARCHAR(100) NOT NULL,
 *        email VARCHAR(255) NOT NULL,
 *        age INTEGER,
 *        active BOOLEAN DEFAULT true,
 *        created_at TIMESTAMP DEFAULT NOW()
 *    );
 *    ```
 * 3. Run with: ./gradlew :aether-db:test --tests "*.SupabaseE2ETest"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@EnabledIfEnvironmentVariable(named = "SUPABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SUPABASE_KEY", matches = ".+")
class SupabaseE2ETest {

    private lateinit var driver: SupabaseDriver
    private val tableName = "aether_test_users"
    private var insertedId: Int = 0

    @BeforeAll
    fun setup() {
        driver = SupabaseDriver.fromEnvironment()
        DatabaseDriverRegistry.initialize(driver)
        
        // Clean up any leftover test data
        runBlocking {
            try {
                val deleteQuery = DeleteQuery(
                    table = tableName,
                    where = WhereClause.Like(
                        column = Expression.ColumnRef(column = "username"),
                        pattern = Expression.Literal(SqlValue.StringValue("e2e_test_%"))
                    )
                )
                driver.executeUpdate(deleteQuery)
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    @AfterAll
    fun teardown() {
        runBlocking {
            // Clean up test data
            try {
                val deleteQuery = DeleteQuery(
                    table = tableName,
                    where = WhereClause.Like(
                        column = Expression.ColumnRef(column = "username"),
                        pattern = Expression.Literal(SqlValue.StringValue("e2e_test_%"))
                    )
                )
                driver.executeUpdate(deleteQuery)
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
            driver.close()
        }
    }

    @Test
    @Order(1)
    fun `insert creates new row`() = runBlocking {
        val query = InsertQuery(
            table = tableName,
            columns = listOf("username", "email", "age", "active"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("e2e_test_user1")),
                Expression.Literal(SqlValue.StringValue("e2e_test@example.com")),
                Expression.Literal(SqlValue.IntValue(30)),
                Expression.Literal(SqlValue.BooleanValue(true))
            ),
            returning = listOf("id")
        )
        
        val count = driver.executeUpdate(query)
        assertTrue(count >= 1, "Should insert at least 1 row")
    }

    @Test
    @Order(2)
    fun `select returns inserted row`() = runBlocking {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "username"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue("e2e_test_user1"))
            )
        )
        
        val rows = driver.executeQuery(query)
        
        assertEquals(1, rows.size, "Should find exactly 1 row")
        assertEquals("e2e_test_user1", rows[0].getString("username"))
        assertEquals("e2e_test@example.com", rows[0].getString("email"))
        assertEquals(30, rows[0].getInt("age"))
        assertEquals(true, rows[0].getBoolean("active"))
        
        // Save ID for later tests
        insertedId = rows[0].getInt("id") ?: 0
        assertTrue(insertedId > 0, "Should have a valid ID")
    }

    @Test
    @Order(3)
    fun `select with filter works`() = runBlocking {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = WhereClause.And(listOf(
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "active"),
                    operator = ComparisonOperator.EQUALS,
                    right = Expression.Literal(SqlValue.BooleanValue(true))
                ),
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "age"),
                    operator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
                    right = Expression.Literal(SqlValue.IntValue(18))
                )
            ))
        )
        
        val rows = driver.executeQuery(query)
        assertTrue(rows.isNotEmpty(), "Should find at least one active adult user")
        rows.forEach { row ->
            assertEquals(true, row.getBoolean("active"))
            assertTrue((row.getInt("age") ?: 0) >= 18)
        }
    }

    @Test
    @Order(4)
    fun `select with ordering works`() = runBlocking {
        // Insert another row for ordering test
        val insertQuery = InsertQuery(
            table = tableName,
            columns = listOf("username", "email", "age"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("e2e_test_user2")),
                Expression.Literal(SqlValue.StringValue("e2e_test2@example.com")),
                Expression.Literal(SqlValue.IntValue(25))
            )
        )
        driver.executeUpdate(insertQuery)
        
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = WhereClause.Like(
                column = Expression.ColumnRef(column = "username"),
                pattern = Expression.Literal(SqlValue.StringValue("e2e_test_%"))
            ),
            orderBy = listOf(
                OrderByClause(Expression.ColumnRef(column = "age"), OrderDirection.ASC)
            )
        )
        
        val rows = driver.executeQuery(query)
        assertTrue(rows.size >= 2, "Should have at least 2 test users")
        
        // Verify ordering
        for (i in 0 until rows.size - 1) {
            val currentAge = rows[i].getInt("age") ?: 0
            val nextAge = rows[i + 1].getInt("age") ?: 0
            assertTrue(currentAge <= nextAge, "Rows should be ordered by age ASC")
        }
    }

    @Test
    @Order(5)
    fun `select with pagination works`() = runBlocking {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = WhereClause.Like(
                column = Expression.ColumnRef(column = "username"),
                pattern = Expression.Literal(SqlValue.StringValue("e2e_test_%"))
            ),
            limit = 1,
            offset = 0
        )
        
        val rows = driver.executeQuery(query)
        assertEquals(1, rows.size, "Should return exactly 1 row with limit=1")
    }

    @Test
    @Order(6)
    fun `update modifies row`() = runBlocking {
        val query = UpdateQuery(
            table = tableName,
            assignments = mapOf(
                "age" to Expression.Literal(SqlValue.IntValue(31)),
                "email" to Expression.Literal(SqlValue.StringValue("updated@example.com"))
            ),
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "username"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue("e2e_test_user1"))
            )
        )
        
        val count = driver.executeUpdate(query)
        assertTrue(count >= 1, "Should update at least 1 row")
        
        // Verify update
        val selectQuery = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "username"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue("e2e_test_user1"))
            )
        )
        val rows = driver.executeQuery(selectQuery)
        assertEquals(31, rows[0].getInt("age"))
        assertEquals("updated@example.com", rows[0].getString("email"))
    }

    @Test
    @Order(7)
    fun `delete removes row`() = runBlocking {
        val query = DeleteQuery(
            table = tableName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "username"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue("e2e_test_user2"))
            )
        )
        
        val count = driver.executeUpdate(query)
        assertTrue(count >= 1, "Should delete at least 1 row")
        
        // Verify deletion
        val selectQuery = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "username"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue("e2e_test_user2"))
            )
        )
        val rows = driver.executeQuery(selectQuery)
        assertTrue(rows.isEmpty(), "Deleted row should not be found")
    }

    @Test
    @Order(8)
    fun `select with IN clause works`() = runBlocking {
        // Insert test data
        val insertQuery = InsertQuery(
            table = tableName,
            columns = listOf("username", "email", "age"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("e2e_test_user3")),
                Expression.Literal(SqlValue.StringValue("e2e_test3@example.com")),
                Expression.Literal(SqlValue.IntValue(35))
            )
        )
        driver.executeUpdate(insertQuery)
        
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = WhereClause.In(
                column = Expression.ColumnRef(column = "username"),
                values = listOf(
                    Expression.Literal(SqlValue.StringValue("e2e_test_user1")),
                    Expression.Literal(SqlValue.StringValue("e2e_test_user3"))
                )
            )
        )
        
        val rows = driver.executeQuery(query)
        assertEquals(2, rows.size, "Should find exactly 2 users")
        val usernames = rows.map { it.getString("username") }
        assertTrue(usernames.contains("e2e_test_user1"))
        assertTrue(usernames.contains("e2e_test_user3"))
    }

    @Test
    @Order(9)
    fun `select with null check works`() = runBlocking {
        // Insert user with null age
        val insertQuery = InsertQuery(
            table = tableName,
            columns = listOf("username", "email"),
            values = listOf(
                Expression.Literal(SqlValue.StringValue("e2e_test_user_null")),
                Expression.Literal(SqlValue.StringValue("null@example.com"))
            )
        )
        driver.executeUpdate(insertQuery)
        
        // Query for users with null age
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = WhereClause.And(listOf(
                WhereClause.IsNull(Expression.ColumnRef(column = "age")),
                WhereClause.Like(
                    column = Expression.ColumnRef(column = "username"),
                    pattern = Expression.Literal(SqlValue.StringValue("e2e_test_%"))
                )
            ))
        )
        
        val rows = driver.executeQuery(query)
        assertTrue(rows.isNotEmpty(), "Should find users with null age")
        rows.forEach { row ->
            assertNull(row.getInt("age"), "Age should be null")
        }
    }

    @Test
    @Order(100)
    fun `final cleanup`() = runBlocking {
        val query = DeleteQuery(
            table = tableName,
            where = WhereClause.Like(
                column = Expression.ColumnRef(column = "username"),
                pattern = Expression.Literal(SqlValue.StringValue("e2e_test_%"))
            )
        )
        driver.executeUpdate(query)
    }
}
