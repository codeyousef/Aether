# Quickstart Guide

Get started with Aether Framework in minutes.

## Prerequisites

*   JDK 21 or later
*   IntelliJ IDEA (Community or Ultimate)
*   Docker (optional, for local database)

## Creating a New Project

You can create a new Aether project using the Gradle plugin or by cloning the template.

### Using Gradle

1.  Create a new Kotlin Multiplatform project.
2.  Apply the Aether plugin in your `build.gradle.kts`:

```kotlin
plugins {
    id("codes.yousef.aether.plugin") version "0.1.0"
}

aether {
    jvmPort = 8080
    enableHotReload = true
}
```

3.  Run the initialization task:

```bash
./gradlew aetherInit
```

## Your First Application

Aether applications start with a `main` function that configures the server.

`src/commonMain/kotlin/Main.kt`:

```kotlin
import codes.yousef.aether.core.server
import codes.yousef.aether.web.router

fun main() {
    val appRouter = router {
        get("/") { exchange ->
            exchange.respond(body = "Hello, Aether!")
        }
        
        get("/hello/:name") { exchange ->
            val name = exchange.pathParam("name")
            exchange.respond(body = "Hello, $name!")
        }
    }

    server {
        port = 8080
        router(appRouter)
    }.start()
}
```

## Running the Application

### JVM (Local Development)

Run the application on the JVM with hot reload enabled (if configured):

```bash
./gradlew aetherRunJvm
```

Visit `http://localhost:8080` to see your app.

### Wasm (Experimental)

To run the application in a Wasm environment (e.g., Node.js or browser-based runtime):

```bash
./gradlew aetherRunWasm
```

## Next Steps

*   Explore [Routing](api-reference/routing.md) to build your API.
*   Set up a [Database](api-reference/database.md) with models.
*   Build a UI with the [Composable DSL](api-reference/ui.md).
