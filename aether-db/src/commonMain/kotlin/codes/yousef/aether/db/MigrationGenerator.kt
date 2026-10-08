package codes.yousef.aether.db

class MigrationGenerator(
    private val driver: DatabaseDriver
) {
    suspend fun generateMigration(
        name: String,
        version: Int,
        models: List<Model<*>>
    ): String {
        require(MIGRATION_NAME.matches(name)) {
            "Migration name must contain only letters, digits, and underscores"
        }
        require(version > 0) { "Migration version must be positive" }
        val comparator = SchemaComparator(driver)
        val statements = comparator.generateDiff(models)
        
        if (statements.isEmpty()) {
            return ""
        }
        
        val className = "Migration_${version}_$name"
        
        val upSql = statements.joinToString("\n") { "$it;" }
        
        return """
            package codes.yousef.aether.migrations
            
            import codes.yousef.aether.db.Migration
            import codes.yousef.aether.db.MigrationSource
            
            class $className : Migration {
                override val version = ${version}L
                override val description = "$name"
                override val source = MigrationSource.GENERATED_CANDIDATE
                
                override fun up(): String {
                    return ""${'"'}
$upSql
                    ""${'"'}.trimIndent()
                }
                
                override fun down(): String? = null
            }
        """.trimIndent()
    }

    private companion object {
        val MIGRATION_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
    }
}
