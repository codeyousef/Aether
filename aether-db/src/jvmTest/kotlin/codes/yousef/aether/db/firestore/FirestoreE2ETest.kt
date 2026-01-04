package codes.yousef.aether.db.firestore

import codes.yousef.aether.db.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * End-to-end tests for FirestoreDriver against a real Firestore instance.
 * 
 * These tests are SKIPPED by default. To run them:
 * 1. Set environment variables:
 *    - FIREBASE_PROJECT_ID: Your Firebase project ID
 *    - FIREBASE_API_KEY: Your Firebase Web API key (for client auth)
 *    OR
 *    - GOOGLE_ACCESS_TOKEN: An OAuth2 access token (for service account auth)
 * 2. Ensure Firestore is enabled in your Firebase project
 * 3. Set up Firestore security rules to allow read/write (for testing only!):
 *    ```
 *    rules_version = '2';
 *    service cloud.firestore {
 *      match /databases/{database}/documents {
 *        match /aether_test_users/{document=**} {
 *          allow read, write: if true;
 *        }
 *      }
 *    }
 *    ```
 * 4. Run with: ./gradlew :aether-db:test --tests "*.FirestoreE2ETest"
 * 
 * NOTE: Firestore has different behavior than SQL databases:
 * - Documents have auto-generated IDs or user-specified IDs
 * - No schema - documents can have different fields
 * - No JOINs - use denormalized data
 * - Limited query operators compared to SQL
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@EnabledIfEnvironmentVariable(named = "FIREBASE_PROJECT_ID", matches = ".+")
class FirestoreE2ETest {

    private lateinit var driver: FirestoreDriver
    private val collectionName = "aether_test_users"
    private var insertedDocId: String = ""

    @BeforeAll
    fun setup() {
        driver = FirestoreDriver.fromEnvironment()
        DatabaseDriverRegistry.initialize(driver)
    }

    @AfterAll
    fun teardown() {
        runBlocking {
            // Note: Firestore doesn't support batch delete via REST easily
            // Tests should clean up their own documents
            driver.close()
        }
    }

    @Test
    @Order(1)
    fun `create document with auto-generated ID`() = runBlocking {
        val docId = driver.createDocument(
            collection = collectionName,
            data = mapOf(
                "username" to "e2e_firestore_user1",
                "email" to "firestore_test@example.com",
                "age" to 28,
                "active" to true,
                "tags" to listOf("test", "e2e")
            )
        )
        
        assertTrue(docId.isNotEmpty(), "Should return document ID")
        insertedDocId = docId
        println("Created document with ID: $docId")
    }

    @Test
    @Order(2)
    fun `get document by ID`() = runBlocking {
        Assumptions.assumeTrue(insertedDocId.isNotEmpty(), "Previous test must have created a document")
        
        val row = driver.getDocument(collectionName, insertedDocId)
        
        assertNotNull(row, "Should find the document")
        assertEquals("e2e_firestore_user1", row?.getString("username"))
        assertEquals("firestore_test@example.com", row?.getString("email"))
        assertEquals(28L, row?.getLong("age"))
        assertEquals(true, row?.getBoolean("active"))
    }

