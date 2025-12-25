package codes.yousef.aether.db

class SchemaComparator(
    private val driver: DatabaseDriver
) {
    /**
     * Compares the given models against the database schema and generates migration SQL.
     */
    suspend fun generateDiff(models: List<Model<*>>): List<String> {
        val statements = mutableListOf<String>()
        val existingTables = driver.getTables().toSet()

        for (model in models) {
            if (model.tableName !in existingTables) {
                // Table does not exist, create it
                val createQuery = model.getCreateTableQuery()
                val translated = SqlTranslator.translate(createQuery)
                statements.add(translated.sql)
            } else {
                // Table exists, check columns
                val existingColumns = driver.getColumns(model.tableName).associateBy { it.name }
                
                for (column in model.columns) {
                    if (column.name !in existingColumns) {
                        // Column missing, add it
                        // ALTER TABLE table ADD COLUMN name type ...
                        val sql = buildAddColumnSql(model.tableName, column)
                        statements.add(sql)
                    }
                }
            }
        }
        
        return statements
    }
    
    private fun buildAddColumnSql(tableName: String, column: ColumnProperty<*, *>): String {
        val sb = StringBuilder()
        sb.append("ALTER TABLE $tableName ADD COLUMN ${column.name} ${column.type.sqlType}")
        if (!column.nullable) {
            sb.append(" NOT NULL")
        }
        if (column.defaultValue != null) {
            // This is tricky without a proper expression serializer
            // sb.append(" DEFAULT ...") 
        }
        return sb.toString()
    }
}
