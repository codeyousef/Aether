package codes.yousef.aether.db

infix fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.eq(value: V): WhereClause {
    return WhereClause.Condition(
        left = Expression.ColumnRef(column = this.name),
        operator = ComparisonOperator.EQUALS,
        right = toExpression(value)
    )
}

infix fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.neq(value: V): WhereClause {
    return WhereClause.Condition(
        left = Expression.ColumnRef(column = this.name),
        operator = ComparisonOperator.NOT_EQUALS,
        right = toExpression(value)
    )
}

infix fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.gt(value: V): WhereClause {
    return WhereClause.Condition(
        left = Expression.ColumnRef(column = this.name),
        operator = ComparisonOperator.GREATER_THAN,
        right = toExpression(value)
    )
}

infix fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.gte(value: V): WhereClause {
    return WhereClause.Condition(
        left = Expression.ColumnRef(column = this.name),
        operator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
        right = toExpression(value)
    )
}

infix fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.lt(value: V): WhereClause {
    return WhereClause.Condition(
        left = Expression.ColumnRef(column = this.name),
        operator = ComparisonOperator.LESS_THAN,
        right = toExpression(value)
    )
}

infix fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.lte(value: V): WhereClause {
    return WhereClause.Condition(
        left = Expression.ColumnRef(column = this.name),
        operator = ComparisonOperator.LESS_THAN_OR_EQUAL,
        right = toExpression(value)
    )
}

infix fun <T : BaseEntity<T>> ColumnProperty<T, String>.like(pattern: String): WhereClause {
    return WhereClause.Like(
        column = Expression.ColumnRef(column = this.name),
        pattern = toExpression(pattern)
    )
}

infix fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.inList(values: List<V>): WhereClause {
    return WhereClause.In(
        column = Expression.ColumnRef(column = this.name),
        values = values.map { toExpression(it) }
    )
}

fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.asc(): OrderByClause {
    return OrderByClause(
        expression = Expression.ColumnRef(column = this.name),
        direction = OrderDirection.ASC
    )
}

fun <T : BaseEntity<T>, V> ColumnProperty<T, V>.desc(): OrderByClause {
    return OrderByClause(
        expression = Expression.ColumnRef(column = this.name),
        direction = OrderDirection.DESC
    )
}
