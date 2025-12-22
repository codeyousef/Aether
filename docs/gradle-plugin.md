# Gradle Plugin

The `aether-plugin` simplifies project configuration and development tasks.

## Installation

Apply the plugin in your `build.gradle.kts`:

```kotlin
plugins {
    id("codes.yousef.aether.plugin") version "0.1.0"
}
```

## Configuration

Configure the plugin using the `aether` block:

```kotlin
aether {
    // The port for the JVM development server
    jvmPort = 8080
    
    // Enable hot reload (restarts server on class changes)
    enableHotReload = true
    
    // Wasm specific settings
    wasm {
        // ...
    }
}
```

## Tasks

| Task | Description |
| :--- | :--- |
| `aetherInit` | Initializes a new Aether project structure. |
| `aetherRunJvm` | Runs the application on the JVM with hot reload support. |
| `aetherRunWasm` | Runs the application in a Wasm environment (Node.js). |

## Multiplatform Setup

The plugin automatically configures the necessary Kotlin Multiplatform targets (JVM, WasmJS, WasmWASI) and source sets if they are not already defined. It also adds the core Aether dependencies.
