package codes.yousef.aether.db.firestore

import codes.yousef.aether.db.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Result of translating a QueryAST to Firestore REST API format.
 * Uses Firestore's runQuery structured query format.
 * 
 * @see <a href="https://firebase.google.com/docs/firestore/reference/rest/v1/projects.databases.documents/runQuery">Firestore REST API</a>
 */
data class FirestoreQuery(
    /** The collection name (used as the document path) */
    val collection: String,
    /** HTTP method to use */
    val method: HttpMethod,
    /** The request body as JSON */
    val body: String?,
    /** Document ID for single-document operations */
    val documentId: String? = null,
    /** Query parameters (for pagination tokens, etc.) */
    val queryParams: Map<String, String> = emptyMap()
) {
    enum class HttpMethod { GET, POST, PATCH, DELETE }

    /**
     * Builds the Firestore REST API URL.
     * @param projectId The Firebase project ID
     * @param databaseId The database ID (usually "(default)")
     */
    fun buildUrl(projectId: String, databaseId: String = "(default)"): String {
        val basePath = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents"
        
        return when {
            documentId != null -> "$basePath/$collection/$documentId"
            method == HttpMethod.POST && body?.contains("structuredQuery") == true -> 
                "$basePath:runQuery"
            else -> "$basePath/$collection"
        }
    }
}

/**
 * Translates QueryAST to Firestore REST API format.
 * 
 * Firestore limitations compared to SQL:
 * - No JOINs (denormalized data model)
 * - Limited WHERE operators (no complex expressions)
 * - No DISTINCT
 * - Composite indexes required for certain queries
 * - OR queries limited to single field or require composite
 * 
 * Supported operations:
 * - SELECT: via runQuery with structuredQuery
 * - INSERT: via createDocument
 * - UPDATE: via patch
 * - DELETE: via delete
 */
object FirestoreTranslator {
    
