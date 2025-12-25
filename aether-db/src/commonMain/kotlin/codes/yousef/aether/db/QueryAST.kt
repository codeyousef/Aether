package codes.yousef.aether.db

import kotlinx.serialization.Serializable

/**
 * Abstract Syntax Tree for SQL queries.
 * This is a platform-agnostic representation that can be serialized and translated to actual SQL.
 */
@Serializable
sealed class QueryAST

// ============= Query Types =============

@Serializable
data class SelectQuery(
    val columns: List<Expression>,
    val from: String,
    val joins: List<JoinClause> = emptyList(),
    val where: WhereClause? = null,
    val orderBy: List<OrderByClause> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    val distinct: Boolean = false
) : QueryAST()

@Serializable
data class InsertQuery(
    val table: String,
    val columns: List<String>,
    val values: List<Expression>,
    val returning: List<String> = emptyList()
) : QueryAST()

@Serializable
data class UpdateQuery(
    val table: String,
    val assignments: Map<String, Expression>,
    val where: WhereClause? = null,
    val returning: List<String> = emptyList()
) : QueryAST()

@Serializable
data class DeleteQuery(
    val table: String,
    val where: WhereClause? = null,
    val returning: List<String> = emptyList()
) : QueryAST()

@Serializable
data class CreateTableQuery(
    val table: String,
    val columns: List<ColumnDefinition>,
    val constraints: List<TableConstraint> = emptyList(),
    val ifNotExists: Boolean = true
) : QueryAST()

// ============= Column Definition =============

@Serializable
data class ColumnDefinition(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val primaryKey: Boolean = false,
    val unique: Boolean = false,
    val defaultValue: Expression? = null,
    val autoIncrement: Boolean = false
)

@Serializable
sealed class TableConstraint {
    @Serializable
    data class PrimaryKey(val columns: List<String>) : TableConstraint()

    @Serializable
    data class ForeignKey(
        val columns: List<String>,
        val referencedTable: String,
        val referencedColumns: List<String>,
        val onDelete: ReferenceAction = ReferenceAction.NO_ACTION,
        val onUpdate: ReferenceAction = ReferenceAction.NO_ACTION
    ) : TableConstraint()

    @Serializable
    data class Unique(val columns: List<String>) : TableConstraint()

    @Serializable
    data class Check(val condition: WhereClause) : TableConstraint()
}

@Serializable
enum class ReferenceAction {
    NO_ACTION,
    RESTRICT,
    CASCADE,
    SET_NULL,
    SET_DEFAULT
}

// ============= Where Clause =============

@Serializable
sealed class WhereClause {
    @Serializable
    data class Condition(
        val left: Expression,
        val operator: ComparisonOperator,
        val right: Expression
    ) : WhereClause()

    @Serializable
    data class And(val conditions: List<WhereClause>) : WhereClause()

    @Serializable
    data class Or(val conditions: List<WhereClause>) : WhereClause()

    @Serializable
    data class Not(val condition: WhereClause) : WhereClause()

    @Serializable
    data class In(val column: Expression, val values: List<Expression>) : WhereClause()

    @Serializable
    data class InSubQuery(val column: Expression, val subQuery: SelectQuery) : WhereClause()

    @Serializable
    data class Between(val column: Expression, val lower: Expression, val upper: Expression) : WhereClause()

    @Serializable
    data class IsNull(val column: Expression) : WhereClause()

    @Serializable
    data class IsNotNull(val column: Expression) : WhereClause()

    @Serializable
    data class Like(val column: Expression, val pattern: Expression) : WhereClause()
}

@Serializable
enum class ComparisonOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL
}

// ============= Expression =============

@Serializable
sealed class Expression {
    @Serializable
    data class ColumnRef(val table: String? = null, val column: String) : Expression()

    @Serializable
    data class Literal(val value: SqlValue) : Expression()

    @Serializable
    data class BinaryOp(
        val left: Expression,
        val operator: BinaryOperator,
        val right: Expression
    ) : Expression()

    @Serializable
    data class UnaryOp(
        val operator: UnaryOperator,
        val operand: Expression
    ) : Expression()

    @Serializable
    data class FunctionCall(
        val name: String,
        val arguments: List<Expression>
    ) : Expression()

    @Serializable
    data class CaseExpression(
        val conditions: List<Pair<WhereClause, Expression>>,
        val elseExpression: Expression? = null
    ) : Expression()

    @Serializable
    data class SubQuery(val query: SelectQuery) : Expression()

    @Serializable
    object Star : Expression()
}

@Serializable
sealed class SqlValue {
    @Serializable
    data class StringValue(val value: String) : SqlValue()

    @Serializable
    data class IntValue(val value: Int) : SqlValue()

    @Serializable
    data class LongValue(val value: Long) : SqlValue()

    @Serializable
    data class DoubleValue(val value: Double) : SqlValue()

    @Serializable
    data class BooleanValue(val value: Boolean) : SqlValue()

    @Serializable
    object NullValue : SqlValue()
}

@Serializable
enum class BinaryOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    MODULO,
    CONCAT
}

@Serializable
enum class UnaryOperator {
    NEGATE,
    NOT
}

// ============= Join Clause =============

@Serializable
data class JoinClause(
    val type: JoinType,
    val table: String,
    val alias: String? = null,
    val on: WhereClause
)

@Serializable
enum class JoinType {
    INNER,
    LEFT,
    RIGHT,
    FULL,
    CROSS
}

// ============= Order By Clause =============

@Serializable
data class OrderByClause(
    val expression: Expression,
    val direction: OrderDirection = OrderDirection.ASC
)

@Serializable
enum class OrderDirection {
    ASC,
    DESC
}
