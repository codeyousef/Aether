package codes.yousef.aether.db

class ForeignKeyRelation<T : BaseEntity<T>, R : BaseEntity<R>>(
    val source: Model<T>,
    val target: Model<R>,
    val column: ColumnProperty<T, *>
) {
    /**
     * Fetches the related entity.
     */
    suspend fun get(entity: T): R? {
        val id = column.getValue(entity) ?: return null
        return target.get(id)
    }
}

/**
 * Defines a ForeignKey relationship.
 * Automatically creates the backing ID column (e.g., user_id).
 */
@Suppress("UNCHECKED_CAST")
fun <T : BaseEntity<T>, R : BaseEntity<R>> Model<T>.foreignKey(
    target: Model<R>,
    name: String? = null,
    nullable: Boolean = true
): ForeignKeyRelation<T, R> {
    // Simple singularization: users -> user
    val defaultName = if (target.tableName.endsWith("s")) {
        target.tableName.dropLast(1)
    } else {
        target.tableName
    }
    
    val prefix = name ?: defaultName
    val columnName = "${prefix}_id"
    
    // We need to access the primary key of the target model.
    // However, if the target model is not fully initialized, primaryKeyColumn might not be set yet.
    // This is a circular dependency risk.
    // For now, we assume target is initialized.
    
    val targetPk = try {
        target.primaryKeyColumn
    } catch (e: Exception) {
        throw DatabaseException("Target model ${target::class.simpleName} must be initialized before defining a ForeignKey to it. Error: ${e.message}")
    }
    
    val column = when (targetPk.type) {
        ColumnType.Serial, ColumnType.Integer -> integer(columnName, nullable = nullable)
        ColumnType.BigSerial, ColumnType.Long -> long(columnName, nullable = nullable)
        ColumnType.Varchar, is ColumnType.VarcharCustom -> varchar(columnName, nullable = nullable)
        else -> throw DatabaseException("Unsupported Primary Key type for ForeignKey: ${targetPk.type}")
    }
    
    return ForeignKeyRelation(this, target, column)
}

class HasMany<T : BaseEntity<T>, R : BaseEntity<R>>(
    val source: Model<T>,
    val target: Model<R>,
    val foreignKey: ForeignKeyRelation<R, T>
) {
    /**
     * Returns a QuerySet for the related entities.
     */
    fun query(entity: T): QuerySet<R> {
        val id = source.primaryKeyColumn.getValue(entity) 
            ?: throw DatabaseException("Entity must be saved before accessing reverse relation")
            
        return target.objects.filter(
            WhereClause.Condition(
                left = Expression.ColumnRef(column = foreignKey.column.name),
                operator = ComparisonOperator.EQUALS,
                right = toExpression(id)
            )
        )
    }
}

/**
 * Defines a One-to-Many reverse relationship.
 */
fun <T : BaseEntity<T>, R : BaseEntity<R>> Model<T>.hasMany(
    target: Model<R>,
    foreignKey: ForeignKeyRelation<R, T>
): HasMany<T, R> {
    return HasMany(this, target, foreignKey)
}
