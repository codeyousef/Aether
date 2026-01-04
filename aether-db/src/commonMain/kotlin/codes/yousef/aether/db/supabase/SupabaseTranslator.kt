package codes.yousef.aether.db.supabase

import codes.yousef.aether.db.*

/**
 * Result of translating a QueryAST to Supabase PostgREST format.
 * PostgREST uses URL query parameters for filtering and JSON body for mutations.
 */
data class SupabaseQuery(
    /** The table name (used as the endpoint path) */
    val table: String,
    /** HTTP method to use */
    val method: HttpMethod,
    /** Query parameters for filtering, ordering, pagination */
    val queryParams: Map<String, String> = emptyMap(),
    /** JSON body for INSERT/UPDATE operations */
    val body: String? = null,
    /** Headers to include (e.g., Prefer for returning rows) */
    val headers: Map<String, String> = emptyMap()
) {
    enum class HttpMethod { GET, POST, PATCH, DELETE }
    
    /**
     * Builds the full URL with query parameters.
     */
    fun buildUrl(baseUrl: String): String {
        val url = "$baseUrl/$table"
        return if (queryParams.isEmpty()) {
            url
        } else {
            "$url?${queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }}"
        }
    }
}

/**
 * Translates QueryAST to Supabase PostgREST format.
 * 
 * PostgREST query syntax:
 * - Equality: ?column=eq.value
 * - Greater than: ?column=gt.value
 * - Less than: ?column=lt.value
 * - Like: ?column=like.*pattern*
 * - Is null: ?column=is.null
 * - In: ?column=in.(val1,val2)
 * - And: Uses multiple query params
 * - Or: ?or=(column.eq.val1,column.eq.val2)
 * - Order: ?order=column.asc
 * - Limit: ?limit=10
 * - Offset: ?offset=5
 * - Select columns: ?select=col1,col2
 * 
 * @see <a href="https://postgrest.org/en/stable/references/api/tables_views.html">PostgREST API</a>
 */
object SupabaseTranslator {

    /**
     * Translates a QueryAST to a SupabaseQuery.
     * @throws UnsupportedOperationException for unsupported query types
     */
    fun translate(query: QueryAST): SupabaseQuery {
        return when (query) {
            is SelectQuery -> translateSelect(query)
            is InsertQuery -> translateInsert(query)
            is UpdateQuery -> translateUpdate(query)
            is DeleteQuery -> translateDelete(query)
            is CreateTableQuery -> throw UnsupportedOperationException(
                "DDL operations are not supported via Supabase REST API. Use Supabase Dashboard or migrations."
            )
            is RawQuery -> throw UnsupportedOperationException(
                "Raw SQL is not supported via Supabase REST API. Use Supabase's SQL Editor or direct PostgreSQL connection."
            )
        }
    }

    private fun translateSelect(query: SelectQuery): SupabaseQuery {
        val params = mutableMapOf<String, String>()
        val headers = mutableMapOf<String, String>()

        // SELECT columns
        if (query.columns.isNotEmpty() && query.columns.first() != Expression.Star) {
            val selectCols = query.columns.mapNotNull { expr ->
                when (expr) {
                    is Expression.ColumnRef -> expr.column
                    is Expression.FunctionCall -> translateFunction(expr)
                    Expression.Star -> "*"
                    else -> null
                }
            }
            if (selectCols.isNotEmpty()) {
                params["select"] = selectCols.joinToString(",")
            }
        }

        // WHERE clause
        query.where?.let { where ->
            val whereParams = translateWhere(where)
            params.putAll(whereParams)
        }

        // ORDER BY
        if (query.orderBy.isNotEmpty()) {
            params["order"] = query.orderBy.joinToString(",") { orderBy ->
                val col = (orderBy.expression as? Expression.ColumnRef)?.column
                    ?: throw UnsupportedOperationException("PostgREST only supports column references in ORDER BY")
                val dir = orderBy.direction.name.lowercase()
                "$col.$dir"
            }
        }

        // LIMIT
        query.limit?.let { params["limit"] = it.toString() }

        // OFFSET
        query.offset?.let { params["offset"] = it.toString() }

        // JOINs - PostgREST supports embedded resources via foreign keys
        if (query.joins.isNotEmpty()) {
            // PostgREST handles joins via resource embedding in select
            // e.g., ?select=*,foreign_table(*)
            throw UnsupportedOperationException(
                "Explicit JOINs not supported. Use PostgREST resource embedding instead: ?select=*,related_table(*)"
            )
        }

        // DISTINCT
        if (query.distinct) {
            headers["Prefer"] = "count=exact"
            // PostgREST doesn't directly support DISTINCT, but we can use RPC or limit results
        }

        return SupabaseQuery(
            table = query.from,
            method = SupabaseQuery.HttpMethod.GET,
            queryParams = params,
            headers = headers
        )
    }

