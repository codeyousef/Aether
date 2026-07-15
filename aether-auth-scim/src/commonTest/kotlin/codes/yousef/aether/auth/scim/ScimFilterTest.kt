package codes.yousef.aether.auth.scim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScimFilterTest {
    @Test
    fun parsesRequiredEqualityFilter() {
        assertEquals(
            ScimEqualityFilter("username", "alice@example.test"),
            ScimFilterParser.parse("userName eq \"alice@example.test\"")
        )
        assertEquals(ScimEqualityFilter("active", "true"), ScimFilterParser.parse("active EQ true"))
    }

    @Test
    fun rejectsCompoundOrUnsupportedOperators() {
        assertFailsWith<ScimFilterException> { ScimFilterParser.parse("userName co \"alice\"") }
        assertFailsWith<ScimFilterException> {
            ScimFilterParser.parse("userName eq \"alice\" and active eq true")
        }
    }

    @Test
    fun paginationUsesOneBasedStartAndCapsCount() {
        val page = parsePage(mapOf("startIndex" to "2", "count" to "99"), maximumPageSize = 2)
        assertEquals(listOf(2, 3), page.apply(listOf(1, 2, 3, 4)))
    }
}
