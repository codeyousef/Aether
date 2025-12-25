package codes.yousef.aether.db

/**
 * Result of SQL translation containing the SQL string and parameters.
 */
data class TranslatedQuery(
    val sql: String,
    val params: List<Any?>
)

/**
 * Translates QueryAST to PostgreSQL SQL with parameterized queries.
 */
object SqlTranslator {
    fun translate(query: QueryAST): TranslatedQuery {
        val params = mutableListOf<Any?>()
        val sql = when (query) {
            is SelectQuery -> translateSelect(query, params)
            is InsertQuery -> translateInsert(query, params)
            is UpdateQuery -> translateUpdate(query, params)
            is DeleteQuery -> translateDelete(query, params)
            is CreateTableQuery -> translateCreateTable(query)
            is RawQuery -> query.sql
        }
        return TranslatedQuery(sql, params)
    }

    private fun translateSelect(query: SelectQuery, params: MutableList<Any?>): String {
        val sb = StringBuilder("SELECT ")

        if (query.distinct) {
            sb.append("DISTINCT ")
        }

        // Columns
        if (query.columns.isEmpty()) {
            sb.append("*")
        } else {
            sb.append(query.columns.joinToString(", ") { translateExpression(it, params) })
        }

        // FROM
        sb.append(" FROM ").append(query.from)

        // JOINs
        for (join in query.joins) {
            sb.append(translateJoin(join, params))
        }

        // WHERE
        query.where?.let {
            sb.append(" WHERE ").append(translateWhereClause(it, params))
        }

        // ORDER BY
        if (query.orderBy.isNotEmpty()) {
            sb.append(" ORDER BY ")
            sb.append(query.orderBy.joinToString(", ") { orderBy ->
                "${translateExpression(orderBy.expression, params)} ${orderBy.direction.name}"
            })
        }

        // LIMIT
        query.limit?.let {
            sb.append(" LIMIT $it")
        }

        // OFFSET
        query.offset?.let {
            sb.append(" OFFSET $it")
        }

        return sb.toString()
    }

    private fun translateInsert(query: InsertQuery, params: MutableList<Any?>): String {
        val sb = StringBuilder("INSERT INTO ").append(query.table)

        sb.append(" (")
        sb.append(query.columns.joinToString(", "))
        sb.append(") VALUES (")
        sb.append(query.values.joinToString(", ") { translateExpression(it, params) })
        sb.append(")")

        if (query.returning.isNotEmpty()) {
            sb.append(" RETURNING ")
            sb.append(query.returning.joinToString(", "))
        }

        return sb.toString()
    }

    private fun translateUpdate(query: UpdateQuery, params: MutableList<Any?>): String {
        val sb = StringBuilder("UPDATE ").append(query.table).append(" SET ")

        sb.append(query.assignments.entries.joinToString(", ") { (column, expr) ->
            "$column = ${translateExpression(expr, params)}"
        })

        query.where?.let {
            sb.append(" WHERE ").append(translateWhereClause(it, params))
        }

        if (query.returning.isNotEmpty()) {
            sb.append(" RETURNING ")
            sb.append(query.returning.joinToString(", "))
        }

        return sb.toString()
    }

    private fun translateDelete(query: DeleteQuery, params: MutableList<Any?>): String {
        val sb = StringBuilder("DELETE FROM ").append(query.table)

        query.where?.let {
            sb.append(" WHERE ").append(translateWhereClause(it, params))
        }

        if (query.returning.isNotEmpty()) {
            sb.append(" RETURNING ")
            sb.append(query.returning.joinToString(", "))
        }

        return sb.toString()
    }

    private fun translateCreateTable(query: CreateTableQuery): String {
        val sb = StringBuilder("CREATE TABLE ")

        if (query.ifNotExists) {
            sb.append("IF NOT EXISTS ")
        }

        sb.append(query.table).append(" (")

        val columnDefs = query.columns.map { col ->
            buildString {
                append(col.name).append(" ").append(col.type)

                if (!col.nullable) {
                    append(" NOT NULL")
                }

                if (col.primaryKey) {
                    append(" PRIMARY KEY")
                }

                if (col.unique) {
                    append(" UNIQUE")
                }

                col.defaultValue?.let { default ->
                    append(" DEFAULT ")
                    when (val value = (default as? Expression.Literal)?.value) {
                        is SqlValue.StringValue -> append("'${value.value}'")
                        is SqlValue.IntValue -> append(value.value)
                        is SqlValue.LongValue -> append(value.value)
                        is SqlValue.DoubleValue -> append(value.value)
                        is SqlValue.BooleanValue -> append(value.value)
                        SqlValue.NullValue -> append("NULL")
                        null -> {}
                    }
                }
            }
        }

        sb.append(columnDefs.joinToString(", "))

        // Table constraints
        val constraints = query.constraints.mapNotNull { constraint ->
            when (constraint) {
                is TableConstraint.PrimaryKey -> {
                    "PRIMARY KEY (${constraint.columns.joinToString(", ")})"
                }
                is TableConstraint.ForeignKey -> {
                    "FOREIGN KEY (${constraint.columns.joinToString(", ")}) " +
                            "REFERENCES ${constraint.referencedTable} (${constraint.referencedColumns.joinToString(", ")}) " +
                            "ON DELETE ${constraint.onDelete.toSql()} " +
                            "ON UPDATE ${constraint.onUpdate.toSql()}"
                }
                is TableConstraint.Unique -> {
                    "UNIQUE (${constraint.columns.joinToString(", ")})"
                }
                is TableConstraint.Check -> {
                    val params = mutableListOf<Any?>()
                    "CHECK (${translateWhereClause(constraint.condition, params)})"
                }
            }
        }

        if (constraints.isNotEmpty()) {
            sb.append(", ").append(constraints.joinToString(", "))
        }

        sb.append(")")

        return sb.toString()
    }