    @Test
    @Order(3)
    fun `create document with specific ID`() = runBlocking {
        val specificId = "e2e_test_doc_${System.currentTimeMillis()}"
        
        val docId = driver.createDocument(
            collection = collectionName,
            documentId = specificId,
            data = mapOf(
                "username" to "e2e_firestore_user2",
                "email" to "firestore_test2@example.com",
                "age" to 35
            )
        )
        
        assertEquals(specificId, docId, "Should use the specified document ID")
        
        // Verify we can retrieve it
        val row = driver.getDocument(collectionName, specificId)
        assertNotNull(row)
        assertEquals("e2e_firestore_user2", row?.getString("username"))
        
        // Clean up
        val deleteQuery = DeleteQuery(
            table = collectionName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue(specificId))
            )
        )
        driver.executeUpdate(deleteQuery)
    }

    @Test
    @Order(4)
    fun `select returns documents`() = runBlocking {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = collectionName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "username"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue("e2e_firestore_user1"))
            )
        )
        
        val rows = driver.executeQuery(query)
        
        assertTrue(rows.isNotEmpty(), "Should find at least one document")
        val user = rows.find { it.getString("username") == "e2e_firestore_user1" }
        assertNotNull(user, "Should find the test user")
        assertEquals("firestore_test@example.com", user?.getString("email"))
    }

    @Test
    @Order(5)
    fun `select with comparison operators`() = runBlocking {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = collectionName,
            where = WhereClause.And(listOf(
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "age"),
                    operator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
                    right = Expression.Literal(SqlValue.IntValue(18))
                ),
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "active"),
                    operator = ComparisonOperator.EQUALS,
                    right = Expression.Literal(SqlValue.BooleanValue(true))
                )
            ))
        )
        
        val rows = driver.executeQuery(query)
        rows.forEach { row ->
            assertTrue((row.getLong("age") ?: 0) >= 18)
            assertEquals(true, row.getBoolean("active"))
        }
    }

    @Test
    @Order(6)
    fun `select with ordering`() = runBlocking {
        // Create additional documents for ordering test
        driver.createDocument(
            collection = collectionName,
            data = mapOf(
                "username" to "e2e_firestore_user_young",
                "email" to "young@example.com",
                "age" to 20,
                "active" to true
            )
        )
        
        driver.createDocument(
            collection = collectionName,
            data = mapOf(
                "username" to "e2e_firestore_user_old",
                "email" to "old@example.com",
                "age" to 50,
                "active" to true
            )
        )
        
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = collectionName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "active"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.BooleanValue(true))
            ),
            orderBy = listOf(
                OrderByClause(Expression.ColumnRef(column = "age"), OrderDirection.DESC)
            ),
            limit = 10
        )
        
        val rows = driver.executeQuery(query)
        assertTrue(rows.size >= 2, "Should have multiple results")
        
        // Verify descending order
        for (i in 0 until rows.size - 1) {
            val currentAge = rows[i].getLong("age") ?: 0
            val nextAge = rows[i + 1].getLong("age") ?: 0
            assertTrue(currentAge >= nextAge, "Should be ordered by age DESC")
        }
    }

    @Test
    @Order(7)
    fun `update document`() = runBlocking {
        Assumptions.assumeTrue(insertedDocId.isNotEmpty(), "Previous test must have created a document")
        
        val query = UpdateQuery(
            table = collectionName,
            assignments = mapOf(
                "age" to Expression.Literal(SqlValue.IntValue(29)),
                "email" to Expression.Literal(SqlValue.StringValue("updated@example.com"))
            ),
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue(insertedDocId))
            )
        )
        
        val count = driver.executeUpdate(query)
        assertEquals(1, count)
        
        // Verify update
        val row = driver.getDocument(collectionName, insertedDocId)
        assertEquals(29L, row?.getLong("age"))
        assertEquals("updated@example.com", row?.getString("email"))
    }

    @Test
    @Order(8)
    fun `select with IN clause`() = runBlocking {
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = collectionName,
            where = WhereClause.In(
                column = Expression.ColumnRef(column = "username"),
                values = listOf(
                    Expression.Literal(SqlValue.StringValue("e2e_firestore_user1")),
                    Expression.Literal(SqlValue.StringValue("e2e_firestore_user_young"))
                )
            )
        )
        
        val rows = driver.executeQuery(query)
        assertTrue(rows.isNotEmpty(), "Should find matching documents")
        rows.forEach { row ->
            val username = row.getString("username")
            assertTrue(
                username == "e2e_firestore_user1" || username == "e2e_firestore_user_young",
                "Username should match IN clause"
            )
        }
    }

    @Test
    @Order(9)
    fun `get non-existent document returns null`() = runBlocking {
        val row = driver.getDocument(collectionName, "nonexistent_document_id_12345")
        assertNull(row, "Should return null for non-existent document")
    }

    @Test
    @Order(10)
    fun `delete document`() = runBlocking {
        Assumptions.assumeTrue(insertedDocId.isNotEmpty(), "Previous test must have created a document")
        
        val query = DeleteQuery(
            table = collectionName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue(insertedDocId))
            )
        )
        
        val count = driver.executeUpdate(query)
        assertEquals(1, count)
        
        // Verify deletion
        val row = driver.getDocument(collectionName, insertedDocId)
        assertNull(row, "Deleted document should not exist")
    }

    @Test
    @Order(100)
    fun `cleanup test documents`() = runBlocking {
        // Clean up remaining test documents
        val testUsernames = listOf(
            "e2e_firestore_user_young",
            "e2e_firestore_user_old"
        )
        
        for (username in testUsernames) {
            try {
                val query = SelectQuery(
                    columns = listOf(Expression.Star),
                    from = collectionName,
                    where = WhereClause.Condition(
                        left = Expression.ColumnRef(column = "username"),
                        operator = ComparisonOperator.EQUALS,
                        right = Expression.Literal(SqlValue.StringValue(username))
                    )
                )
                val rows = driver.executeQuery(query)
                for (row in rows) {
                    val docId = row.getString("id")
                    if (docId != null) {
                        val deleteQuery = DeleteQuery(
                            table = collectionName,
                            where = WhereClause.Condition(
                                left = Expression.ColumnRef(column = "id"),
                                operator = ComparisonOperator.EQUALS,
                                right = Expression.Literal(SqlValue.StringValue(docId))
                            )
                        )
                        driver.executeUpdate(deleteQuery)
                    }
                }
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
}
