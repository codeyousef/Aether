package codes.yousef.aether.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RadixTreeTest {

    @Test
    fun testSimpleStaticRoutes() {
        val tree = RadixTree<String>()
        tree.insert("/users", "users_list")
        tree.insert("/posts", "posts_list")

        val match1 = tree.search("/users")
        assertNotNull(match1)
        assertEquals("users_list", match1.value)
        assertEquals(0, match1.params.size)

        val match2 = tree.search("/posts")
        assertNotNull(match2)
        assertEquals("posts_list", match2.value)
    }

    @Test
    fun testSinglePathParameter() {
        val tree = RadixTree<String>()
        tree.insert("/users/:id", "user_detail")

        val match = tree.search("/users/123")
        assertNotNull(match)
        assertEquals("user_detail", match.value)
        assertEquals(1, match.params.size)
        assertEquals("123", match.params["id"])
    }

    @Test
    fun testMultiplePathParameters() {
        val tree = RadixTree<String>()
        tree.insert("/users/:userId/posts/:postId", "user_post_detail")

        val match = tree.search("/users/42/posts/99")
        assertNotNull(match)
        assertEquals("user_post_detail", match.value)
        assertEquals(2, match.params.size)
        assertEquals("42", match.params["userId"])
        assertEquals("99", match.params["postId"])
    }

    @Test
    fun testMixedStaticAndDynamic() {
        val tree = RadixTree<String>()
        tree.insert("/users", "users_list")
        tree.insert("/users/:id", "user_detail")
        tree.insert("/users/:id/posts", "user_posts")

        val match1 = tree.search("/users")
        assertNotNull(match1)
        assertEquals("users_list", match1.value)
        assertEquals(0, match1.params.size)

        val match2 = tree.search("/users/123")
        assertNotNull(match2)
        assertEquals("user_detail", match2.value)
        assertEquals("123", match2.params["id"])

        val match3 = tree.search("/users/456/posts")
        assertNotNull(match3)
        assertEquals("user_posts", match3.value)
        assertEquals("456", match3.params["id"])
    }

    @Test
    fun testCommonPrefixSplitting() {
        val tree = RadixTree<String>()
        tree.insert("/users/list", "users_list")
        tree.insert("/users/active", "users_active")

        val match1 = tree.search("/users/list")
        assertNotNull(match1)
        assertEquals("users_list", match1.value)

        val match2 = tree.search("/users/active")
        assertNotNull(match2)
        assertEquals("users_active", match2.value)
    }

    @Test
    fun testNotFound() {
        val tree = RadixTree<String>()
        tree.insert("/users", "users_list")

        val match = tree.search("/posts")
        assertNull(match)
    }

    @Test
    fun testRootRoute() {
        val tree = RadixTree<String>()
        tree.insert("/", "root")

        val match = tree.search("/")
        assertNotNull(match)
        assertEquals("root", match.value)
    }

    @Test
    fun testTrailingSlashNormalization() {
        val tree = RadixTree<String>()
        tree.insert("/users/", "users_list")

        val match = tree.search("/users")
        assertNotNull(match)
        assertEquals("users_list", match.value)
    }

    @Test
    fun testLongPaths() {
        val tree = RadixTree<String>()
        tree.insert("/api/v1/users/:userId/posts/:postId/comments/:commentId", "comment_detail")

        val match = tree.search("/api/v1/users/100/posts/200/comments/300")
        assertNotNull(match)
        assertEquals("comment_detail", match.value)
        assertEquals("100", match.params["userId"])
        assertEquals("200", match.params["postId"])
        assertEquals("300", match.params["commentId"])
    }

    @Test
    fun testStaticPrefersOverParameter() {
        val tree = RadixTree<String>()
        tree.insert("/users/:id", "user_detail")
        tree.insert("/users/admin", "admin_panel")

        val match1 = tree.search("/users/admin")
        assertNotNull(match1)
        assertEquals("admin_panel", match1.value)

        val match2 = tree.search("/users/123")
        assertNotNull(match2)
        assertEquals("user_detail", match2.value)
        assertEquals("123", match2.params["id"])
    }

    @Test
    fun testComplexScenario() {
        val tree = RadixTree<String>()
        tree.insert("/", "home")
        tree.insert("/about", "about")
        tree.insert("/users", "users_list")
        tree.insert("/users/:id", "user_detail")
        tree.insert("/users/:id/edit", "user_edit")
        tree.insert("/users/:userId/posts", "user_posts")
        tree.insert("/users/:userId/posts/:postId", "post_detail")
        tree.insert("/admin/users", "admin_users")

        assertEquals("home", tree.search("/")?.value)
        assertEquals("about", tree.search("/about")?.value)
        assertEquals("users_list", tree.search("/users")?.value)
        assertEquals("user_detail", tree.search("/users/1")?.value)
        assertEquals("user_edit", tree.search("/users/1/edit")?.value)
        assertEquals("user_posts", tree.search("/users/2/posts")?.value)
        assertEquals("post_detail", tree.search("/users/2/posts/3")?.value)
        assertEquals("admin_users", tree.search("/admin/users")?.value)
    }

    @Test
    fun testParameterWithSpecialCharacters() {
        val tree = RadixTree<String>()
        tree.insert("/users/:id", "user_detail")

        val match = tree.search("/users/abc-123_xyz")
        assertNotNull(match)
        assertEquals("user_detail", match.value)
        assertEquals("abc-123_xyz", match.params["id"])
    }
}
