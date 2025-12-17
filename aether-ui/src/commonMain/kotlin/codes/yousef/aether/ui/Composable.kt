package codes.yousef.aether.ui

import kotlinx.serialization.Serializable

/**
 * DSL marker to ensure proper scope for composable functions.
 */
@DslMarker
annotation class ComposableDsl

/**
 * Scope for building UI components.
 * Provides methods to add elements and text nodes to the UI tree.
 */
@ComposableDsl
interface ComposableScope {
    /**
     * Add a UI element with the given tag name.
     */
    fun element(tag: String, attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {})

    /**
     * Add a text node.
     */
    fun text(content: String)
}

/**
 * Represents a node in the UI tree.
 * Can be either an Element or Text node.
 */
@Serializable
sealed class UiNode {
    /**
     * An HTML element with a tag, attributes, and children.
     */
    @Serializable
    data class Element(
        val tag: String,
        val attributes: Map<String, String> = emptyMap(),
        val children: List<UiNode> = emptyList()
    ) : UiNode()

    /**
     * A text node with content.
     */
    @Serializable
    data class Text(val content: String) : UiNode()
}

/**
 * Builder for constructing UI node trees.
 */
class UiTreeBuilder : ComposableScope {
    private val nodes = mutableListOf<UiNode>()

    override fun element(tag: String, attributes: Map<String, String>, content: ComposableScope.() -> Unit) {
        val builder = UiTreeBuilder()
        builder.content()
        nodes.add(UiNode.Element(tag, attributes, builder.build()))
    }

    override fun text(content: String) {
        nodes.add(UiNode.Text(content))
    }

    /**
     * Build the list of UI nodes.
     */
    fun build(): List<UiNode> = nodes.toList()
}

// DSL functions for common HTML elements

/**
 * Create an HTML document structure.
 */
fun html(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit): List<UiNode> {
    val builder = UiTreeBuilder()
    builder.element("html", attributes, content)
    return builder.build()
}

/**
 * Create a head element.
 */
fun ComposableScope.head(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("head", attributes, content)
}

/**
 * Create a body element.
 */
fun ComposableScope.body(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("body", attributes, content)
}

/**
 * Create a div element.
 */
fun ComposableScope.div(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("div", attributes, content)
}

/**
 * Create an h1 heading.
 */
fun ComposableScope.h1(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("h1", attributes, content)
}

/**
 * Create an h2 heading.
 */
fun ComposableScope.h2(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("h2", attributes, content)
}

/**
 * Create an h3 heading.
 */
fun ComposableScope.h3(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("h3", attributes, content)
}

/**
 * Create a paragraph element.
 */
fun ComposableScope.p(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("p", attributes, content)
}

/**
 * Create an anchor element.
 */
fun ComposableScope.a(href: String, attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("a", attributes + ("href" to href), content)
}

/**
 * Create a button element.
 */
fun ComposableScope.button(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("button", attributes, content)
}

/**
 * Create an input element.
 */
fun ComposableScope.input(type: String = "text", name: String? = null, attributes: Map<String, String> = emptyMap()) {
    val attrs = buildMap {
        put("type", type)
        name?.let { put("name", it) }
        putAll(attributes)
    }
    element("input", attrs)
}

/**
 * Create a form element.
 */
fun ComposableScope.form(action: String? = null, method: String = "post", attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    val attrs = buildMap {
        action?.let { put("action", it) }
        put("method", method)
        putAll(attributes)
    }
    element("form", attrs, content)
}

/**
 * Create an unordered list.
 */
fun ComposableScope.ul(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("ul", attributes, content)
}

/**
 * Create a list item.
 */
fun ComposableScope.li(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("li", attributes, content)
}

/**
 * Create a span element.
 */
fun ComposableScope.span(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("span", attributes, content)
}

/**
 * Create a title element.
 */
fun ComposableScope.title(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("title", attributes, content)
}
