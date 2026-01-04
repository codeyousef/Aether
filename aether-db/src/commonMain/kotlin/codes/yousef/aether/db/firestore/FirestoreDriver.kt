package codes.yousef.aether.db.firestore

import codes.yousef.aether.db.*
import codes.yousef.aether.db.http.HttpClient
import codes.yousef.aether.db.http.HttpException
import kotlinx.serialization.json.*

/**
 * Firestore database driver that uses the Firestore REST API.
 * 
 * This driver allows using the Aether ORM with Google Firestore as the backend.
 * It translates QueryAST to Firestore REST API requests.
 * 
 * **Important Limitations:**
 * - Firestore is a NoSQL database, not all SQL operations are supported
 * - JOINs are not supported (use denormalized data or subcollections)
 * - LIKE/pattern matching not supported (use full-text search solutions)
 * - Complex subqueries not supported
 * - DDL operations not applicable (schemaless)
 * 
 * Example usage:
 * ```kotlin
 * val driver = FirestoreDriver.create(
 *     projectId = "your-project-id",
 *     apiKey = "your-api-key" // or use service account
 * )
 * DatabaseDriverRegistry.initialize(driver)
 * 
 * // Now use your models normally
 * val users = User.objects.filter { it.active eq true }.toList()
 * ```
 * 
 * @param projectId The Google Cloud project ID
 * @param apiKey The Firebase API key (for client-side auth)
 * @param accessToken OAuth2 access token (for service account auth, takes precedence over apiKey)
 * @param databaseId The Firestore database ID (default: "(default)")
 * @param baseUrlOverride Optional override for the base URL (for testing)
 */
