package codes.yousef.aether.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * KSP Symbol Processor for generating database migrations.
 * 
 * Scans for classes extending Model<T> and generates migration scripts
 * when the schema changes.
 */
class MigrationProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val schemaFile = options["aether.schema.file"] ?: ".aether/schema.json"
    private val migrationsDir = options["aether.migrations.dir"] ?: "migrations"
    private val dialect = SqlDialect.valueOf(
        options["aether.sql.dialect"]?.uppercase() ?: "POSTGRESQL"
    )

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("codes.yousef.aether.db.AetherModel")
            .filterIsInstance<KSClassDeclaration>()

        if (!symbols.iterator().hasNext()) {
            // Try to find Model subclasses directly
            return processModelClasses(resolver)
        }

        val notValid = symbols.filterNot { it.validate() }.toList()
        
        symbols.filter { it.validate() }.forEach { classDeclaration ->
            processModel(classDeclaration)
        }

        return notValid
    }

    private fun processModelClasses(resolver: Resolver): List<KSAnnotated> {
        // Find all object declarations that extend Model
        val modelClasses = resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .filter { isModelSubclass(it) }
            .toList()

        if (modelClasses.isEmpty()) {
            return emptyList()
        }

        val tables = mutableMapOf<String, TableSchema>()

        for (modelClass in modelClasses) {
            try {
                val tableSchema = extractTableSchema(modelClass)
                if (tableSchema != null) {
                    tables[tableSchema.name] = tableSchema
                    logger.info("Found model: ${modelClass.simpleName.asString()} -> ${tableSchema.name}")
                }
            } catch (e: Exception) {
                logger.warn("Failed to process model ${modelClass.simpleName.asString()}: ${e.message}")
            }
        }

        if (tables.isEmpty()) {
            return emptyList()
        }

        // Load previous schema
        val previousSchema = loadPreviousSchema()

        // Create new schema snapshot
        val newSchema = SchemaSnapshot(
            version = (previousSchema?.version ?: 0) + 1,
            tables = tables
        )

        // Compute diff
        val diff = SchemaDiff()
        val changes = diff.diff(previousSchema, newSchema)

        if (changes.isEmpty()) {
            logger.info("No schema changes detected")
            return emptyList()
        }

        // Generate migration
        generateMigration(newSchema, changes)

        // Save new schema
        saveSchema(newSchema)

        return emptyList()
    }

    private fun isModelSubclass(declaration: KSClassDeclaration): Boolean {
        val superTypes = declaration.superTypes.toList()
        
        for (superType in superTypes) {
            val resolvedType = superType.resolve()
            val typeName = resolvedType.declaration.qualifiedName?.asString() ?: continue
            
            if (typeName == "codes.yousef.aether.db.Model") {
                return true
            }
        }
        
        return false
    }

    private fun extractTableSchema(modelClass: KSClassDeclaration): TableSchema? {
        val tableName = extractTableName(modelClass) ?: return null
        val columns = extractColumns(modelClass)
        val constraints = extractConstraints(modelClass)

        return TableSchema(
            name = tableName,
            columns = columns,
            constraints = constraints
        )
    }

    private fun extractTableName(modelClass: KSClassDeclaration): String? {
        // Look for tableName property
        val tableNameProp = modelClass.getAllProperties()
            .find { it.simpleName.asString() == "tableName" }

        if (tableNameProp != null) {
            // Try to get the value from the property initializer
            val getter = tableNameProp.getter
            if (getter != null) {
                // For simple string literals
                return modelClass.simpleName.asString()
                    .replace(Regex("([a-z])([A-Z])"), "$1_$2")
                    .lowercase() + "s"
            }
        }

        // Default: convert class name to table name
        return modelClass.simpleName.asString()
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase() + "s"
    }

    private fun extractColumns(modelClass: KSClassDeclaration): List<ColumnSchema> {
        val columns = mutableListOf<ColumnSchema>()

        for (prop in modelClass.getAllProperties()) {
            val propName = prop.simpleName.asString()
            
            // Skip non-column properties
            if (propName in listOf("tableName", "columns", "primaryKeyColumn")) {
                continue
            }

            val typeName = prop.type.resolve().declaration.qualifiedName?.asString() ?: continue
            
            // Check if it's a ColumnProperty
            if (typeName.contains("ColumnProperty")) {
                val columnSchema = extractColumnFromProperty(prop)
                if (columnSchema != null) {
                    columns.add(columnSchema)
                }
            }
        }

        return columns
    }

    private fun extractColumnFromProperty(prop: KSPropertyDeclaration): ColumnSchema? {
        val propName = prop.simpleName.asString()
        val typeArgs = prop.type.resolve().arguments

        // Infer column type from type argument
        val valueType = typeArgs.lastOrNull()?.type?.resolve()
        val sqlType = mapKotlinTypeToSql(valueType)

        return ColumnSchema(
            name = propName,
            type = sqlType,
            nullable = valueType?.isMarkedNullable ?: true
        )
    }

    private fun mapKotlinTypeToSql(type: KSType?): String {
        val typeName = type?.declaration?.qualifiedName?.asString() ?: return "TEXT"
        
        return when (typeName) {
            "kotlin.String" -> "VARCHAR(255)"
            "kotlin.Int" -> "INTEGER"
            "kotlin.Long" -> "BIGINT"
            "kotlin.Double" -> "DOUBLE PRECISION"
            "kotlin.Float" -> "REAL"
            "kotlin.Boolean" -> "BOOLEAN"
            "kotlin.ByteArray" -> "BYTEA"
            else -> "TEXT"
        }
    }

    private fun extractConstraints(modelClass: KSClassDeclaration): List<ConstraintSchema> {
        // TODO: Extract constraints from annotations or property definitions
        return emptyList()
    }

    private fun processModel(classDeclaration: KSClassDeclaration) {
        // Process annotated model
        val tableSchema = extractTableSchema(classDeclaration)
        if (tableSchema != null) {
            logger.info("Processed annotated model: ${classDeclaration.simpleName.asString()}")
        }
    }

    private fun loadPreviousSchema(): SchemaSnapshot? {
        val file = File(schemaFile)
        if (!file.exists()) {
            return null
        }

        return try {
            SchemaSnapshotSerializer.deserialize(file.readText())
        } catch (e: Exception) {
            logger.warn("Failed to load previous schema: ${e.message}")
            null
        }
    }

    private fun saveSchema(schema: SchemaSnapshot) {
        val file = File(schemaFile)
        file.parentFile?.mkdirs()
        file.writeText(SchemaSnapshotSerializer.serialize(schema))
        logger.info("Schema saved to $schemaFile")
    }

    private fun generateMigration(schema: SchemaSnapshot, changes: List<SchemaChange>) {
        val generator = SqlGenerator(dialect)
        val sql = generator.generateMigration(changes)

        // Generate migration filename
        val timestamp = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
        val description = generateDescription(changes)
        val filename = "V${timestamp}__$description.sql"

        // Write migration file
        val dir = File(migrationsDir)
        dir.mkdirs()
        
        val migrationFile = File(dir, filename)
        migrationFile.writeText(buildMigrationContent(schema, sql))
        
        logger.info("Generated migration: $filename")
    }

    private fun generateDescription(changes: List<SchemaChange>): String {
        val descriptions = changes.take(3).map { change ->
            when (change) {
                is SchemaChange.CreateTable -> "create_${change.table.name}"
                is SchemaChange.DropTable -> "drop_${change.tableName}"
                is SchemaChange.AddColumn -> "add_${change.column.name}_to_${change.tableName}"
                is SchemaChange.DropColumn -> "drop_${change.columnName}_from_${change.tableName}"
                else -> "schema_update"
            }
        }

        val base = descriptions.firstOrNull() ?: "schema_update"
        return if (changes.size > 1) {
            "${base}_and_more"
        } else {
            base
        }
    }

    private fun buildMigrationContent(schema: SchemaSnapshot, sql: String): String {
        return """
            |-- Aether Migration
            |-- Version: ${schema.version}
            |-- Generated: ${Date()}
            |-- Dialect: $dialect
            |
            |-- Up Migration
            |$sql
            |
            |-- Down Migration (manual review recommended)
            |-- TODO: Add rollback SQL
        """.trimMargin()
    }
}

/**
 * Provider for the MigrationProcessor.
 */
class MigrationProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return MigrationProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            options = environment.options
        )
    }
}
