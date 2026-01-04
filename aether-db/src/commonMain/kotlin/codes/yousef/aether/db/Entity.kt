package codes.yousef.aether.db

import codes.yousef.aether.signals.EntitySignalPayload
import codes.yousef.aether.signals.Signals

/**
 * Entity interface for ActiveRecord pattern.
 * Provides save(), delete(), and refresh() operations.
 */
interface Entity<T : Entity<T>> {
    /**
     * Saves this entity to the database.
     * If the entity has a primary key set, performs an UPDATE.
     * Otherwise, performs an INSERT and sets the primary key.
     */
    suspend fun save()

    /**
     * Deletes this entity from the database.
     * Throws an exception if the entity has not been saved (no primary key).
     */
    suspend fun delete()

    /**
     * Refreshes this entity from the database.
     * Reloads all fields from the database based on the primary key.
     * Throws an exception if the entity has not been saved (no primary key).
     */
    suspend fun refresh()

    /**
     * Gets the primary key value of this entity.
     * Returns null if the entity has not been saved yet.
     */
    fun getPrimaryKey(): Any?

    /**
     * Sets the primary key value of this entity.
     * Used internally after INSERT operations.
     */
    fun setPrimaryKey(value: Any?)

    /**
     * Checks if this entity has been persisted to the database.
     */
    fun isPersisted(): Boolean = getPrimaryKey() != null
}

/**
 * Base implementation of Entity that works with Model.
 */
abstract class BaseEntity<T : BaseEntity<T>> : Entity<T> {
    private var primaryKeyValue: Any? = null

    override fun getPrimaryKey(): Any? = primaryKeyValue

    override fun setPrimaryKey(value: Any?) {
        primaryKeyValue = value
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun save() {
        val model = getModel()
        val pk = getPrimaryKey()
        val isNew = pk == null

        // Fire pre-save signal
        Signals.preSave.send(EntitySignalPayload(this, isNew))

        if (isNew) {
            // INSERT
            val columns = mutableListOf<String>()
            val values = mutableListOf<Expression>()

            for (column in model.columns) {
                if (column.autoIncrement) continue

                val value = column.getValue(this as T)
                columns.add(column.name)
                values.add(toExpression(value))
            }

            val insertQuery = InsertQuery(
                table = model.tableName,
                columns = columns,
                values = values,
                returning = listOf(model.primaryKeyColumn.name)
            )

            val driver = DatabaseDriverRegistry.driver
            val rows = driver.executeQuery(insertQuery)

            if (rows.isNotEmpty()) {
                val newPk = rows[0].getValue(model.primaryKeyColumn.name)
                setPrimaryKey(newPk)
            }
        } else {
            // UPDATE
            val assignments = mutableMapOf<String, Expression>()

            for (column in model.columns) {
                if (column.isPrimaryKey) continue

                val value = column.getValue(this as T)
                assignments[column.name] = toExpression(value)
            }

            val updateQuery = UpdateQuery(
                table = model.tableName,
                assignments = assignments,
                where = WhereClause.Condition(
                    left = Expression.ColumnRef(column = model.primaryKeyColumn.name),
                    operator = ComparisonOperator.EQUALS,
                    right = toExpression(pk)
                )
            )

            DatabaseDriverRegistry.driver.executeUpdate(updateQuery)
        }

        // Fire post-save signal
        Signals.postSave.send(EntitySignalPayload(this, isNew))
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun delete() {
        val pk = getPrimaryKey() ?: throw DatabaseException("Cannot delete entity without primary key")
        val model = getModel()

        // Fire pre-delete signal
        Signals.preDelete.send(EntitySignalPayload(this, false))

        val deleteQuery = DeleteQuery(
            table = model.tableName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = model.primaryKeyColumn.name),
                operator = ComparisonOperator.EQUALS,
                right = toExpression(pk)
            )
        )

        DatabaseDriverRegistry.driver.executeUpdate(deleteQuery)
        setPrimaryKey(null)

        // Fire post-delete signal
        Signals.postDelete.send(EntitySignalPayload(this, false))
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun refresh() {
        val pk = getPrimaryKey() ?: throw DatabaseException("Cannot refresh entity without primary key")
        val model = getModel()

        val selectQuery = SelectQuery(
            columns = listOf(Expression.Star),
            from = model.tableName,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = model.primaryKeyColumn.name),
                operator = ComparisonOperator.EQUALS,
                right = toExpression(pk)
            )
        )

        val driver = DatabaseDriverRegistry.driver
        val rows = driver.executeQuery(selectQuery)

        if (rows.isEmpty()) {
            throw DatabaseException("Entity not found in database")
        }

        val row = rows[0]
        for (column in model.columns) {
            val value = row.getValue(column.name)
            column.setValue(this as T, value)
        }
    }

    /**
     * Gets the Model instance associated with this entity.
     * Must be implemented by subclasses.
     */
    protected abstract fun getModel(): Model<T>

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
