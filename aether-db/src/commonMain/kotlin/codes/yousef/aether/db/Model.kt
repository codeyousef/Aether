package codes.yousef.aether.db

import kotlin.reflect.KProperty

/**
 * Django-like Model base class.
 * Defines the table structure and provides ActiveRecord operations.
 */
abstract class Model<T : BaseEntity<T>> {
    /**
     * The database table name for this model.
     */
    abstract val tableName: String

    /**
     * List of all columns in this model.
     * Populated automatically by property delegates.
     */
    internal val columns = mutableListOf<ColumnProperty<T, *>>()

    /**
     * The primary key column.
     * Must be set by the model definition.
     */
    lateinit var primaryKeyColumn: ColumnProperty<T, *>

    /**
     * Generates a CREATE TABLE query for this model.
     */
    fun getCreateTableQuery(): CreateTableQuery {
        val columnDefs = columns.map { column ->
            ColumnDefinition(
                name = column.name,
                type = column.type.sqlType,
                nullable = column.nullable,
                primaryKey = column.isPrimaryKey,
                unique = column.unique,
                defaultValue = column.defaultValue?.let { toExpression(it) },
                autoIncrement = column.autoIncrement
            )
        }

        return CreateTableQuery(
            table = tableName,
            columns = columnDefs,
            ifNotExists = true
        )
    }

    /**
     * Queries all entities from this model.
     */
    suspend fun all(): List<T> {
        val selectQuery = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName
        )

        val driver = DatabaseDriverRegistry.driver
        val rows = driver.executeQuery(selectQuery)

        return rows.map { row -> rowToEntity(row) }
    }

    /**
     * Queries entities that match the given where clause.
     */
    suspend fun filter(where: WhereClause): List<T> {
        val selectQuery = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = where
        )

        val driver = DatabaseDriverRegistry.driver
        val rows = driver.executeQuery(selectQuery)

        return rows.map { row -> rowToEntity(row) }
    }

    /**
     * Gets a single entity by primary key.
     */
    suspend fun get(id: Any): T? {
        val selectQuery = SelectQuery(
            columns = listOf(Expression.Star),
            from = tableName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = primaryKeyColumn.name),
                operator = ComparisonOperator.EQUALS,
                right = toExpression(id)
            )
        )

        val driver = DatabaseDriverRegistry.driver
        val rows = driver.executeQuery(selectQuery)

        return if (rows.isNotEmpty()) rowToEntity(rows[0]) else null
    }

    /**
     * Creates the table in the database.
     */
    suspend fun createTable() {
        val createQuery = getCreateTableQuery()
        DatabaseDriverRegistry.driver.executeDDL(createQuery)
    }

    /**
     * Converts a database row to an entity instance.
     */
    private fun rowToEntity(row: Row): T {
        val entity = createInstance()

        for (column in columns) {
            val value = row.getValue(column.name)
            column.setValue(entity, value)

            if (column.isPrimaryKey) {
                entity.setPrimaryKey(value)
            }
        }

        return entity
    }

    /**
     * Creates a new instance of the entity.
     * Must be implemented by subclasses.
     */
    protected abstract fun createInstance(): T

    private fun toExpression(value: Any?): Expression {
        return when (value) {
            null -> Expression.Literal(SqlValue.NullValue)
            is String -> Expression.Literal(SqlValue.StringValue(value))
            is Int -> Expression.Literal(SqlValue.IntValue(value))
            is Long -> Expression.Literal(SqlValue.LongValue(value))
            is Double -> Expression.Literal(SqlValue.DoubleValue(value))
            is Boolean -> Expression.Literal(SqlValue.BooleanValue(value))
            else -> throw DatabaseException("Unsupported value type: ${value::class}")
        }
    }
}

// ============= Column Types =============

/**
 * Sealed class hierarchy for column types.
 */
sealed class ColumnType(val sqlType: String) {
    object Varchar : ColumnType("VARCHAR(255)")
    data class VarcharCustom(val length: Int) : ColumnType("VARCHAR($length)")
    object Text : ColumnType("TEXT")
    object Integer : ColumnType("INTEGER")
    object Long : ColumnType("BIGINT")
    object Double : ColumnType("DOUBLE PRECISION")
    object Boolean : ColumnType("BOOLEAN")
    object Serial : ColumnType("SERIAL")
    object BigSerial : ColumnType("BIGSERIAL")
}

// ============= Property Delegates =============

/**
 * Property delegate for database columns.
 */
class ColumnProperty<T : BaseEntity<T>, V>(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean = true,
    val unique: Boolean = false,
    val isPrimaryKey: Boolean = false,
    val autoIncrement: Boolean = false,
    val defaultValue: V? = null
) {
    private val values = mutableMapOf<T, V?>()

    operator fun getValue(thisRef: T, property: KProperty<*>): V? {
        return values[thisRef]
    }

    operator fun setValue(thisRef: T, property: KProperty<*>, value: V?) {
        if (!nullable && value == null) {
            throw DatabaseException("Cannot set null value for non-nullable column: $name")
        }
        values[thisRef] = value
    }

    fun getValue(entity: T): V? {
        return values[entity]
    }

    fun setValue(entity: T, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        values[entity] = value as V?
    }
}

