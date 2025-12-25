package codes.yousef.aether.db

/**
 * Represents a lazy database query.
 * Allows chaining filters and other operations before execution.
 */
class QuerySet<T : BaseEntity<T>>(
    private val model: Model<T>,
    private val where: WhereClause? = null,
    private val orderBy: List<OrderByClause> = emptyList(),
    private val limit: Int? = null,
    private val offset: Int? = null,
    private val distinct: Boolean = false
) {

    /**
     * Returns a new QuerySet containing objects that match the given lookup parameters.
     */
    fun filter(condition: WhereClause): QuerySet<T> {
        val newWhere = if (where == null) {
            condition
        } else {
            WhereClause.And(listOf(where, condition))
        }
        return copy(where = newWhere)
    }

    /**
     * Returns a new QuerySet containing objects that do not match the given lookup parameters.
     */
    fun exclude(condition: WhereClause): QuerySet<T> {
        val newCondition = WhereClause.Not(condition)
        val newWhere = if (where == null) {
            newCondition
        } else {
            WhereClause.And(listOf(where, newCondition))
        }
        return copy(where = newWhere)
    }

    /**
     * Returns a new QuerySet with the given order.
     */
    fun orderBy(vararg clauses: OrderByClause): QuerySet<T> {
        return copy(orderBy = orderBy + clauses)
    }

    /**
     * Returns a new QuerySet with the given limit.
     */
    fun limit(limit: Int): QuerySet<T> {
        return copy(limit = limit)
    }

    /**
     * Returns a new QuerySet with the given offset.
     */
    fun offset(offset: Int): QuerySet<T> {
        return copy(offset = offset)
    }

    /**
     * Returns a new QuerySet with distinct results.
     */
    fun distinct(): QuerySet<T> {
        return copy(distinct = true)
    }

    /**
     * Executes the query and returns a list of results.
     */
    suspend fun toList(): List<T> {
        val selectQuery = SelectQuery(
            columns = listOf(Expression.Star),
            from = model.tableName,
            where = where,
            orderBy = orderBy,
            limit = limit,
            offset = offset,
            distinct = distinct
        )

        val driver = DatabaseDriverRegistry.driver
        val rows = driver.executeQuery(selectQuery)

        return rows.map { model.rowToEntity(it) }
    }

    /**
     * Returns the first object of a query, or null if no match is found.
     */
    suspend fun firstOrNull(): T? {
        return limit(1).toList().firstOrNull()
    }

    /**
     * Returns the first object of a query. Throws if no match is found.
     */
    suspend fun first(): T {
        return firstOrNull() ?: throw NoSuchElementException("Query returned no results")
    }

    /**
     * Returns the number of objects matching the query.
     */
    suspend fun count(): Long {
        val countQuery = SelectQuery(
            columns = listOf(Expression.FunctionCall("COUNT", listOf(Expression.Star))),
            from = model.tableName,
            where = where,
            distinct = distinct
        )

        val driver = DatabaseDriverRegistry.driver
        val rows = driver.executeQuery(countQuery)

        if (rows.isEmpty()) return 0
        
        // Depending on the driver, COUNT might return Long or Int.
        // We need to handle this safely.
        val firstColumn = rows[0].getColumnNames().first()
        val countVal = rows[0].getValue(firstColumn)
        return when (countVal) {
            is Long -> countVal
            is Int -> countVal.toLong()
            else -> countVal.toString().toLong()
        }
    }

    /**
     * Returns true if the query contains any results.
     */
    suspend fun exists(): Boolean {
        return count() > 0
    }

    private fun copy(
        where: WhereClause? = this.where,
        orderBy: List<OrderByClause> = this.orderBy,
        limit: Int? = this.limit,
        offset: Int? = this.offset,
        distinct: Boolean = this.distinct
    ): QuerySet<T> {
        return QuerySet(model, where, orderBy, limit, offset, distinct)
    }
}
