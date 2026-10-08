package codes.yousef.aether.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationValidationTest {
    @Test
    fun `migration checksum is SHA-256 of exact reviewed UTF-8 bytes`() {
        val migration = SimpleMigration(1, "reviewed", "CREATE TABLE example (id BIGINT);\n")

        assertEquals(
            "d2fee209b7f19c38becd7c8c936a3eb09c0e810de24be9b9d929247de11f3021",
            migration.checksum
        )
    }

    @Test
    fun `duplicate registered versions fail before execution`() {
        val runner = MigrationRunner(UnsupportedDriver())
        runner.register(SimpleMigration(7, "first", "SELECT 1"))

        val failure = assertFailsWith<MigrationException> {
            runner.register(SimpleMigration(7, "second", "SELECT 2"))
        }

        assertEquals(MigrationFailure.DUPLICATE_VERSION, failure.failure)
        assertEquals(7, failure.version)
    }

    @Test
    fun `migration stream names fit PostgreSQL identifiers without truncation`() {
        MigrationRunner(UnsupportedDriver(), stream = "a".repeat(27))

        assertFailsWith<IllegalArgumentException> {
            MigrationRunner(UnsupportedDriver(), stream = "a".repeat(28))
        }
    }

    private class UnsupportedDriver : DatabaseDriver {
        override suspend fun executeQuery(query: QueryAST): List<Row> = error("not called")
        override suspend fun executeQueryRaw(sql: String): List<Row> = error("not called")
        override suspend fun executeUpdate(query: QueryAST): Int = error("not called")
        override suspend fun executeDDL(query: QueryAST) = error("not called")
        override suspend fun getTables(): List<String> = error("not called")
        override suspend fun getColumns(table: String): List<ColumnDefinition> = error("not called")
        override suspend fun execute(sql: String, params: List<SqlValue>): Int = error("not called")
        override suspend fun close() = Unit
    }
}
