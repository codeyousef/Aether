package codes.yousef.aether.web

/**
 * A Radix Tree implementation for efficient route matching with O(k) complexity,
 * where k is the length of the path being matched.
 *
 * Supports:
 * - Static paths: /users/list
 * - Path parameters: /users/:id, /users/:userId/posts/:postId
 * - Proper prefix splitting and node merging
 */
class RadixTree<T> {
    private val root = RadixNode<T>()

    /**
     * Insert a route pattern into the tree with an associated value.
     *
     * @param path The route pattern (e.g., "/users/:id")
     * @param value The value to associate with this route
     */
    fun insert(path: String, value: T) {
        val normalizedPath = normalizePath(path)
        if (normalizedPath.isEmpty()) {
            root.value = value
            return
        }

        var current = root
        var remaining = normalizedPath

        while (remaining.isNotEmpty()) {
            val segment = extractNextSegment(remaining)
            val isParam = segment.startsWith(':')

            if (isParam) {
                val paramName = segment.substring(1)
                if (current.paramChild == null) {
                    current.paramChild = RadixNode(paramName = paramName)
                }
                current = current.paramChild!!
                remaining = remaining.substring(segment.length)
                if (remaining.startsWith("/")) {
                    remaining = remaining.substring(1)
                }
            } else {
                val matchedChild = current.children.entries.firstOrNull { (prefix, _) ->
                    segment.startsWith(prefix) || prefix.startsWith(segment)
                }

                if (matchedChild != null) {
                    val (prefix, child) = matchedChild
                    val commonPrefixLen = commonPrefixLength(segment, prefix)

                    if (commonPrefixLen < prefix.length) {
                        val splitNode = RadixNode<T>()
                        val oldSuffix = prefix.substring(commonPrefixLen)
                        val newPrefix = prefix.substring(0, commonPrefixLen)

                        splitNode.children[oldSuffix] = child
                        splitNode.value = null
                        splitNode.paramChild = null

                        current.children.remove(prefix)
                        current.children[newPrefix] = splitNode

                        if (commonPrefixLen < segment.length) {
                            val remainingSuffix = segment.substring(commonPrefixLen)
                            val newChild = RadixNode<T>()
                            splitNode.children[remainingSuffix] = newChild
                            current = newChild
                        } else {
                            current = splitNode
                        }
                        remaining = remaining.substring(segment.length)
                    } else if (commonPrefixLen == prefix.length && segment.length > prefix.length) {
                        current = child
                        remaining = segment.substring(prefix.length) + remaining.substring(segment.length)
                    } else {
                        current = child
                        remaining = remaining.substring(segment.length)
                    }
                } else {
                    val newChild = RadixNode<T>()
                    current.children[segment] = newChild
                    current = newChild
                    remaining = remaining.substring(segment.length)
                }

                if (remaining.startsWith("/")) {
                    remaining = remaining.substring(1)
                }
            }
        }

        current.value = value
    }

    /**
     * Search for a route in the tree that matches the given path.
     *
     * @param path The path to match (e.g., "/users/123")
     * @return RouteMatch with the value and extracted parameters, or null if no match
     */
    fun search(path: String): RouteMatch<T>? {
        val normalizedPath = normalizePath(path)
        if (normalizedPath.isEmpty()) {
            return root.value?.let { RouteMatch(it, emptyMap()) }
        }

        val params = mutableMapOf<String, String>()
        var current = root
        var remaining = normalizedPath

        while (remaining.isNotEmpty()) {
            val segment = extractNextSegment(remaining)

            val staticMatch = current.children.entries.firstOrNull { (prefix, _) ->
                segment.startsWith(prefix)
            }

            if (staticMatch != null) {
                val (prefix, child) = staticMatch
                current = child
                remaining = segment.substring(prefix.length) + remaining.substring(segment.length)
                if (remaining.startsWith("/")) {
                    remaining = remaining.substring(1)
                }
            } else if (current.paramChild != null) {
                val paramChild = current.paramChild!!
                val paramValue = segment
                params[paramChild.paramName!!] = paramValue
                current = paramChild
                remaining = remaining.substring(segment.length)
                if (remaining.startsWith("/")) {
                    remaining = remaining.substring(1)
                }
            } else {
                return null
            }
        }

        return current.value?.let { RouteMatch(it, params) }
    }

    /**
     * Normalize a path by removing trailing slashes and ensuring it starts with a slash.
     */
    private fun normalizePath(path: String): String {
        var normalized = path.trim()
        if (normalized.isEmpty() || normalized == "/") {
            return ""
        }
        if (!normalized.startsWith("/")) {
            normalized = "/$normalized"
        }
        if (normalized.endsWith("/") && normalized.length > 1) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized.substring(1)
    }

    /**
     * Extract the next segment from the path (up to the next slash or end).
     */
    private fun extractNextSegment(path: String): String {
        val slashIndex = path.indexOf('/')
        return if (slashIndex == -1) path else path.substring(0, slashIndex)
    }

    /**
     * Calculate the length of the common prefix between two strings.
     */
    private fun commonPrefixLength(s1: String, s2: String): Int {
        val minLen = minOf(s1.length, s2.length)
        for (i in 0 until minLen) {
            if (s1[i] != s2[i]) {
                return i
            }
        }
        return minLen
    }
}

/**
 * A node in the Radix Tree.
 *
 * @property value The value associated with this node (if it's a terminal node)
 * @property children Map of edge labels to child nodes
 * @property paramChild The child node for parameter segments (e.g., :id)
 * @property paramName The name of the parameter (if this is a parameter node)
 */
internal class RadixNode<T>(
    var value: T? = null,
    val children: MutableMap<String, RadixNode<T>> = mutableMapOf(),
    var paramChild: RadixNode<T>? = null,
    var paramName: String? = null
)

/**
 * Result of a successful route match.
 *
 * @property value The value associated with the matched route
 * @property params Map of parameter names to their extracted values
 */
data class RouteMatch<T>(
    val value: T,
    val params: Map<String, String>
)
