package codes.yousef.aether.ui

/**
 * JVM implementation of HTML rendering using Server-Side Rendering (SSR).
 *
 * This implementation walks the UiNode tree and generates a complete HTML string.
 * It handles:
 * - DOCTYPE declaration
 * - Self-closing tags (br, hr, img, input, etc.)
 * - HTML entity escaping for text and attributes
 * - Hydration script injection at the end
 */
actual fun renderToHtml(tree: List<UiNode>): String {
    val builder = StringBuilder()
    builder.append("<!DOCTYPE html>\n")

    for (node in tree) {
        renderNode(node, builder)
    }

    return builder.toString()
}

/**
 * Self-closing HTML tags that don't need a closing tag.
 */
private val SELF_CLOSING_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr"
)

/**
 * Recursively render a UI node to HTML.
 */
private fun renderNode(node: UiNode, builder: StringBuilder) {
    when (node) {
        is UiNode.Element -> {
            // Opening tag
            builder.append("<${node.tag}")

            // Attributes
            if (node.attributes.isNotEmpty()) {
                for ((key, value) in node.attributes) {
                    builder.append(" $key=\"${escapeHtml(value)}\"")
                }
            }

            // Self-closing tags
            if (node.tag.lowercase() in SELF_CLOSING_TAGS && node.children.isEmpty()) {
                builder.append(" />")
            } else {
                builder.append(">")

                // Children
                for (child in node.children) {
                    renderNode(child, builder)
                }

                // Closing tag
                builder.append("</${node.tag}>")

                // Inject hydration script at the end of body
                if (node.tag.lowercase() == "body") {
                    builder.append("\n<script>console.log('Aether UI initialized');</script>")
                }
            }
        }
        is UiNode.Text -> {
            builder.append(escapeHtml(node.content))
        }
    }
}

/**
 * Escape HTML entities in text and attributes.
 * Converts special characters to their HTML entity equivalents.
 */
private fun escapeHtml(text: String): String {
    return buildString(text.length) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
}
