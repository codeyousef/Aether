# UI API

The `aether-ui` module provides a type-safe Kotlin DSL for building HTML user interfaces. It is designed for Server-Side Rendering (SSR) and can serialize the UI tree to CBOR for efficient transport.

## Composable DSL

The DSL uses the `ComposableScope` to build a tree of `UiNode` elements.

### Basic Structure

```kotlin
exchange.render {
    html {
        head {
            title { text("My Aether App") }
        }
        body {
            div(mapOf("class" to "container")) {
                h1 { text("Welcome") }
                p { text("This is rendered on the server.") }
            }
        }
    }
}
```

### Core Components

#### `element(tag: String, attributes: Map<String, String>, content: ComposableScope.() -> Unit)`
Creates a generic HTML element.

#### `text(content: String)`
Creates a text node.

### Helper Functions

The DSL provides helper functions for standard HTML tags:
*   `html`, `head`, `body`
*   `div`, `span`, `p`, `h1`...`h6`
*   `a`, `button`, `input`, `form`
*   `ul`, `ol`, `li`
*   `table`, `tr`, `td`, `th`

### Serialization

The UI tree is built using `UiNode` classes, which are marked as `@Serializable`. This allows the entire UI structure to be serialized (e.g., to JSON or CBOR) and sent to the client, enabling "UI over the wire" architectures or hydration on the client side.

```kotlin
@Serializable
sealed class UiNode {
    data class Element(...) : UiNode()
    data class Text(...) : UiNode()
}
```
