package codes.yousef.aether.db

class MigrationGenerator(
    private val driver: DatabaseDriver
) {
    suspend fun generateMigration(
        name: String,
        version: Int,
        models: List<Model<*>>
    ): String {
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
            
            class $className : Migration {
                override val version = $version
                override val description = "$name"
                
                override fun up(): String {
                    return ""${'"'}
$upSql
                    ""${'"'}.trimIndent()
                }
                
                override fun down(): String? {
                    return null // TODO: Implement rollback
                }
            }
        """.trimIndent()
    }
}
