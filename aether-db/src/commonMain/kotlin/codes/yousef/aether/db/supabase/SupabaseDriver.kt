package codes.yousef.aether.db.supabase

import codes.yousef.aether.db.*
import codes.yousef.aether.db.http.HttpClient
import codes.yousef.aether.db.http.HttpException
import kotlinx.serialization.json.*

/**
 * Supabase database driver that uses the PostgREST API.
 * 
 * This driver allows using the Aether ORM with Supabase as the backend.
 * It translates QueryAST to PostgREST HTTP requests.
 * 
 * Example usage:
 * ```kotlin
 * val driver = SupabaseDriver.create(
 *     projectUrl = "https://your-project.supabase.co",
 *     apiKey = "your-anon-or-service-key"
 * )
 * DatabaseDriverRegistry.initialize(driver)
 * 
 * // Now use your models normally
 * val users = User.objects.filter { it.active eq true }.toList()
 * ```
 * 
 * @param projectUrl The Supabase project URL (e.g., https://xxx.supabase.co)
 * @param apiKey The Supabase API key (anon key for client, service key for server)
 * @param schema The PostgreSQL schema to use (default: "public")
 */
class SupabaseDriver(
    private val projectUrl: String,
    private val apiKey: String,
    private val schema: String = "public"
) : DatabaseDriver {
    
    private val httpClient = HttpClient()
    private val restUrl = "$projectUrl/rest/v1"
    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }

    private fun buildHeaders(extraHeaders: Map<String, String> = emptyMap()): Map<String, String> {
        return mapOf(
            "apikey" to apiKey,
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json",
            "Accept-Profile" to schema
        ) + extraHeaders
    }

    override suspend fun executeQuery(query: QueryAST): List<Row> {
        val supabaseQuery = SupabaseTranslator.translate(query)
        val url = supabaseQuery.buildUrl(restUrl)
        val headers = buildHeaders(supabaseQuery.headers)

        try {
            val response = when (supabaseQuery.method) {
                SupabaseQuery.HttpMethod.GET -> httpClient.get(url, headers)
                else -> throw DatabaseException("Unexpected method for query: ${supabaseQuery.method}")
            }

            if (!response.isSuccessful) {
                throw DatabaseException("Supabase query failed: ${response.statusCode} - ${response.body}")
            }

            return parseRows(response.body)
        } catch (e: HttpException) {
            throw DatabaseException("HTTP error executing Supabase query: ${e.message}", e)
        }
    }

    override suspend fun executeQueryRaw(sql: String): List<Row> {
        // Supabase supports raw SQL via the RPC endpoint or the SQL API
        // For simplicity, we'll use the /rpc endpoint if a function exists
        throw DatabaseException(
            "Raw SQL queries require using Supabase's SQL API or database functions. " +
            "Consider creating a database function and calling it via RPC."
        )
    }

    override suspend fun executeUpdate(query: QueryAST): Int {
        val supabaseQuery = SupabaseTranslator.translate(query)
        val url = supabaseQuery.buildUrl(restUrl)
        val headers = buildHeaders(supabaseQuery.headers + mapOf("Prefer" to "return=representation,count=exact"))

        try {
            val response = when (supabaseQuery.method) {
                SupabaseQuery.HttpMethod.POST -> 
                    httpClient.post(url, supabaseQuery.body ?: "{}", headers)
                SupabaseQuery.HttpMethod.PATCH -> 
                    httpClient.patch(url, supabaseQuery.body ?: "{}", headers)
                SupabaseQuery.HttpMethod.DELETE -> 
                    httpClient.delete(url, headers)
                else -> throw DatabaseException("Unexpected method for update: ${supabaseQuery.method}")
            }

            if (!response.isSuccessful) {
                throw DatabaseException("Supabase update failed: ${response.statusCode} - ${response.body}")
            }

            // Try to get count from Content-Range header or parse response
            val contentRange = response.headers["content-range"]
            if (contentRange != null) {
                // Format: "0-9/100" or "*/100"
                val match = Regex("\\*?/?([0-9]+)$").find(contentRange)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull() ?: parseRows(response.body).size
                }
            }

            // Fallback: count returned rows
            return parseRows(response.body).size
        } catch (e: HttpException) {
            throw DatabaseException("HTTP error executing Supabase update: ${e.message}", e)
        }
    }

    override suspend fun executeDDL(query: QueryAST) {
        throw DatabaseException(
            "DDL operations are not supported via Supabase REST API. " +
            "Use Supabase Dashboard, migrations, or direct database connection."
        )
    }

    override suspend fun getTables(): List<String> {
        // Supabase provides schema info via the OpenAPI spec endpoint
        val url = "$projectUrl/rest/v1/"
        val headers = buildHeaders()

        try {
            val response = httpClient.get(url, headers)
            if (!response.isSuccessful) {
                throw DatabaseException("Failed to get tables: ${response.statusCode}")
            }

            // Parse OpenAPI spec to extract table names
            val spec = json.parseToJsonElement(response.body)
            val paths = spec.jsonObject["paths"]?.jsonObject?.keys ?: emptySet()
            return paths.map { it.removePrefix("/") }.filter { it.isNotEmpty() && !it.contains("/") }
        } catch (e: Exception) {
            throw DatabaseException("Failed to retrieve tables from Supabase: ${e.message}", e)
        }
    }

    override suspend fun getColumns(table: String): List<ColumnDefinition> {
        // This would require parsing the OpenAPI spec for the table
        // For now, throw an exception as this is mainly used for migrations
        throw DatabaseException(
            "Column introspection is not fully supported via REST API. " +
            "Use Supabase Dashboard or direct database connection for schema inspection."
        )
    }

    override suspend fun execute(sql: String, params: List<SqlValue>): Int {
        throw DatabaseException(
            "Raw SQL execution is not supported via Supabase REST API. " +
            "Create a database function and use RPC instead."
        )
    }

    override suspend fun close() {
        httpClient.close()
    }

    /**
     * Calls a Supabase RPC (Remote Procedure Call) function.
     * 
     * @param functionName The name of the database function
     * @param params Parameters to pass to the function
     * @return The function result as a list of rows
     */
    suspend fun rpc(functionName: String, params: Map<String, Any?> = emptyMap()): List<Row> {
        val url = "$restUrl/rpc/$functionName"
        val headers = buildHeaders(mapOf("Content-Type" to "application/json"))
        val body = jsonMapToString(params)

        try {
            val response = httpClient.post(url, body, headers)
            if (!response.isSuccessful) {
                throw DatabaseException("Supabase RPC failed: ${response.statusCode} - ${response.body}")
            }
            return parseRows(response.body)
        } catch (e: HttpException) {
            throw DatabaseException("HTTP error calling Supabase RPC: ${e.message}", e)
        }
    }

    private fun parseRows(jsonBody: String): List<Row> {
        if (jsonBody.isBlank() || jsonBody == "[]" || jsonBody == "null") {
            return emptyList()
        }

        return try {
            val jsonElement = json.parseToJsonElement(jsonBody)
            when (jsonElement) {
                is JsonArray -> jsonElement.map { element ->
                    SupabaseRow(element.jsonObject.toMap().mapValues { (_, v) -> jsonValueToAny(v) })
                }
                is JsonObject -> listOf(
                    SupabaseRow(jsonElement.toMap().mapValues { (_, v) -> jsonValueToAny(v) })
                )
                else -> emptyList()
            }
        } catch (e: Exception) {
            throw DatabaseException("Failed to parse Supabase response: ${e.message}", e)
        }
    }

    private fun jsonValueToAny(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                element.intOrNull != null -> element.int
                element.longOrNull != null -> element.long
                element.doubleOrNull != null -> element.double
                else -> element.content
            }
            is JsonArray -> element.map { jsonValueToAny(it) }
            is JsonObject -> element.toMap().mapValues { (_, v) -> jsonValueToAny(v) }
        }
    }

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
            is List<*> -> "[${value.joinToString(",") { valueToJson(it) }}]"
            is Map<*, *> -> {
                val entries = value.entries.joinToString(",") { (k, v) ->
                    "\"$k\":${valueToJson(v)}"
                }
                "{$entries}"
            }
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

    companion object {
        /**
         * Creates a SupabaseDriver instance.
         * 
         * @param projectUrl The Supabase project URL (e.g., https://xxx.supabase.co)
         * @param apiKey The Supabase API key
         * @param schema The PostgreSQL schema (default: "public")
         */
        fun create(
            projectUrl: String,
            apiKey: String,
            schema: String = "public"
        ): SupabaseDriver {
            return SupabaseDriver(
                projectUrl = projectUrl.trimEnd('/'),
                apiKey = apiKey,
                schema = schema
            )
        }
    }
}

/**
 * Row implementation for Supabase query results.
 */
class SupabaseRow(
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