    private val json = Json { 
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * Translates a QueryAST to a FirestoreQuery.
     * @throws UnsupportedOperationException for unsupported query types or operations
     */
    fun translate(query: QueryAST): FirestoreQuery {
        return when (query) {
            is SelectQuery -> translateSelect(query)
            is InsertQuery -> translateInsert(query)
            is UpdateQuery -> translateUpdate(query)
            is DeleteQuery -> translateDelete(query)
            is CreateTableQuery -> throw UnsupportedOperationException(
                "Firestore is schemaless - collections are created automatically when documents are added"
            )
            is RawQuery -> throw UnsupportedOperationException(
                "Raw SQL is not supported in Firestore"
            )
        }
    }

    private fun translateSelect(query: SelectQuery): FirestoreQuery {
        // Check for unsupported features
        if (query.joins.isNotEmpty()) {
            throw UnsupportedOperationException(
                "JOINs are not supported in Firestore. Use denormalized data or subcollections."
            )
        }
        if (query.distinct) {
            throw UnsupportedOperationException(
                "DISTINCT is not supported in Firestore"
            )
        }

        val structuredQuery = buildStructuredQuery(query)
        val body = json.encodeToString(RunQueryRequest(structuredQuery))

        return FirestoreQuery(
            collection = query.from,
            method = FirestoreQuery.HttpMethod.POST,
            body = body
        )
    }

    private fun buildStructuredQuery(query: SelectQuery): StructuredQuery {
        val from = listOf(CollectionSelector(collectionId = query.from))
        
        // SELECT fields
        val select = if (query.columns.isEmpty() || query.columns.first() == Expression.Star) {
            null // Select all fields
        } else {
            val fields = query.columns.mapNotNull { expr ->
                when (expr) {
                    is Expression.ColumnRef -> FieldReference(expr.column)
                    else -> null
                }
            }
            if (fields.isNotEmpty()) Projection(fields) else null
        }

        // WHERE clause
        val where = query.where?.let { translateWhere(it) }

        // ORDER BY
        val orderBy = if (query.orderBy.isNotEmpty()) {
            query.orderBy.map { orderByClause ->
                val field = (orderByClause.expression as? Expression.ColumnRef)?.column
                    ?: throw UnsupportedOperationException("Only column references supported in ORDER BY")
                Order(
                    field = FieldReference(field),
                    direction = when (orderByClause.direction) {
                        OrderDirection.ASC -> "ASCENDING"
                        OrderDirection.DESC -> "DESCENDING"
                    }
                )
            }
        } else null

        return StructuredQuery(
            from = from,
            select = select,
            where = where,
            orderBy = orderBy,
            limit = query.limit,
            offset = query.offset
        )
    }

    private fun translateWhere(where: WhereClause): Filter {
        return when (where) {
            is WhereClause.Condition -> {
                val field = getColumnName(where.left)
                val value = expressionToFirestoreValue(where.right)
                val op = when (where.operator) {
                    ComparisonOperator.EQUALS -> "EQUAL"
                    ComparisonOperator.NOT_EQUALS -> "NOT_EQUAL"
                    ComparisonOperator.GREATER_THAN -> "GREATER_THAN"
                    ComparisonOperator.GREATER_THAN_OR_EQUAL -> "GREATER_THAN_OR_EQUAL"
                    ComparisonOperator.LESS_THAN -> "LESS_THAN"
                    ComparisonOperator.LESS_THAN_OR_EQUAL -> "LESS_THAN_OR_EQUAL"
                }
                Filter(
                    fieldFilter = FieldFilter(
                        field = FieldReference(field),
                        op = op,
                        value = value
                    )
                )
            }

            is WhereClause.And -> {
                Filter(
                    compositeFilter = CompositeFilter(
                        op = "AND",
                        filters = where.conditions.map { translateWhere(it) }
                    )
                )
            }

            is WhereClause.Or -> {
                Filter(
                    compositeFilter = CompositeFilter(
                        op = "OR",
                        filters = where.conditions.map { translateWhere(it) }
                    )
                )
            }

            is WhereClause.Not -> {
                throw UnsupportedOperationException(
                    "NOT operator is not directly supported in Firestore. Use != for simple negations."
                )
            }

            is WhereClause.In -> {
                val field = getColumnName(where.column)
                val values = where.values.map { expressionToFirestoreValue(it) }
                Filter(
                    fieldFilter = FieldFilter(
                        field = FieldReference(field),
                        op = "IN",
                        value = FirestoreValue(arrayValue = ArrayValue(values))
                    )
                )
            }

            is WhereClause.IsNull -> {
                val field = getColumnName(where.column)
                Filter(
                    unaryFilter = UnaryFilter(
                        op = "IS_NULL",
                        field = FieldReference(field)
                    )
                )
            }

            is WhereClause.IsNotNull -> {
                val field = getColumnName(where.column)
                Filter(
                    unaryFilter = UnaryFilter(
                        op = "IS_NOT_NULL",
                        field = FieldReference(field)
                    )
                )
            }

            is WhereClause.Like -> {
                throw UnsupportedOperationException(
                    "LIKE queries are not supported in Firestore. Consider using full-text search solutions like Algolia or Typesense."
                )
            }

            is WhereClause.Between -> {
                // Translate BETWEEN to two conditions with AND
                val col = where.column
                val lowerCondition = WhereClause.Condition(
                    col, ComparisonOperator.GREATER_THAN_OR_EQUAL, where.lower
                )
                val upperCondition = WhereClause.Condition(
                    col, ComparisonOperator.LESS_THAN_OR_EQUAL, where.upper
                )
                translateWhere(WhereClause.And(listOf(lowerCondition, upperCondition)))
            }

            is WhereClause.InSubQuery -> {
                throw UnsupportedOperationException("Subqueries are not supported in Firestore")
            }
        }
    }

    private fun translateInsert(query: InsertQuery): FirestoreQuery {
        // Build document fields
        val fields = mutableMapOf<String, FirestoreValue>()
        query.columns.zip(query.values).forEach { (col, expr) ->
            fields[col] = expressionToFirestoreValue(expr)
        }

        val document = Document(fields = fields)
        val body = json.encodeToString(document)

        return FirestoreQuery(
            collection = query.table,
            method = FirestoreQuery.HttpMethod.POST,
            body = body
        )
    }

    private fun translateUpdate(query: UpdateQuery): FirestoreQuery {
        // Extract document ID from WHERE clause
        val documentId = extractDocumentId(query.where)
            ?: throw UnsupportedOperationException(
                "UPDATE requires a WHERE clause with document ID (e.g., WHERE id = 'doc_id')"
            )

        // Build update fields
        val fields = mutableMapOf<String, FirestoreValue>()
        query.assignments.forEach { (col, expr) ->
            fields[col] = expressionToFirestoreValue(expr)
        }

        val document = Document(fields = fields)
        val body = json.encodeToString(document)

        // Build update mask (list of fields to update)
        val updateMask = query.assignments.keys.joinToString(",")

        return FirestoreQuery(
            collection = query.table,
            method = FirestoreQuery.HttpMethod.PATCH,
            body = body,
            documentId = documentId,
            queryParams = mapOf("updateMask.fieldPaths" to updateMask)
        )
    }

    private fun translateDelete(query: DeleteQuery): FirestoreQuery {
        val documentId = extractDocumentId(query.where)
            ?: throw UnsupportedOperationException(
                "DELETE requires a WHERE clause with document ID (e.g., WHERE id = 'doc_id')"
            )

        return FirestoreQuery(
            collection = query.table,
            method = FirestoreQuery.HttpMethod.DELETE,
            body = null,
            documentId = documentId
        )
    }

    /**
     * Extracts document ID from a simple equality condition.
     * Looks for patterns like: id = 'value' or _id = 'value'
     */
    private fun extractDocumentId(where: WhereClause?): String? {
        if (where == null) return null
        
        return when (where) {
            is WhereClause.Condition -> {
                val col = getColumnName(where.left)
                if ((col == "id" || col == "_id") && where.operator == ComparisonOperator.EQUALS) {
                    getLiteralStringValue(where.right)
                } else null
            }
            is WhereClause.And -> {
                // Try to find ID in any of the AND conditions
                where.conditions.firstNotNullOfOrNull { extractDocumentId(it) }
            }
            else -> null
        }
    }

    private fun getColumnName(expr: Expression): String {
        return when (expr) {
            is Expression.ColumnRef -> expr.column
            else -> throw UnsupportedOperationException("Only column references supported")
        }
    }

    private fun getLiteralStringValue(expr: Expression): String? {
        return when (expr) {
            is Expression.Literal -> when (val v = expr.value) {
                is SqlValue.StringValue -> v.value
                is SqlValue.IntValue -> v.value.toString()
                is SqlValue.LongValue -> v.value.toString()
                else -> null
            }
            else -> null
        }
    }

    private fun expressionToFirestoreValue(expr: Expression): FirestoreValue {
        return when (expr) {
            is Expression.Literal -> sqlValueToFirestoreValue(expr.value)
            else -> throw UnsupportedOperationException(
                "Only literal values are supported in Firestore queries"
            )
        }
    }

    private fun sqlValueToFirestoreValue(value: SqlValue): FirestoreValue {
        return when (value) {
            is SqlValue.StringValue -> FirestoreValue(stringValue = value.value)
            is SqlValue.IntValue -> FirestoreValue(integerValue = value.value.toLong().toString())
            is SqlValue.LongValue -> FirestoreValue(integerValue = value.value.toString())
            is SqlValue.DoubleValue -> FirestoreValue(doubleValue = value.value)
            is SqlValue.BooleanValue -> FirestoreValue(booleanValue = value.value)
            SqlValue.NullValue -> FirestoreValue(nullValue = "NULL_VALUE")
        }
    }
}

// ============= Firestore REST API Data Types =============

@Serializable
internal data class RunQueryRequest(
    val structuredQuery: StructuredQuery
)

@Serializable
internal data class StructuredQuery(
    val from: List<CollectionSelector>,
    val select: Projection? = null,
    val where: Filter? = null,
    val orderBy: List<Order>? = null,
    val limit: Int? = null,
    val offset: Int? = null
)

@Serializable
internal data class CollectionSelector(
    val collectionId: String,
    val allDescendants: Boolean = false
)

@Serializable
internal data class Projection(
    val fields: List<FieldReference>
)

@Serializable
internal data class FieldReference(
    val fieldPath: String
)

@Serializable
internal data class Filter(
    val compositeFilter: CompositeFilter? = null,
    val fieldFilter: FieldFilter? = null,
    val unaryFilter: UnaryFilter? = null
)

@Serializable
internal data class CompositeFilter(
    val op: String, // "AND" or "OR"
    val filters: List<Filter>
)

@Serializable
internal data class FieldFilter(
    val field: FieldReference,
    val op: String,
    val value: FirestoreValue
)

@Serializable
internal data class UnaryFilter(
    val op: String, // "IS_NULL" or "IS_NOT_NULL"
    val field: FieldReference
)

@Serializable
internal data class Order(
    val field: FieldReference,
    val direction: String // "ASCENDING" or "DESCENDING"
)

@Serializable
internal data class Document(
    val name: String? = null,
    val fields: Map<String, FirestoreValue>
)

@Serializable
internal data class FirestoreValue(
    val nullValue: String? = null,
    val booleanValue: Boolean? = null,
    val integerValue: String? = null, // Firestore uses string for integers
    val doubleValue: Double? = null,
    val stringValue: String? = null,
    val arrayValue: ArrayValue? = null,
    val mapValue: MapValue? = null
)

@Serializable
internal data class ArrayValue(
    val values: List<FirestoreValue>
)

@Serializable
internal data class MapValue(
    val fields: Map<String, FirestoreValue>
)
