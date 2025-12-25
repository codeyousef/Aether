package codes.yousef.aether.ui

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.CsrfProtection
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
/**
 * Create a hidden input field for CSRF token.
 * @param token The CSRF token value
 * @param name The field name (defaults to "csrftoken")
 */
fun ComposableScope.csrfInput(token: String, name: String = "csrftoken") {
    input(type = "hidden", name = name, attributes = mapOf("value" to token))
}

/**
 * Create a hidden input field for CSRF token using the token from the Exchange.
 * @param exchange The current Exchange
 * @param name The field name (defaults to "csrftoken")
 */
fun ComposableScope.csrfToken(exchange: Exchange, name: String = "csrftoken") {
    val token = exchange.attributes.get(CsrfProtection.CsrfTokenKey) ?: ""
    csrfInput(token, name)
}

/**
 * Create a textarea element.
 */
fun ComposableScope.textarea(name: String? = null, attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    val attrs = buildMap {
        name?.let { put("name", it) }
        putAll(attributes)
    }
    element("textarea", attrs, content)
}

/**
 * Create a label element.
 */
fun ComposableScope.label(forId: String? = null, attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    val attrs = buildMap {
        forId?.let { put("for", it) }
        putAll(attributes)
    }
    element("label", attrs, content)
}

/**
 * Create a select element.
 */
fun ComposableScope.select(name: String? = null, attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    val attrs = buildMap {
        name?.let { put("name", it) }
        putAll(attributes)
    }
    element("select", attrs, content)
}

/**
 * Create an option element.
 */
fun ComposableScope.option(value: String, selected: Boolean = false, attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    val attrs = buildMap {
        put("value", value)
        if (selected) put("selected", "selected")
        putAll(attributes)
    }
    element("option", attrs, content)
}

/**
 * Create a table element.
 */
fun ComposableScope.table(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("table", attributes, content)
}

/**
 * Create a table row.
 */
fun ComposableScope.tr(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("tr", attributes, content)
}

/**
 * Create a table header cell.
 */
fun ComposableScope.th(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("th", attributes, content)
}

/**
 * Create a table data cell.
 */
fun ComposableScope.td(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("td", attributes, content)
}

/**
 * Create a thead element.
 */
fun ComposableScope.thead(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("thead", attributes, content)
}

/**
 * Create a tbody element.
 */
fun ComposableScope.tbody(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("tbody", attributes, content)
}

/**
 * Create an img element.
 */
fun ComposableScope.img(src: String, alt: String = "", attributes: Map<String, String> = emptyMap()) {
    val attrs = buildMap {
        put("src", src)
        put("alt", alt)
        putAll(attributes)
    }
    element("img", attrs)
}

/**
 * Create a link element (for stylesheets, etc.).
 */
fun ComposableScope.link(rel: String, href: String, attributes: Map<String, String> = emptyMap()) {
    val attrs = buildMap {
        put("rel", rel)
        put("href", href)
        putAll(attributes)
    }
    element("link", attrs)
}

/**
 * Create a script element.
 */
fun ComposableScope.script(src: String? = null, attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    val attrs = buildMap {
        src?.let { put("src", it) }
        putAll(attributes)
    }
    element("script", attrs, content)
}

/**
 * Create a meta element.
 */
fun ComposableScope.meta(attributes: Map<String, String>) {
    element("meta", attributes)
}

/**
 * Create a nav element.
 */
fun ComposableScope.nav(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("nav", attributes, content)
}

/**
 * Create a header element.
 */
fun ComposableScope.header(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("header", attributes, content)
}

/**
 * Create a footer element.
 */
fun ComposableScope.footer(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("footer", attributes, content)
}

/**
 * Create a main element.
 */
fun ComposableScope.main(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("main", attributes, content)
}

/**
 * Create a section element.
 */
fun ComposableScope.section(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("section", attributes, content)
}

/**
 * Create an article element.
 */
fun ComposableScope.article(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("article", attributes, content)
}

/**
 * Create an aside element.
 */
fun ComposableScope.aside(attributes: Map<String, String> = emptyMap(), content: ComposableScope.() -> Unit = {}) {
    element("aside", attributes, content)
}