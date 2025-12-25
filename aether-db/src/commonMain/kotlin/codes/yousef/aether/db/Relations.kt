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
 * 
 * Usage:
 * object Users : Model<User>() { ... }
 * object Posts : Model<Post>() {
 *     val user = foreignKey(Users)
 * }
 * 
 * // In Users model or extension:
 * val Users.posts get() = hasMany(Posts, Posts.user)
 */
fun <T : BaseEntity<T>, R : BaseEntity<R>> Model<T>.hasMany(
    target: Model<R>,
    foreignKey: ForeignKeyRelation<R, T>
): HasMany<T, R> {
    return HasMany(this, target, foreignKey)
}

class OneToOneRelation<T : BaseEntity<T>, R : BaseEntity<R>>(
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
 * Defines a OneToOne relationship.
 * Similar to ForeignKey but implies uniqueness.
 */
@Suppress("UNCHECKED_CAST")
fun <T : BaseEntity<T>, R : BaseEntity<R>> Model<T>.oneToOne(
    target: Model<R>,
    name: String? = null,
    nullable: Boolean = true
): OneToOneRelation<T, R> {
    val defaultName = if (target.tableName.endsWith("s")) {
        target.tableName.dropLast(1)
    } else {
        target.tableName
    }
    
    val prefix = name ?: defaultName
    val columnName = "${prefix}_id"
    
    val targetPk = try {
        target.primaryKeyColumn
    } catch (e: Exception) {
        throw DatabaseException("Target model ${target::class.simpleName} must be initialized before defining a OneToOne to it.")
    }
    
    // OneToOne implies unique constraint on the foreign key column
    val column = when (targetPk.type) {
        ColumnType.Serial, ColumnType.Integer -> integer(columnName, nullable = nullable, unique = true)
        ColumnType.BigSerial, ColumnType.Long -> long(columnName, nullable = nullable, unique = true)
        ColumnType.Varchar, is ColumnType.VarcharCustom -> varchar(columnName, nullable = nullable, unique = true)
        else -> throw DatabaseException("Unsupported Primary Key type for OneToOne: ${targetPk.type}")
    }
    
    return OneToOneRelation(this, target, column)
}

class ReverseOneToOne<T : BaseEntity<T>, R : BaseEntity<R>>(
    val source: Model<T>,
    val target: Model<R>,
    val foreignKey: OneToOneRelation<R, T>
) {
    /**
     * Fetches the related entity.
     */
    suspend fun get(entity: T): R? {
        val id = source.primaryKeyColumn.getValue(entity) 
            ?: throw DatabaseException("Entity must be saved before accessing reverse relation")
            
        return target.objects.filter(
            WhereClause.Condition(
                left = Expression.ColumnRef(column = foreignKey.column.name),
                operator = ComparisonOperator.EQUALS,
                right = toExpression(id)
            )
        ).firstOrNull()
    }
}

/**
 * Defines a reverse OneToOne relationship.
 */
fun <T : BaseEntity<T>, R : BaseEntity<R>> Model<T>.reverseOneToOne(
    target: Model<R>,
    foreignKey: OneToOneRelation<R, T>
): ReverseOneToOne<T, R> {
    return ReverseOneToOne(this, target, foreignKey)
}

class ManyToManyRelation<T : BaseEntity<T>, R : BaseEntity<R>>(
    val source: Model<T>,
    val target: Model<R>,
    val throughTableName: String
) {
    /**
     * Returns a QuerySet for the related entities.
     */
    fun query(entity: T): QuerySet<R> {
        val id = source.primaryKeyColumn.getValue(entity) 
            ?: throw DatabaseException("Entity must be saved before accessing ManyToMany relation")
            
        val sourceCol = "${source.tableName}_id"
        val targetCol = "${target.tableName}_id"
        
        val subQuery = SelectQuery(
            columns = listOf(Expression.ColumnRef(column = targetCol)),
            from = throughTableName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = sourceCol),
                operator = ComparisonOperator.EQUALS,
                right = toExpression(id)
            )
        )
        
        return target.objects.filter(
            WhereClause.InSubQuery(
                column = Expression.ColumnRef(column = target.primaryKeyColumn.name),
                subQuery = subQuery
            )
        )
    }
    
    suspend fun add(entity: T, other: R) {
        val sourceId = source.primaryKeyColumn.getValue(entity)
        val targetId = target.primaryKeyColumn.getValue(other)
        
        if (sourceId == null || targetId == null) {
             throw DatabaseException("Both entities must be saved before adding to ManyToMany relation")
        }
        
        val sourceCol = "${source.tableName}_id" // simplistic naming
        val targetCol = "${target.tableName}_id"
        
        val sql = "INSERT INTO $throughTableName ($sourceCol, $targetCol) VALUES (?, ?)"
        DatabaseDriverRegistry.driver.execute(sql, listOf(toSqlValueInternal(sourceId), toSqlValueInternal(targetId)))
    }
    
    suspend fun remove(entity: T, other: R) {
        val sourceId = source.primaryKeyColumn.getValue(entity)
        val targetId = target.primaryKeyColumn.getValue(other)
        
        if (sourceId == null || targetId == null) return
        
        val sourceCol = "${source.tableName}_id"
        val targetCol = "${target.tableName}_id"
        
        val sql = "DELETE FROM $throughTableName WHERE $sourceCol = ? AND $targetCol = ?"
        DatabaseDriverRegistry.driver.execute(sql, listOf(toSqlValueInternal(sourceId), toSqlValueInternal(targetId)))
    }
    
    private fun toSqlValueInternal(value: Any): SqlValue {
        return when (value) {
            is Int -> SqlValue.IntValue(value)
            is Long -> SqlValue.LongValue(value)
            is String -> SqlValue.StringValue(value)
            is Boolean -> SqlValue.BooleanValue(value)
            is Double -> SqlValue.DoubleValue(value)
            else -> SqlValue.StringValue(value.toString())
        }
    }
}

/**
 * Defines a ManyToMany relationship.
 * Creates a join table automatically (conceptually, migration generator needs to handle it).
 */
fun <T : BaseEntity<T>, R : BaseEntity<R>> Model<T>.manyToMany(
    target: Model<R>,
    tableName: String? = null
): ManyToManyRelation<T, R> {
    // Sort table names to ensure consistent join table name regardless of which side defines it
    val names = listOf(this.tableName, target.tableName).sorted()
    val joinTableName = tableName ?: "${names[0]}_${names[1]}"
    
    val relation = ManyToManyRelation(this, target, joinTableName)
    this.manyToManyRelations.add(relation)
    return relation
}
