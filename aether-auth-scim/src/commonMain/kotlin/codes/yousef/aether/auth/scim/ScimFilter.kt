package codes.yousef.aether.auth.scim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

data class ScimEqualityFilter(val attributePath: String, val value: String)

object ScimFilterParser {
    private val expression = Regex(
        pattern = "^([A-Za-z][A-Za-z0-9.:-]{0,199})\\s+eq\\s+(\"(?:[^\"\\\\]|\\\\.)*\"|true|false)$",
        option = RegexOption.IGNORE_CASE
    )
    private val stringJson = Json { isLenient = false }

    fun parse(value: String): ScimEqualityFilter {
        if (value.length > 2_048) throw ScimFilterException()
        val match = expression.matchEntire(value.trim()) ?: throw ScimFilterException()
        val raw = match.groupValues[2]
        val decoded = if (raw.startsWith('"')) {
            try {
                stringJson.parseToJsonElement(raw).jsonPrimitive.content
            } catch (_: Throwable) {
                throw ScimFilterException()
            }
        } else {
            raw.lowercase()
        }
        if (decoded.length > 1_024) throw ScimFilterException()
        return ScimEqualityFilter(match.groupValues[1].lowercase(), decoded)
    }
}

class ScimFilterException : IllegalArgumentException("Invalid SCIM filter")

internal data class ScimPage(val startIndex: Int, val count: Int) {
    fun <T> apply(values: List<T>): List<T> {
        if (count == 0) return emptyList()
        val offset = (startIndex - 1).coerceAtMost(values.size)
        return values.drop(offset).take(count)
    }
}

internal fun parsePage(query: Map<String, String>, maximumPageSize: Int): ScimPage {
    val unknown = query.keys - setOf("filter", "startIndex", "count")
    if (unknown.isNotEmpty()) throw ScimQueryException()
    val start = query["startIndex"]?.toIntOrNull() ?: 1
    val requested = query["count"]?.toIntOrNull() ?: minOf(100, maximumPageSize)
    if (start < 1 || requested < 0) throw ScimQueryException()
    return ScimPage(start, minOf(requested, maximumPageSize))
}

internal class ScimQueryException : IllegalArgumentException("Invalid SCIM query")
