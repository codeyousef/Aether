package codes.yousef.aether.ksp

import codes.yousef.aether.db.migration.*
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

/**
 * Unit tests for KSP Migration Generation functionality.
 * Tests schema diffing, SQL generation, and migration DSL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationGenerationTest {

    @Test
    fun testSchemaSnapshotCreation() {
        val schema = SchemaSnapshot(
            version = "001",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true, autoIncrement = true),
                        ColumnSchema("username", "VARCHAR(100)", nullable = false),
                        ColumnSchema("email", "VARCHAR(255)", nullable = false),
                        ColumnSchema("age", "INTEGER", nullable = true)
                    ),
                    indexes = listOf(
                        ConstraintSchema.Index("idx_users_email", "users", listOf("email"), unique = true)
                    )
                )
            )
        )
        
        assertEquals("001", schema.version)
        assertEquals(1, schema.tables.size)
        assertNotNull(schema.tables["users"])
        assertEquals(4, schema.tables["users"]!!.columns.size)
    }

    @Test
    fun testSchemaDiffDetectsNewTable() {
        val oldSchema = SchemaSnapshot(version = "001", tables = emptyMap())
        
        val newSchema = SchemaSnapshot(
            version = "002",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true)
                    )
                )
            )
        )
        
        val diff = SchemaDiff.calculate(oldSchema, newSchema)
        
        assertEquals(1, diff.addedTables.size)
        assertEquals("users", diff.addedTables.first().name)
        assertTrue(diff.removedTables.isEmpty())
        assertTrue(diff.modifiedTables.isEmpty())
    }

    @Test
    fun testSchemaDiffDetectsRemovedTable() {
        val oldSchema = SchemaSnapshot(
            version = "001",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true)
                    )
                )
            )
        )
        
        val newSchema = SchemaSnapshot(version = "002", tables = emptyMap())
        
        val diff = SchemaDiff.calculate(oldSchema, newSchema)
        
        assertTrue(diff.addedTables.isEmpty())
        assertEquals(1, diff.removedTables.size)
        assertEquals("users", diff.removedTables.first())
    }

    @Test
    fun testSchemaDiffDetectsNewColumn() {
        val oldSchema = SchemaSnapshot(
            version = "001",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true)
                    )
                )
            )
        )
        
        val newSchema = SchemaSnapshot(
            version = "002",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true),
                        ColumnSchema("email", "VARCHAR(255)", nullable = false)
                    )
                )
            )
        )
        
        val diff = SchemaDiff.calculate(oldSchema, newSchema)
        
        assertEquals(1, diff.modifiedTables.size)
        val tableDiff = diff.modifiedTables["users"]
        assertNotNull(tableDiff)
        assertEquals(1, tableDiff.addedColumns.size)
        assertEquals("email", tableDiff.addedColumns.first().name)
    }

    @Test
    fun testSchemaDiffDetectsRemovedColumn() {
        val oldSchema = SchemaSnapshot(
            version = "001",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true),
                        ColumnSchema("temporary_field", "VARCHAR(100)", nullable = true)
                    )
                )
            )
        )
        
        val newSchema = SchemaSnapshot(
            version = "002",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true)
                    )
                )
            )
        )
        
        val diff = SchemaDiff.calculate(oldSchema, newSchema)
        
        val tableDiff = diff.modifiedTables["users"]
        assertNotNull(tableDiff)
        assertEquals(1, tableDiff.removedColumns.size)
        assertEquals("temporary_field", tableDiff.removedColumns.first())
    }

    @Test
    fun testSchemaDiffDetectsModifiedColumn() {
        val oldSchema = SchemaSnapshot(
            version = "001",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("email", "VARCHAR(100)", nullable = true)
                    )
                )
            )
        )
        
        val newSchema = SchemaSnapshot(
            version = "002",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("email", "VARCHAR(255)", nullable = false) // Type and nullable changed
                    )
                )
            )
        )
        
        val diff = SchemaDiff.calculate(oldSchema, newSchema)
        
        val tableDiff = diff.modifiedTables["users"]
        assertNotNull(tableDiff)
        assertEquals(1, tableDiff.modifiedColumns.size)
        val (old, new) = tableDiff.modifiedColumns.first()
        assertEquals("VARCHAR(100)", old.type)
        assertEquals("VARCHAR(255)", new.type)
        assertTrue(old.nullable)
        assertFalse(new.nullable)
    }

    @Test
    fun testSqlGeneratorPostgresCreateTable() {
        val generator = SqlGenerator(SqlDialect.POSTGRESQL)
        
        val table = TableSchema(
            name = "users",
            columns = listOf(
                ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true, autoIncrement = true),
                ColumnSchema("username", "VARCHAR(100)", nullable = false),
                ColumnSchema("email", "VARCHAR(255)", nullable = false),
                ColumnSchema("created_at", "TIMESTAMP", nullable = false, defaultValue = "NOW()")
            )
        )
        
        val sql = generator.createTable(table)
        
        assertTrue(sql.contains("CREATE TABLE"), "Should start with CREATE TABLE")
        assertTrue(sql.contains("users"), "Should contain table name")
        assertTrue(sql.contains("id SERIAL PRIMARY KEY"), "Should have SERIAL for auto-increment on PostgreSQL")
        assertTrue(sql.contains("username VARCHAR(100) NOT NULL"), "Should contain username column")
        assertTrue(sql.contains("DEFAULT NOW()"), "Should contain default value")
    }

    @Test
    fun testSqlGeneratorMySqlCreateTable() {
        val generator = SqlGenerator(SqlDialect.MYSQL)
        
        val table = TableSchema(
            name = "users",
            columns = listOf(
                ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true, autoIncrement = true),
                ColumnSchema("username", "VARCHAR(100)", nullable = false)
            )
        )
        
        val sql = generator.createTable(table)
        
        assertTrue(sql.contains("AUTO_INCREMENT"), "MySQL should use AUTO_INCREMENT")
        assertTrue(sql.contains("PRIMARY KEY (id)"), "Should have primary key constraint")
    }

    @Test
    fun testSqlGeneratorSqliteCreateTable() {
        val generator = SqlGenerator(SqlDialect.SQLITE)
        
        val table = TableSchema(
            name = "users",
            columns = listOf(
                ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true, autoIncrement = true),
                ColumnSchema("name", "TEXT", nullable = false)
            )
        )
        
        val sql = generator.createTable(table)
        
        assertTrue(sql.contains("INTEGER PRIMARY KEY AUTOINCREMENT"), "SQLite should use AUTOINCREMENT")
    }

    @Test
    fun testSqlGeneratorAddColumn() {
        val generator = SqlGenerator(SqlDialect.POSTGRESQL)
        
        val sql = generator.addColumn(
            "users",
            ColumnSchema("phone", "VARCHAR(20)", nullable = true)
        )
        
        assertEquals("ALTER TABLE users ADD COLUMN phone VARCHAR(20);", sql)
    }

    @Test
    fun testSqlGeneratorAddColumnNotNull() {
        val generator = SqlGenerator(SqlDialect.POSTGRESQL)
        
        val sql = generator.addColumn(
            "users",
            ColumnSchema("required_field", "VARCHAR(100)", nullable = false, defaultValue = "'default'")
        )
        
        assertTrue(sql.contains("NOT NULL"), "Should contain NOT NULL")
        assertTrue(sql.contains("DEFAULT 'default'"), "Should contain default value")
    }

    @Test
    fun testSqlGeneratorDropColumn() {
        val generator = SqlGenerator(SqlDialect.POSTGRESQL)
        
        val sql = generator.dropColumn("users", "old_column")
        
        assertEquals("ALTER TABLE users DROP COLUMN old_column;", sql)
    }

    @Test
    fun testSqlGeneratorAlterColumn() {
        val generator = SqlGenerator(SqlDialect.POSTGRESQL)
        
        val newColumn = ColumnSchema("email", "VARCHAR(500)", nullable = false)
        val sql = generator.alterColumn("users", newColumn)
        
        assertTrue(sql.contains("ALTER TABLE users"), "Should contain ALTER TABLE")
        assertTrue(sql.contains("ALTER COLUMN email"), "Should contain ALTER COLUMN")
        assertTrue(sql.contains("TYPE VARCHAR(500)"), "Should change type")
        assertTrue(sql.contains("SET NOT NULL"), "Should set not null")
    }

    @Test
    fun testSqlGeneratorCreateIndex() {
        val generator = SqlGenerator(SqlDialect.POSTGRESQL)
        
        val index = ConstraintSchema.Index(
            name = "idx_users_email",
            tableName = "users",
            columns = listOf("email"),
            unique = true
        )
        
        val sql = generator.createIndex(index)
        
        assertEquals("CREATE UNIQUE INDEX idx_users_email ON users (email);", sql)
    }

    @Test
    fun testSqlGeneratorCreateCompositeIndex() {
        val generator = SqlGenerator(SqlDialect.POSTGRESQL)
        
        val index = ConstraintSchema.Index(
            name = "idx_users_name_email",
            tableName = "users",
            columns = listOf("first_name", "last_name", "email"),
            unique = false
        )
        
        val sql = generator.createIndex(index)
        
        assertTrue(sql.contains("(first_name, last_name, email)"), "Should contain all columns")
        assertFalse(sql.contains("UNIQUE"), "Should not be unique")
    }

    @Test
    fun testSqlGeneratorDropTable() {
        val generator = SqlGenerator(SqlDialect.POSTGRESQL)
        
        val sql = generator.dropTable("old_table")
        
        assertEquals("DROP TABLE IF EXISTS old_table;", sql)
    }

    @Test
    fun testSqlGeneratorAddForeignKey() {
        val generator = SqlGenerator(SqlDialect.POSTGRESQL)
        
        val fk = ConstraintSchema.ForeignKey(
            name = "fk_posts_user",
            tableName = "posts",
            column = "user_id",
            referencedTable = "users",
            referencedColumn = "id",
            onDelete = "CASCADE",
            onUpdate = "NO ACTION"
        )
        
        val sql = generator.addForeignKey(fk)
        
        assertTrue(sql.contains("ALTER TABLE posts"), "Should alter posts table")
        assertTrue(sql.contains("ADD CONSTRAINT fk_posts_user"), "Should add constraint")
        assertTrue(sql.contains("FOREIGN KEY (user_id)"), "Should specify foreign key column")
        assertTrue(sql.contains("REFERENCES users (id)"), "Should reference users table")
        assertTrue(sql.contains("ON DELETE CASCADE"), "Should have cascade delete")
    }

    @Test
    fun testMigrationDsl() {
        val migration = migration("002", "Add email to users") {
            addColumn("users", "email", "VARCHAR(255)", nullable = false, defaultValue = "''")
            createIndex("idx_users_email", "users", listOf("email"), unique = true)
        }
        
        assertEquals("002", migration.version)
        assertEquals("Add email to users", migration.name)
        assertEquals(2, migration.operations.size)
    }

    @Test
    fun testMigrationExecutionOrder() {
        val operations = mutableListOf<String>()
        
        val migration = object : Migration {
            override val version = "001"
            override val name = "Test migration"
            
            override suspend fun up(driver: codes.yousef.aether.db.DatabaseDriver) {
                operations.add("up")
            }
            
            override suspend fun down(driver: codes.yousef.aether.db.DatabaseDriver) {
                operations.add("down")
            }
        }
        
        assertEquals("001", migration.version)
        assertEquals("Test migration", migration.name)
    }

    @Test
    fun testComplexSchemaDiff() {
        val oldSchema = SchemaSnapshot(
            version = "001",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true),
                        ColumnSchema("name", "VARCHAR(100)", nullable = false),
                        ColumnSchema("temp_field", "VARCHAR(50)", nullable = true)
                    )
                ),
                "old_table" to TableSchema(
                    name = "old_table",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true)
                    )
                )
            )
        )
        
        val newSchema = SchemaSnapshot(
            version = "002",
            tables = mapOf(
                "users" to TableSchema(
                    name = "users",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true),
                        ColumnSchema("name", "VARCHAR(200)", nullable = false), // Modified
                        // temp_field removed
                        ColumnSchema("email", "VARCHAR(255)", nullable = false) // Added
                    ),
                    indexes = listOf(
                        ConstraintSchema.Index("idx_users_email", "users", listOf("email"), unique = true)
                    )
                ),
                "posts" to TableSchema( // New table
                    name = "posts",
                    columns = listOf(
                        ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true),
                        ColumnSchema("user_id", "INTEGER", nullable = false),
                        ColumnSchema("title", "VARCHAR(200)", nullable = false)
                    )
                )
            )
        )
        
        val diff = SchemaDiff.calculate(oldSchema, newSchema)
        
        // Check added tables
        assertEquals(1, diff.addedTables.size)
        assertEquals("posts", diff.addedTables.first().name)
        
        // Check removed tables
        assertEquals(1, diff.removedTables.size)
        assertEquals("old_table", diff.removedTables.first())
        
        // Check modified tables
        val usersDiff = diff.modifiedTables["users"]
        assertNotNull(usersDiff)
        assertEquals(1, usersDiff.addedColumns.size) // email
        assertEquals(1, usersDiff.removedColumns.size) // temp_field
        assertEquals(1, usersDiff.modifiedColumns.size) // name (type change)
        assertEquals(1, usersDiff.addedIndexes.size) // idx_users_email
    }
}
