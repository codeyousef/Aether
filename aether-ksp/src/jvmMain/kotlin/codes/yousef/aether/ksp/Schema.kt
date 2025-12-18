package codes.yousef.aether.ksp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Represents a snapshot of the database schema.
 * Used to track changes between builds and generate migrations.
 */
@Serializable
data class SchemaSnapshot(
    val version: Int,
    val tables: Map<String, TableSchema>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Represents a table's schema.
 */
@Serializable
data class TableSchema(
    val name: String,
    val columns: List<ColumnSchema>,
    val constraints: List<ConstraintSchema> = emptyList()
)

/**
 * Represents a column's schema.
 */
@Serializable
data class ColumnSchema(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val primaryKey: Boolean = false,
    val unique: Boolean = false,
    val defaultValue: String? = null,
    val autoIncrement: Boolean = false
)

/**
 * Represents a table constraint.
 */
@Serializable
sealed class ConstraintSchema {
    @Serializable
    data class PrimaryKey(val columns: List<String>) : ConstraintSchema()

    @Serializable
    data class ForeignKey(
        val columns: List<String>,
        val referencedTable: String,
        val referencedColumns: List<String>,
        val onDelete: String = "NO ACTION",
        val onUpdate: String = "NO ACTION"
    ) : ConstraintSchema()

    @Serializable
    data class Unique(val columns: List<String>, val name: String? = null) : ConstraintSchema()

    @Serializable
    data class Check(val expression: String, val name: String? = null) : ConstraintSchema()

    @Serializable
    data class Index(val columns: List<String>, val name: String, val unique: Boolean = false) : ConstraintSchema()
}

/**
 * Represents a schema change (diff between two snapshots).
 */
sealed class SchemaChange {
    data class CreateTable(val table: TableSchema) : SchemaChange()
    data class DropTable(val tableName: String) : SchemaChange()
    data class RenameTable(val oldName: String, val newName: String) : SchemaChange()
    
    data class AddColumn(val tableName: String, val column: ColumnSchema) : SchemaChange()
    data class DropColumn(val tableName: String, val columnName: String) : SchemaChange()
    data class AlterColumn(val tableName: String, val oldColumn: ColumnSchema, val newColumn: ColumnSchema) : SchemaChange()
    data class RenameColumn(val tableName: String, val oldName: String, val newName: String) : SchemaChange()
    
    data class AddConstraint(val tableName: String, val constraint: ConstraintSchema) : SchemaChange()
    data class DropConstraint(val tableName: String, val constraintName: String) : SchemaChange()
    
    data class CreateIndex(val tableName: String, val index: ConstraintSchema.Index) : SchemaChange()
    data class DropIndex(val indexName: String) : SchemaChange()
}

/**
 * Computes the difference between two schema snapshots.
 */
class SchemaDiff {
    /**
     * Compute changes needed to go from oldSchema to newSchema.
     */
    fun diff(oldSchema: SchemaSnapshot?, newSchema: SchemaSnapshot): List<SchemaChange> {
        if (oldSchema == null) {
            // New schema - create all tables
            return newSchema.tables.values.map { SchemaChange.CreateTable(it) }
        }

        val changes = mutableListOf<SchemaChange>()
        
        val oldTables = oldSchema.tables
        val newTables = newSchema.tables

        // Find dropped tables
        for (oldTableName in oldTables.keys) {
            if (oldTableName !in newTables) {
                changes.add(SchemaChange.DropTable(oldTableName))
            }
        }

        // Find new and modified tables
        for ((tableName, newTable) in newTables) {
            val oldTable = oldTables[tableName]
            
            if (oldTable == null) {
                // New table
                changes.add(SchemaChange.CreateTable(newTable))
            } else {
                // Compare columns
                changes.addAll(diffColumns(tableName, oldTable, newTable))
                
                // Compare constraints
                changes.addAll(diffConstraints(tableName, oldTable, newTable))
            }
        }

        return changes
    }

    private fun diffColumns(tableName: String, oldTable: TableSchema, newTable: TableSchema): List<SchemaChange> {
        val changes = mutableListOf<SchemaChange>()
        
        val oldColumns = oldTable.columns.associateBy { it.name }
        val newColumns = newTable.columns.associateBy { it.name }

        // Find dropped columns
        for (oldColumnName in oldColumns.keys) {
            if (oldColumnName !in newColumns) {
                changes.add(SchemaChange.DropColumn(tableName, oldColumnName))
            }
        }

        // Find new and modified columns
        for ((columnName, newColumn) in newColumns) {
            val oldColumn = oldColumns[columnName]
            
            if (oldColumn == null) {
                changes.add(SchemaChange.AddColumn(tableName, newColumn))
            } else if (oldColumn != newColumn) {
                changes.add(SchemaChange.AlterColumn(tableName, oldColumn, newColumn))
            }
        }

        return changes
    }

    private fun diffConstraints(tableName: String, oldTable: TableSchema, newTable: TableSchema): List<SchemaChange> {
        val changes = mutableListOf<SchemaChange>()
        
        // For simplicity, compare serialized constraints
        val oldConstraints = oldTable.constraints.toSet()
        val newConstraints = newTable.constraints.toSet()

        // Find added constraints
        for (constraint in newConstraints) {
            if (constraint !in oldConstraints) {
                when (constraint) {
                    is ConstraintSchema.Index -> changes.add(SchemaChange.CreateIndex(tableName, constraint))
                    else -> changes.add(SchemaChange.AddConstraint(tableName, constraint))
                }
            }
        }

        // Find dropped constraints
        for (constraint in oldConstraints) {
            if (constraint !in newConstraints) {
                when (constraint) {
                    is ConstraintSchema.Index -> changes.add(SchemaChange.DropIndex(constraint.name))
                    else -> {
                        val name = getConstraintName(constraint)
                        if (name != null) {
                            changes.add(SchemaChange.DropConstraint(tableName, name))
                        }
                    }
                }
            }
        }

        return changes
    }

    private fun getConstraintName(constraint: ConstraintSchema): String? {
        return when (constraint) {
            is ConstraintSchema.Unique -> constraint.name
            is ConstraintSchema.Check -> constraint.name
            is ConstraintSchema.Index -> constraint.name
            else -> null
        }
    }
}

/**
 * Helper to serialize/deserialize schema snapshots.
 */
object SchemaSnapshotSerializer {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun serialize(snapshot: SchemaSnapshot): String {
        return json.encodeToString(snapshot)
    }

    fun deserialize(jsonString: String): SchemaSnapshot {
        return json.decodeFromString(jsonString)
    }
}