    private fun translateInsert(query: InsertQuery): SupabaseQuery {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Prefer" to "return=representation"
        )

        // Build JSON body
        val jsonObj = query.columns.zip(query.values).associate { (col, expr) ->
            col to expressionToJsonValue(expr)
        }
        val body = jsonMapToString(jsonObj)

        return SupabaseQuery(
            table = query.table,
            method = SupabaseQuery.HttpMethod.POST,
            body = body,
            headers = headers
        )
    }

    private fun translateUpdate(query: UpdateQuery): SupabaseQuery {
        val params = mutableMapOf<String, String>()
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Prefer" to "return=representation"
        )

        // WHERE clause is required for updates (safety)
        query.where?.let { where ->
            val whereParams = translateWhere(where)
            params.putAll(whereParams)
        } ?: throw UnsupportedOperationException(
            "UPDATE without WHERE clause is not allowed for safety. Use a condition like id=gt.0 if you want to update all rows."
        )

        // Build JSON body from assignments
        val jsonObj = query.assignments.mapValues { (_, expr) ->
            expressionToJsonValue(expr)
        }
        val body = jsonMapToString(jsonObj)

        return SupabaseQuery(
            table = query.table,
            method = SupabaseQuery.HttpMethod.PATCH,
            queryParams = params,
            body = body,
            headers = headers
        )
    }

    private fun translateDelete(query: DeleteQuery): SupabaseQuery {
        val params = mutableMapOf<String, String>()
        val headers = mutableMapOf(
            "Prefer" to "return=representation"
        )

        // WHERE clause is required for deletes (safety)
        query.where?.let { where ->
            val whereParams = translateWhere(where)
            params.putAll(whereParams)
        } ?: throw UnsupportedOperationException(
            "DELETE without WHERE clause is not allowed for safety."
        )

        return SupabaseQuery(
            table = query.table,
            method = SupabaseQuery.HttpMethod.DELETE,
            queryParams = params,
            headers = headers
        )
    }

    /**
     * Translates a WhereClause to PostgREST query parameters.
     */
    private fun translateWhere(where: WhereClause): Map<String, String> {
        val params = mutableMapOf<String, String>()

        when (where) {
            is WhereClause.Condition -> {
                val col = getColumnName(where.left)
                val value = getLiteralValue(where.right)
                val op = when (where.operator) {
                    ComparisonOperator.EQUALS -> "eq"
                    ComparisonOperator.NOT_EQUALS -> "neq"
                    ComparisonOperator.GREATER_THAN -> "gt"
                    ComparisonOperator.GREATER_THAN_OR_EQUAL -> "gte"
                    ComparisonOperator.LESS_THAN -> "lt"
                    ComparisonOperator.LESS_THAN_OR_EQUAL -> "lte"
                }
                params[col] = "$op.$value"
            }

            is WhereClause.And -> {
                // Multiple conditions become multiple query params
                where.conditions.forEach { condition ->
                    params.putAll(translateWhere(condition))
                }
            }

            is WhereClause.Or -> {
                // OR requires special syntax: ?or=(cond1,cond2)
                val orConditions = where.conditions.map { condition ->
                    translateWhereToString(condition)
                }
                params["or"] = "(${orConditions.joinToString(",")})"
            }

            is WhereClause.Not -> {
                val innerParams = translateWhere(where.condition)
                innerParams.forEach { (key, value) ->
                    params[key] = "not.$value"
                }
            }

            is WhereClause.In -> {
                val col = getColumnName(where.column)
                val values = where.values.map { getLiteralValue(it) }
                params[col] = "in.(${values.joinToString(",")})"
            }

            is WhereClause.Between -> {
                val col = getColumnName(where.column)
                val lower = getLiteralValue(where.lower)
                val upper = getLiteralValue(where.upper)
                // PostgREST doesn't have BETWEEN, use gte and lte
                params[col] = "gte.$lower"
                params["${col}@lte"] = "lte.$upper" // Use different key to avoid override
            }

            is WhereClause.IsNull -> {
                val col = getColumnName(where.column)
                params[col] = "is.null"
            }

            is WhereClause.IsNotNull -> {
                val col = getColumnName(where.column)
                params[col] = "not.is.null"
            }

            is WhereClause.Like -> {
                val col = getColumnName(where.column)
                val pattern = getLiteralValue(where.pattern)
                // Convert SQL LIKE pattern to PostgREST pattern
                val pgRestPattern = pattern.toString()
                    .replace("%", "*")
                    .replace("_", "?")
                params[col] = "like.$pgRestPattern"
            }

            is WhereClause.InSubQuery -> {
                throw UnsupportedOperationException("Subqueries are not supported in Supabase REST API")
            }
        }

        return params
    }

    /**
     * Translates a single where condition to string format for OR clauses.
     */
    private fun translateWhereToString(where: WhereClause): String {
        return when (where) {
            is WhereClause.Condition -> {
                val col = getColumnName(where.left)
                val value = getLiteralValue(where.right)
                val op = when (where.operator) {
                    ComparisonOperator.EQUALS -> "eq"
                    ComparisonOperator.NOT_EQUALS -> "neq"
                    ComparisonOperator.GREATER_THAN -> "gt"
                    ComparisonOperator.GREATER_THAN_OR_EQUAL -> "gte"
                    ComparisonOperator.LESS_THAN -> "lt"
                    ComparisonOperator.LESS_THAN_OR_EQUAL -> "lte"
                }
                "$col.$op.$value"
            }
            is WhereClause.IsNull -> "${getColumnName(where.column)}.is.null"
            is WhereClause.IsNotNull -> "${getColumnName(where.column)}.not.is.null"
            else -> throw UnsupportedOperationException("Complex conditions in OR clauses not fully supported")
        }
    }

    private fun getColumnName(expr: Expression): String {
        return when (expr) {
            is Expression.ColumnRef -> expr.column
            else -> throw UnsupportedOperationException("Only column references supported in WHERE clause")
        }
    }

    private fun getLiteralValue(expr: Expression): Any {
        return when (expr) {
            is Expression.Literal -> when (val v = expr.value) {
                is SqlValue.StringValue -> v.value
                is SqlValue.IntValue -> v.value
                is SqlValue.LongValue -> v.value
                is SqlValue.DoubleValue -> v.value
                is SqlValue.BooleanValue -> v.value
                SqlValue.NullValue -> "null"
            }
            is Expression.ColumnRef -> expr.column
            else -> throw UnsupportedOperationException("Only literals supported in comparisons for REST API")
        }
    }

    private fun expressionToJsonValue(expr: Expression): Any? {
        return when (expr) {
            is Expression.Literal -> when (val v = expr.value) {
                is SqlValue.StringValue -> v.value
                is SqlValue.IntValue -> v.value
                is SqlValue.LongValue -> v.value
                is SqlValue.DoubleValue -> v.value
                is SqlValue.BooleanValue -> v.value
                SqlValue.NullValue -> null
            }
            else -> throw UnsupportedOperationException("Only literal values supported in INSERT/UPDATE body")
        }
    }

    private fun translateFunction(func: Expression.FunctionCall): String {
        // PostgREST supports some aggregate functions
        return when (func.name.uppercase()) {
            "COUNT" -> "count"
            "SUM" -> "sum"
            "AVG" -> "avg"
            "MIN" -> "min"
            "MAX" -> "max"
            else -> throw UnsupportedOperationException("Function ${func.name} not supported in PostgREST")
        }
    }

    /**
     * Converts a map to JSON string without external dependencies.
     */
    private fun jsonMapToString(map: Map<String, Any?>): String {
        val entries = map.entries.joinToString(",") { (key, value) ->
            "\"$key\":${valueToJson(value)}"
        }
        return "{$entries}"
    }

    private fun valueToJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${escapeJsonString(value)}\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> "\"${escapeJsonString(value.toString())}\""
        }
    }

    private fun escapeJsonString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