// ============= DSL Functions =============

/**
 * Defines a VARCHAR column.
 */
fun <T : BaseEntity<T>> Model<T>.varchar(
    name: String? = null,
    length: Int = 255,
    nullable: Boolean = true,
    unique: Boolean = false,
    defaultValue: String? = null
): ColumnProperty<T, String> {
    val columnName = name ?: ""
    val column = ColumnProperty<T, String>(
        name = columnName,
        type = if (length == 255) ColumnType.Varchar else ColumnType.VarcharCustom(length),
        nullable = nullable,
        unique = unique,
        defaultValue = defaultValue
    )
    columns.add(column)
    return column
}

/**
 * Defines a TEXT column.
 */
fun <T : BaseEntity<T>> Model<T>.text(
    name: String? = null,
    nullable: Boolean = true,
    defaultValue: String? = null
): ColumnProperty<T, String> {
    val columnName = name ?: ""
    val column = ColumnProperty<T, String>(
        name = columnName,
        type = ColumnType.Text,
        nullable = nullable,
        defaultValue = defaultValue
    )
    columns.add(column)
    return column
}

/**
 * Defines an INTEGER column.
 */
fun <T : BaseEntity<T>> Model<T>.integer(
    name: String? = null,
    nullable: Boolean = true,
    unique: Boolean = false,
    defaultValue: Int? = null
): ColumnProperty<T, Int> {
    val columnName = name ?: ""
    val column = ColumnProperty<T, Int>(
        name = columnName,
        type = ColumnType.Integer,
        nullable = nullable,
        unique = unique,
        defaultValue = defaultValue
    )
    columns.add(column)
    return column
}

/**
 * Defines a BIGINT (Long) column.
 */
fun <T : BaseEntity<T>> Model<T>.long(
    name: String? = null,
    nullable: Boolean = true,
    unique: Boolean = false,
    defaultValue: Long? = null
): ColumnProperty<T, Long> {
    val columnName = name ?: ""
    val column = ColumnProperty<T, Long>(
        name = columnName,
        type = ColumnType.Long,
        nullable = nullable,
        unique = unique,
        defaultValue = defaultValue
    )
    columns.add(column)
    return column
}

/**
 * Defines a DOUBLE PRECISION column.
 */
fun <T : BaseEntity<T>> Model<T>.double(
    name: String? = null,
    nullable: Boolean = true,
    defaultValue: Double? = null
): ColumnProperty<T, Double> {
    val columnName = name ?: ""
    val column = ColumnProperty<T, Double>(
        name = columnName,
        type = ColumnType.Double,
        nullable = nullable,
        defaultValue = defaultValue
    )
    columns.add(column)
    return column
}

/**
 * Defines a BOOLEAN column.
 */
fun <T : BaseEntity<T>> Model<T>.boolean(
    name: String? = null,
    nullable: Boolean = true,
    defaultValue: Boolean? = null
): ColumnProperty<T, Boolean> {
    val columnName = name ?: ""
    val column = ColumnProperty<T, Boolean>(
        name = columnName,
        type = ColumnType.Boolean,
        nullable = nullable,
        defaultValue = defaultValue
    )
    columns.add(column)
    return column
}

/**
 * Defines an auto-incrementing primary key column (SERIAL).
 */
fun <T : BaseEntity<T>> Model<T>.serial(
    name: String = "id"
): ColumnProperty<T, Int> {
    val column = ColumnProperty<T, Int>(
        name = name,
        type = ColumnType.Serial,
        nullable = false,
        isPrimaryKey = true,
        autoIncrement = true
    )
    columns.add(column)
    primaryKeyColumn = column
    return column
}

/**
 * Defines an auto-incrementing primary key column (BIGSERIAL).
 */
fun <T : BaseEntity<T>> Model<T>.bigSerial(
    name: String = "id"
): ColumnProperty<T, Long> {
    val column = ColumnProperty<T, Long>(
        name = name,
        type = ColumnType.BigSerial,
        nullable = false,
        isPrimaryKey = true,
        autoIncrement = true
    )
    columns.add(column)
    primaryKeyColumn = column
    return column
}

/**
 * Marks a column as the primary key.
 */
fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.primaryKey(): ColumnProperty<T, V> {
    val newColumn = ColumnProperty<T, V>(
        name = this.name,
        type = this.type,
        nullable = false,
        unique = this.unique,
        isPrimaryKey = true,
        autoIncrement = this.autoIncrement,
        defaultValue = this.defaultValue
    )

    // Replace in columns list
    val model = (this as? ColumnProperty<T, V>)?.let {
        // This is a workaround - the model reference is needed but not available here
        // In practice, this will be called during model initialization
        newColumn
    }

    return newColumn
}
