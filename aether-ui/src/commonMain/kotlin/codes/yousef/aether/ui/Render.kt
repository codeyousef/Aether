package codes.yousef.aether.ui

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.PreferredContentType
import codes.yousef.aether.core.pipeline.preferredContentType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * Render UI content and respond based on content negotiation.
 *
 * This function detects the preferred content type from the Exchange attributes
 * (set by ContentNegotiation middleware) and routes to either HTML SSR or CBOR
 * based on the Accept header.
 *
 * @param statusCode The HTTP status code (default: 200)
 * @param content The composable UI content to render
 */
suspend fun Exchange.render(statusCode: Int = 200, content: ComposableScope.() -> Unit) {
    // Build the UI tree
    val builder = UiTreeBuilder()
    builder.content()
    val tree = builder.build()

    // Detect content type from attributes
    val preferredType = preferredContentType()

    when (preferredType) {
        PreferredContentType.HTML -> {
            // SSR: Render to HTML string
            val html = renderToHtml(tree)
            respondHtml(statusCode, html)
        }
        PreferredContentType.CBOR -> {
            // UWW Mode: Serialize to CBOR
            val cbor = renderToCbor(tree)
            respondCbor(statusCode, cbor)
        }
        else -> {
            // Default to HTML for unknown types
            val html = renderToHtml(tree)
            respondHtml(statusCode, html)
        }
    }
}

/**
 * Platform-specific HTML rendering implementation.
 * JVM: Full SSR with proper HTML generation
 * Wasm: Simple implementation (SSR is primarily for JVM)
 */
expect fun renderToHtml(tree: List<UiNode>): String

/**
 * Serialize UI tree to CBOR format.
 * Used for UWW (Underground Web World) mode where the browser
 * receives the UI tree structure directly.
 */
@OptIn(ExperimentalSerializationApi::class)
fun renderToCbor(tree: List<UiNode>): ByteArray {
    return Cbor.encodeToByteArray(UiNode.serializer().list, tree)
}

/**
 * Helper to get the list serializer for UiNode.
 */
private val <T> kotlinx.serialization.KSerializer<T>.list: kotlinx.serialization.KSerializer<List<T>>
    get() = kotlinx.serialization.builtins.ListSerializer(this)