class FirestoreDriver internal constructor(
    private val projectId: String,
    private val apiKey: String? = null,
    private val accessToken: String? = null,
    private val databaseId: String = "(default)",
    baseUrlOverride: String? = null
) : DatabaseDriver {
    
    private val httpClient = HttpClient()
    private val baseUrl = baseUrlOverride 
        ?: "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents"
    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
        encodeDefaults = false
    }

    init {
        require(apiKey != null || accessToken != null) {
            "Either apiKey or accessToken must be provided"
        }
    }

    private fun buildHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
        
        if (accessToken != null) {
            headers["Authorization"] = "Bearer $accessToken"
        } else if (apiKey != null) {
            // API key is typically added as query param, but can be header too
            headers["X-Goog-Api-Key"] = apiKey
        }
        
        return headers
    }

    override suspend fun executeQuery(query: QueryAST): List<Row> {
        val firestoreQuery = FirestoreTranslator.translate(query)
        val url = buildQueryUrl(firestoreQuery)
        val headers = buildHeaders()

        try {
            val response = when (firestoreQuery.method) {
                FirestoreQuery.HttpMethod.POST -> 
                    httpClient.post(url, firestoreQuery.body ?: "{}", headers)
                FirestoreQuery.HttpMethod.GET ->
                    httpClient.get(url, headers)
                else -> throw DatabaseException("Unexpected method for query: ${firestoreQuery.method}")
            }

            if (!response.isSuccessful) {
                throw DatabaseException("Firestore query failed: ${response.statusCode} - ${response.body}")
            }

            return parseQueryResponse(response.body)
        } catch (e: HttpException) {
            throw DatabaseException("HTTP error executing Firestore query: ${e.message}", e)
        } catch (e: UnsupportedOperationException) {
            throw DatabaseException("Unsupported operation: ${e.message}", e)
        }
    }

    override suspend fun executeQueryRaw(sql: String): List<Row> {
        throw DatabaseException("Raw SQL queries are not supported in Firestore")
    }

    override suspend fun executeUpdate(query: QueryAST): Int {
        val firestoreQuery = FirestoreTranslator.translate(query)
        val url = buildMutationUrl(firestoreQuery)
        val headers = buildHeaders()

        try {
            val response = when (firestoreQuery.method) {
                FirestoreQuery.HttpMethod.POST -> 
                    httpClient.post(url, firestoreQuery.body ?: "{}", headers)
                FirestoreQuery.HttpMethod.PATCH -> {
                    val patchUrl = buildPatchUrl(firestoreQuery)
                    httpClient.patch(patchUrl, firestoreQuery.body ?: "{}", headers)
                }
                FirestoreQuery.HttpMethod.DELETE ->
                    httpClient.delete(url, headers)
                else -> throw DatabaseException("Unexpected method for update: ${firestoreQuery.method}")
            }

            if (!response.isSuccessful) {
                throw DatabaseException("Firestore update failed: ${response.statusCode} - ${response.body}")
            }

            // Firestore doesn't return affected count, assume 1 for single-document operations
            return 1
        } catch (e: HttpException) {
            throw DatabaseException("HTTP error executing Firestore update: ${e.message}", e)
        } catch (e: UnsupportedOperationException) {
            throw DatabaseException("Unsupported operation: ${e.message}", e)
        }
    }

    override suspend fun executeDDL(query: QueryAST) {
        throw DatabaseException(
            "DDL operations are not applicable to Firestore. " +
            "Firestore is schemaless - collections are created automatically."
        )
    }

    override suspend fun getTables(): List<String> {
        // Firestore doesn't have a direct way to list all collections via REST
        // The Admin SDK supports this, but REST API does not
        throw DatabaseException(
            "Listing collections is not supported via Firestore REST API. " +
            "Use Firebase Admin SDK for collection listing."
        )
    }

    override suspend fun getColumns(table: String): List<ColumnDefinition> {
        throw DatabaseException(
            "Firestore is schemaless - documents can have different fields. " +
            "Column introspection is not applicable."
        )
    }

    override suspend fun execute(sql: String, params: List<SqlValue>): Int {
        throw DatabaseException("Raw SQL execution is not supported in Firestore")
    }

    override suspend fun close() {
        httpClient.close()
    }

    /**
     * Gets a single document by ID.
     * 
     * @param collection The collection name
     * @param documentId The document ID
     * @return The document as a Row, or null if not found
     */
    suspend fun getDocument(collection: String, documentId: String): Row? {
        val url = "$baseUrl/$collection/$documentId"
        val headers = buildHeaders()

        try {
            val response = httpClient.get(url, headers)
            
            if (response.statusCode == 404) {
                return null
            }
            
            if (!response.isSuccessful) {
                throw DatabaseException("Firestore get document failed: ${response.statusCode}")
            }

            val document = json.parseToJsonElement(response.body).jsonObject
            return parseDocument(document)
        } catch (e: HttpException) {
            throw DatabaseException("HTTP error getting document: ${e.message}", e)
        }
    }

    /**
     * Creates a document with a specific ID.
     * 
     * @param collection The collection name
     * @param documentId The document ID
     * @param data The document data
     * @return The created document ID
     */
    suspend fun createDocument(
        collection: String,
        documentId: String? = null,
        data: Map<String, Any?>
    ): String {
        val fields = data.mapValues { (_, v) -> anyToFirestoreValue(v) }
        val document = buildJsonObject {
            put("fields", buildJsonObject {
                fields.forEach { (k, v) -> put(k, v) }
            })
        }
        
        val url = if (documentId != null) {
            "$baseUrl/$collection?documentId=$documentId"
        } else {
            "$baseUrl/$collection"
        }
        
        val headers = buildHeaders()
        val response = httpClient.post(url, document.toString(), headers)
        
        if (!response.isSuccessful) {
            throw DatabaseException("Firestore create document failed: ${response.statusCode} - ${response.body}")
        }
        
        val result = json.parseToJsonElement(response.body).jsonObject
        val name = result["name"]?.jsonPrimitive?.content ?: ""
        return name.substringAfterLast("/")
    }

    private fun buildQueryUrl(query: FirestoreQuery): String {
        return if (query.body?.contains("structuredQuery") == true) {
            "$baseUrl:runQuery${buildQueryParams(query.queryParams)}"
        } else {
            "$baseUrl/${query.collection}${buildQueryParams(query.queryParams)}"
        }
    }

    private fun buildMutationUrl(query: FirestoreQuery): String {
        return if (query.documentId != null) {
            "$baseUrl/${query.collection}/${query.documentId}${buildQueryParams(query.queryParams)}"
        } else {
            "$baseUrl/${query.collection}${buildQueryParams(query.queryParams)}"
        }
    }

    private fun buildPatchUrl(query: FirestoreQuery): String {
        val baseUrl = "$baseUrl/${query.collection}/${query.documentId}"
        val params = query.queryParams.toMutableMap()
        return "$baseUrl${buildQueryParams(params)}"
    }

    private fun buildQueryParams(params: Map<String, String>): String {
        val queryParams = if (params.isNotEmpty()) {
            params.entries.joinToString("&") { (k, v) -> 
                "${encodeURIComponent(k)}=${encodeURIComponent(v)}" 
            }
        } else {
            ""
        }
        
        // Add API key as query param if using API key auth
        return when {
            apiKey != null && accessToken == null && queryParams.isNotEmpty() -> 
                "?key=$apiKey&$queryParams"
            apiKey != null && accessToken == null -> 
                "?key=$apiKey"
            queryParams.isNotEmpty() -> 
                "?$queryParams"
            else -> 
                ""
        }
    }

    private fun encodeURIComponent(s: String): String {
        return s.replace(" ", "%20")
            .replace("!", "%21")
            .replace("#", "%23")
            .replace("$", "%24")
            .replace("&", "%26")
            .replace("'", "%27")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("*", "%2A")
            .replace("+", "%2B")
            .replace(",", "%2C")
            .replace("/", "%2F")
            .replace(":", "%3A")
            .replace(";", "%3B")
            .replace("=", "%3D")
            .replace("?", "%3F")
            .replace("@", "%40")
            .replace("[", "%5B")
            .replace("]", "%5D")
    }

    private fun parseQueryResponse(responseBody: String): List<Row> {
        if (responseBody.isBlank()) return emptyList()
        
        try {
            val element = json.parseToJsonElement(responseBody)
            
            // runQuery returns an array of objects with "document" field
            if (element is JsonArray) {
                return element.mapNotNull { item ->
                    val obj = item.jsonObject
                    val document = obj["document"]?.jsonObject
                    if (document != null) {
                        parseDocument(document)
                    } else {
                        null
                    }
                }
            }
            
            // Single document response
            if (element is JsonObject && element.containsKey("fields")) {
                return listOf(parseDocument(element))
            }
            
            // List documents response
            if (element is JsonObject && element.containsKey("documents")) {
                val documents = element["documents"]?.jsonArray ?: return emptyList()
                return documents.map { doc -> parseDocument(doc.jsonObject) }
            }
            
            return emptyList()
        } catch (e: Exception) {
            throw DatabaseException("Failed to parse Firestore response: ${e.message}", e)
        }
    }

    private fun parseDocument(document: JsonObject): FirestoreRow {
        val fields = document["fields"]?.jsonObject ?: return FirestoreRow(emptyMap())
        val data = mutableMapOf<String, Any?>()
        
        // Extract document ID from name
        document["name"]?.jsonPrimitive?.content?.let { name ->
            data["id"] = name.substringAfterLast("/")
            data["_id"] = data["id"]
        }
        
        fields.forEach { (key, value) ->
            data[key] = firestoreValueToAny(value.jsonObject)
        }
        
        return FirestoreRow(data)
    }

    private fun firestoreValueToAny(value: JsonObject): Any? {
        return when {
            value.containsKey("nullValue") -> null
            value.containsKey("booleanValue") -> value["booleanValue"]?.jsonPrimitive?.boolean
            value.containsKey("integerValue") -> value["integerValue"]?.jsonPrimitive?.content?.toLongOrNull()
            value.containsKey("doubleValue") -> value["doubleValue"]?.jsonPrimitive?.double
            value.containsKey("stringValue") -> value["stringValue"]?.jsonPrimitive?.content
            value.containsKey("timestampValue") -> value["timestampValue"]?.jsonPrimitive?.content
            value.containsKey("arrayValue") -> {
                val arrayValue = value["arrayValue"]?.jsonObject
                arrayValue?.get("values")?.jsonArray?.map { 
                    firestoreValueToAny(it.jsonObject) 
                } ?: emptyList<Any?>()
            }
            value.containsKey("mapValue") -> {
                val mapValue = value["mapValue"]?.jsonObject
                val fields = mapValue?.get("fields")?.jsonObject ?: return emptyMap<String, Any?>()
                fields.entries.associate { (k, v) -> k to firestoreValueToAny(v.jsonObject) }
            }
            value.containsKey("geoPointValue") -> {
                val geo = value["geoPointValue"]?.jsonObject
                mapOf(
                    "latitude" to geo?.get("latitude")?.jsonPrimitive?.double,
                    "longitude" to geo?.get("longitude")?.jsonPrimitive?.double
                )
            }
            value.containsKey("referenceValue") -> value["referenceValue"]?.jsonPrimitive?.content
            else -> null
        }
    }

    private fun anyToFirestoreValue(value: Any?): JsonObject {
        return when (value) {
            null -> buildJsonObject { put("nullValue", JsonNull) }
            is Boolean -> buildJsonObject { put("booleanValue", value) }
            is Int -> buildJsonObject { put("integerValue", value.toString()) }
            is Long -> buildJsonObject { put("integerValue", value.toString()) }
            is Float -> buildJsonObject { put("doubleValue", value.toDouble()) }
            is Double -> buildJsonObject { put("doubleValue", value) }
            is String -> buildJsonObject { put("stringValue", value) }
            is List<*> -> buildJsonObject {
                put("arrayValue", buildJsonObject {
                    put("values", buildJsonArray {
                        value.forEach { add(anyToFirestoreValue(it)) }
                    })
                })
            }
            is Map<*, *> -> buildJsonObject {
                put("mapValue", buildJsonObject {
                    put("fields", buildJsonObject {
                        value.forEach { (k, v) ->
                            if (k is String) {
                                put(k, anyToFirestoreValue(v))
                            }
                        }
                    })
                })
            }
            else -> buildJsonObject { put("stringValue", value.toString()) }
        }
    }

    companion object {
        /**
         * Creates a FirestoreDriver with API key authentication.
         * Suitable for client-side applications with Firebase Auth.
         * 
         * @param projectId The Google Cloud project ID
         * @param apiKey The Firebase API key
         * @param databaseId The Firestore database ID (default: "(default)")
         */
        fun create(
            projectId: String,
            apiKey: String,
            databaseId: String = "(default)"
        ): FirestoreDriver {
            return FirestoreDriver(
                projectId = projectId,
                apiKey = apiKey,
                databaseId = databaseId
            )
        }

        /**
         * Creates a FirestoreDriver with OAuth2 access token.
         * Suitable for server-side applications with service account auth.
         * 
         * @param projectId The Google Cloud project ID
         * @param accessToken The OAuth2 access token
         * @param databaseId The Firestore database ID (default: "(default)")
         */
        fun createWithToken(
            projectId: String,
            accessToken: String,
            databaseId: String = "(default)"
        ): FirestoreDriver {
            return FirestoreDriver(
                projectId = projectId,
                accessToken = accessToken,
                databaseId = databaseId
            )
        }

        /**
         * Creates a FirestoreDriver with a custom base URL for testing.
         * @internal For testing purposes only.
         */
        internal fun createForTesting(
            projectId: String,
            apiKey: String,
            baseUrl: String
        ): FirestoreDriver {
            return FirestoreDriver(
                projectId = projectId,
                apiKey = apiKey,
                baseUrlOverride = baseUrl
            )
        }
    }
}

/**
 * Row implementation for Firestore query results.
 */
class FirestoreRow(
    private val data: Map<String, Any?>
) : Row {

    override fun getString(column: String): String? {
        return data[column]?.toString()
    }

    override fun getInt(column: String): Int? {
        return when (val v = data[column]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }

    override fun getLong(column: String): Long? {
        return when (val v = data[column]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    override fun getDouble(column: String): Double? {
        return when (val v = data[column]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    override fun getBoolean(column: String): Boolean? {
        return when (val v = data[column]) {
            is Boolean -> v
            is String -> v.lowercase() in listOf("true", "t", "1", "yes")
            is Number -> v.toInt() != 0
            else -> null
        }
    }

    override fun getValue(column: String): Any? {
        return data[column]
    }

    override fun getColumnNames(): List<String> {
        return data.keys.toList()
    }

    override fun hasColumn(column: String): Boolean {
        return data.containsKey(column)
    }
}