    private fun translateJoin(join: JoinClause, params: MutableList<Any?>): String {
        val sb = StringBuilder(" ")

        when (join.type) {
            JoinType.INNER -> sb.append("INNER JOIN")
            JoinType.LEFT -> sb.append("LEFT JOIN")
            JoinType.RIGHT -> sb.append("RIGHT JOIN")
            JoinType.FULL -> sb.append("FULL JOIN")
            JoinType.CROSS -> sb.append("CROSS JOIN")
        }

        sb.append(" ").append(join.table)

        join.alias?.let {
            sb.append(" AS ").append(it)
        }

        if (join.type != JoinType.CROSS) {
            sb.append(" ON ").append(translateWhereClause(join.on, params))
        }

        return sb.toString()
    }

    private fun translateWhereClause(where: WhereClause, params: MutableList<Any?>): String {
        return when (where) {
            is WhereClause.Condition -> {
                val left = translateExpression(where.left, params)
                val op = when (where.operator) {
                    ComparisonOperator.EQUALS -> "="
                    ComparisonOperator.NOT_EQUALS -> "!="
                    ComparisonOperator.GREATER_THAN -> ">"
                    ComparisonOperator.GREATER_THAN_OR_EQUAL -> ">="
                    ComparisonOperator.LESS_THAN -> "<"
                    ComparisonOperator.LESS_THAN_OR_EQUAL -> "<="
                }
                val right = translateExpression(where.right, params)
                "$left $op $right"
            }
            is WhereClause.And -> {
                where.conditions.joinToString(" AND ", "(", ")") {
                    translateWhereClause(it, params)
                }
            }
            is WhereClause.Or -> {
                where.conditions.joinToString(" OR ", "(", ")") {
                    translateWhereClause(it, params)
                }
            }
            is WhereClause.Not -> {
                "NOT (${translateWhereClause(where.condition, params)})"
            }
            is WhereClause.In -> {
                val col = translateExpression(where.column, params)
                val values = where.values.joinToString(", ") { translateExpression(it, params) }
                "$col IN ($values)"
            }
            is WhereClause.Between -> {
                val col = translateExpression(where.column, params)
                val lower = translateExpression(where.lower, params)
                val upper = translateExpression(where.upper, params)
                "$col BETWEEN $lower AND $upper"
            }
            is WhereClause.IsNull -> {
                "${translateExpression(where.column, params)} IS NULL"
            }
            is WhereClause.IsNotNull -> {
                "${translateExpression(where.column, params)} IS NOT NULL"
            }
            is WhereClause.Like -> {
                val col = translateExpression(where.column, params)
                val pattern = translateExpression(where.pattern, params)
                "$col LIKE $pattern"
            }
        }
    }

    private fun translateExpression(expr: Expression, params: MutableList<Any?>): String {
        return when (expr) {
            is Expression.ColumnRef -> {
                if (expr.table != null) {
                    "${expr.table}.${expr.column}"
                } else {
                    expr.column
                }
            }
            is Expression.Literal -> {
                params.add(sqlValueToAny(expr.value))
                "$${params.size}"
            }
            is Expression.BinaryOp -> {
                val left = translateExpression(expr.left, params)
                val op = when (expr.operator) {
                    BinaryOperator.ADD -> "+"
                    BinaryOperator.SUBTRACT -> "-"
                    BinaryOperator.MULTIPLY -> "*"
                    BinaryOperator.DIVIDE -> "/"
                    BinaryOperator.MODULO -> "%"
                    BinaryOperator.CONCAT -> "||"
                }
                val right = translateExpression(expr.right, params)
                "($left $op $right)"
            }
            is Expression.UnaryOp -> {
                val op = when (expr.operator) {
                    UnaryOperator.NEGATE -> "-"
                    UnaryOperator.NOT -> "NOT "
                }
                val operand = translateExpression(expr.operand, params)
                "$op$operand"
            }
            is Expression.FunctionCall -> {
                val args = expr.arguments.joinToString(", ") { translateExpression(it, params) }
                "${expr.name}($args)"
            }
            is Expression.CaseExpression -> {
                val sb = StringBuilder("CASE")
                for ((condition, result) in expr.conditions) {
                    sb.append(" WHEN ").append(translateWhereClause(condition, params))
                    sb.append(" THEN ").append(translateExpression(result, params))
                }
                expr.elseExpression?.let {
                    sb.append(" ELSE ").append(translateExpression(it, params))
                }
                sb.append(" END")
                sb.toString()
            }
            is Expression.SubQuery -> {
                "(${translateSelect(expr.query, params)})"
            }
            Expression.Star -> "*"
        }
    }

    private fun sqlValueToAny(value: SqlValue): Any? {
        return when (value) {
            is SqlValue.StringValue -> value.value
            is SqlValue.IntValue -> value.value
            is SqlValue.LongValue -> value.value
            is SqlValue.DoubleValue -> value.value
            is SqlValue.BooleanValue -> value.value
            SqlValue.NullValue -> null
        }
    }

    private fun ReferenceAction.toSql(): String {
        return when (this) {
            ReferenceAction.NO_ACTION -> "NO ACTION"
            ReferenceAction.RESTRICT -> "RESTRICT"
            ReferenceAction.CASCADE -> "CASCADE"
            ReferenceAction.SET_NULL -> "SET NULL"
            ReferenceAction.SET_DEFAULT -> "SET DEFAULT"
        }
    }
}
