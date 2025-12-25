package codes.yousef.aether.db

fun toExpression(value: Any?): Expression {
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
