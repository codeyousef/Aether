package codes.yousef.aether.ksp

/**
 * Generates SQL migration scripts from schema changes.
 */
class SqlGenerator(
    private val dialect: SqlDialect = SqlDialect.POSTGRESQL
) {
    /**
     * Generate migration SQL from a list of schema changes.
     */
    fun generateMigration(changes: List<SchemaChange>): String {
        return changes.joinToString("\n\n") { generateSql(it) }
    }

    /**
     * Generate SQL for a single schema change.
     */
    fun generateSql(change: SchemaChange): String {
        return when (change) {
            is SchemaChange.CreateTable -> generateCreateTable(change.table)
            is SchemaChange.DropTable -> generateDropTable(change.tableName)
            is SchemaChange.RenameTable -> generateRenameTable(change.oldName, change.newName)
            is SchemaChange.AddColumn -> generateAddColumn(change.tableName, change.column)
            is SchemaChange.DropColumn -> generateDropColumn(change.tableName, change.columnName)
            is SchemaChange.AlterColumn -> generateAlterColumn(change.tableName, change.oldColumn, change.newColumn)
            is SchemaChange.RenameColumn -> generateRenameColumn(change.tableName, change.oldName, change.newName)
            is SchemaChange.AddConstraint -> generateAddConstraint(change.tableName, change.constraint)
            is SchemaChange.DropConstraint -> generateDropConstraint(change.tableName, change.constraintName)
            is SchemaChange.CreateIndex -> generateCreateIndex(change.tableName, change.index)
            is SchemaChange.DropIndex -> generateDropIndex(change.indexName)
        }
    }

    private fun generateCreateTable(table: TableSchema): String {
        val columns = table.columns.joinToString(",\n    ") { generateColumnDef(it) }
        val constraints = table.constraints.mapNotNull { generateInlineConstraint(it) }
        
        val allDefs = if (constraints.isEmpty()) {
            columns
        } else {
            "$columns,\n    ${constraints.joinToString(",\n    ")}"
        }
        
        return """
            |CREATE TABLE IF NOT EXISTS ${quote(table.name)} (
            |    $allDefs
            |);
        """.trimMargin()
    }

    private fun generateColumnDef(column: ColumnSchema): String {
        val parts = mutableListOf<String>()
        
        parts.add(quote(column.name))
        parts.add(mapColumnType(column.type, column.autoIncrement))
        
        if (!column.nullable) {
            parts.add("NOT NULL")
        }
        
        if (column.primaryKey && !column.autoIncrement) {
            parts.add("PRIMARY KEY")
        }
        
        if (column.unique && !column.primaryKey) {
            parts.add("UNIQUE")
        }
        
        column.defaultValue?.let {
            parts.add("DEFAULT $it")
        }
        
        return parts.joinToString(" ")
    }

    private fun mapColumnType(type: String, autoIncrement: Boolean): String {
        return when (dialect) {
            SqlDialect.POSTGRESQL -> when {
                autoIncrement && type.contains("BIGINT", ignoreCase = true) -> "BIGSERIAL"
                autoIncrement && type.contains("INT", ignoreCase = true) -> "SERIAL"
                else -> type
            }
            SqlDialect.MYSQL -> when {
                autoIncrement && type.contains("INT", ignoreCase = true) -> "$type AUTO_INCREMENT"
                else -> type
            }
            SqlDialect.SQLITE -> when {
                autoIncrement && type.contains("INT", ignoreCase = true) -> "INTEGER PRIMARY KEY AUTOINCREMENT"
                else -> type
            }
        }
    }

    private fun generateInlineConstraint(constraint: ConstraintSchema): String? {
        return when (constraint) {
            is ConstraintSchema.PrimaryKey -> {
                "PRIMARY KEY (${constraint.columns.joinToString(", ") { quote(it) }})"
            }
            is ConstraintSchema.ForeignKey -> {
                val columns = constraint.columns.joinToString(", ") { quote(it) }
                val refColumns = constraint.referencedColumns.joinToString(", ") { quote(it) }
                "FOREIGN KEY ($columns) REFERENCES ${quote(constraint.referencedTable)} ($refColumns) " +
                    "ON DELETE ${constraint.onDelete} ON UPDATE ${constraint.onUpdate}"
            }
            is ConstraintSchema.Unique -> {
                val name = constraint.name?.let { "CONSTRAINT ${quote(it)} " } ?: ""
                "${name}UNIQUE (${constraint.columns.joinToString(", ") { quote(it) }})"
            }
            is ConstraintSchema.Check -> {
                val name = constraint.name?.let { "CONSTRAINT ${quote(it)} " } ?: ""
                "${name}CHECK (${constraint.expression})"
            }
            is ConstraintSchema.Index -> null // Indexes are created separately
        }
    }

    private fun generateDropTable(tableName: String): String {
        return "DROP TABLE IF EXISTS ${quote(tableName)};"
    }

    private fun generateRenameTable(oldName: String, newName: String): String {
        return when (dialect) {
            SqlDialect.POSTGRESQL, SqlDialect.SQLITE -> 
                "ALTER TABLE ${quote(oldName)} RENAME TO ${quote(newName)};"
            SqlDialect.MYSQL ->
                "RENAME TABLE ${quote(oldName)} TO ${quote(newName)};"
        }
    }

    private fun generateAddColumn(tableName: String, column: ColumnSchema): String {
        return "ALTER TABLE ${quote(tableName)} ADD COLUMN ${generateColumnDef(column)};"
    }

    private fun generateDropColumn(tableName: String, columnName: String): String {
        return "ALTER TABLE ${quote(tableName)} DROP COLUMN ${quote(columnName)};"
    }

    private fun generateAlterColumn(tableName: String, oldColumn: ColumnSchema, newColumn: ColumnSchema): String {
        val statements = mutableListOf<String>()
        
        when (dialect) {
            SqlDialect.POSTGRESQL -> {
                // Type change
                if (oldColumn.type != newColumn.type) {
                    statements.add(
                        "ALTER TABLE ${quote(tableName)} ALTER COLUMN ${quote(newColumn.name)} " +
                            "TYPE ${newColumn.type} USING ${quote(newColumn.name)}::${newColumn.type};"
                    )
                }
                
                // Nullable change
                if (oldColumn.nullable != newColumn.nullable) {
                    val action = if (newColumn.nullable) "DROP NOT NULL" else "SET NOT NULL"
                    statements.add(
                        "ALTER TABLE ${quote(tableName)} ALTER COLUMN ${quote(newColumn.name)} $action;"
                    )
                }
                
                // Default change
                if (oldColumn.defaultValue != newColumn.defaultValue) {
                    val action = if (newColumn.defaultValue != null) {
                        "SET DEFAULT ${newColumn.defaultValue}"
                    } else {
                        "DROP DEFAULT"
                    }
                    statements.add(
                        "ALTER TABLE ${quote(tableName)} ALTER COLUMN ${quote(newColumn.name)} $action;"
                    )
                }
            }
            SqlDialect.MYSQL -> {
                statements.add(
                    "ALTER TABLE ${quote(tableName)} MODIFY COLUMN ${generateColumnDef(newColumn)};"
                )
            }
            SqlDialect.SQLITE -> {
                // SQLite doesn't support ALTER COLUMN - need to recreate table
                statements.add("-- SQLite: Column modification requires table recreation")
            }
        }
        
        return statements.joinToString("\n")
    }

    private fun generateRenameColumn(tableName: String, oldName: String, newName: String): String {
        return when (dialect) {
            SqlDialect.POSTGRESQL ->
                "ALTER TABLE ${quote(tableName)} RENAME COLUMN ${quote(oldName)} TO ${quote(newName)};"
            SqlDialect.MYSQL ->
                "ALTER TABLE ${quote(tableName)} RENAME COLUMN ${quote(oldName)} TO ${quote(newName)};"
            SqlDialect.SQLITE ->
                "ALTER TABLE ${quote(tableName)} RENAME COLUMN ${quote(oldName)} TO ${quote(newName)};"
        }
    }

    private fun generateAddConstraint(tableName: String, constraint: ConstraintSchema): String {
        val constraintDef = when (constraint) {
            is ConstraintSchema.PrimaryKey -> {
                "PRIMARY KEY (${constraint.columns.joinToString(", ") { quote(it) }})"
            }
            is ConstraintSchema.ForeignKey -> {
                val columns = constraint.columns.joinToString(", ") { quote(it) }
                val refColumns = constraint.referencedColumns.joinToString(", ") { quote(it) }
                "FOREIGN KEY ($columns) REFERENCES ${quote(constraint.referencedTable)} ($refColumns) " +
                    "ON DELETE ${constraint.onDelete} ON UPDATE ${constraint.onUpdate}"
            }
            is ConstraintSchema.Unique -> {
                "UNIQUE (${constraint.columns.joinToString(", ") { quote(it) }})"
            }
            is ConstraintSchema.Check -> {
                "CHECK (${constraint.expression})"
            }
            is ConstraintSchema.Index -> return generateCreateIndex(tableName, constraint)
        }
        
        return "ALTER TABLE ${quote(tableName)} ADD $constraintDef;"
    }

    private fun generateDropConstraint(tableName: String, constraintName: String): String {
        return "ALTER TABLE ${quote(tableName)} DROP CONSTRAINT ${quote(constraintName)};"
    }

    private fun generateCreateIndex(tableName: String, index: ConstraintSchema.Index): String {
        val unique = if (index.unique) "UNIQUE " else ""
        val columns = index.columns.joinToString(", ") { quote(it) }
        return "CREATE ${unique}INDEX IF NOT EXISTS ${quote(index.name)} ON ${quote(tableName)} ($columns);"
    }

    private fun generateDropIndex(indexName: String): String {
        return when (dialect) {
            SqlDialect.POSTGRESQL -> "DROP INDEX IF EXISTS ${quote(indexName)};"
            SqlDialect.MYSQL -> "DROP INDEX ${quote(indexName)};"
            SqlDialect.SQLITE -> "DROP INDEX IF EXISTS ${quote(indexName)};"
        }
    }

    private fun quote(identifier: String): String {
        return when (dialect) {
            SqlDialect.POSTGRESQL, SqlDialect.SQLITE -> "\"$identifier\""
            SqlDialect.MYSQL -> "`$identifier`"
        }
    }
}

/**
 * Supported SQL dialects.
 */
enum class SqlDialect {
    POSTGRESQL,
    MYSQL,
    SQLITE
}
