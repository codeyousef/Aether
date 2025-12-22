# Architecture Overview

Aether is designed as a "Write Once, Deploy Anywhere" framework, leveraging Kotlin Multiplatform (KMP) to abstract platform-specific details while providing high-performance implementations for each target.

## Core Philosophy

1.  **Platform Abstraction**: The core logic (routing, middleware, ORM, UI) is pure Kotlin common code.
2.  **Native Performance**:
    *   **JVM**: Uses **Virtual Threads (Project Loom)** for high-throughput, blocking-style I/O that scales like non-blocking code. It sits on top of **Vert.x** for robust networking.
    *   **Wasm**: Uses the platform's event loop (Browser/Node.js/WASI) with coroutines for asynchronous operations.
3.  **Type Safety**: Everything from database queries to HTML generation is type-safe.

## Module Structure

*   **`aether-core`**: The brain. Contains the `Exchange`, `Pipeline`, and platform-agnostic `Dispatcher`.
*   **`aether-web`**: The router. Implements a Radix Tree for O(k) route matching.
*   **`aether-db`**: The data layer. A Django-style ORM that builds a Query AST.
*   **`aether-ui`**: The view layer. A Composable DSL for Server-Side Rendering (SSR).
*   **`aether-net`**: The transport layer. Abstracts TCP/UDP/WebSocket connections.

## The Request Lifecycle

1.  **Transport**: A request arrives via `TcpTransport` (JVM/Vert.x) or a Wasm fetch event.
2.  **Dispatcher**: The platform-specific dispatcher launches a coroutine (Virtual Thread on JVM).
3.  **Pipeline**: The request enters the middleware chain.
    *   Global middleware (Logging, Recovery, Auth).
4.  **Router**: The `Router` middleware matches the path and extracts parameters.
5.  **Handler**: The user-defined handler executes.
    *   Can query the DB (blocking style on JVM, async on Wasm).
    *   Can render UI.
6.  **Response**: The response is written back to the transport.

## Concurrency Model

### JVM: Virtual Threads
On the JVM, Aether uses `Executors.newVirtualThreadPerTaskExecutor()`. This means you can write blocking code (like JDBC calls) without blocking an OS thread. The runtime automatically suspends the virtual thread, allowing massive concurrency (millions of threads).

### Wasm: Coroutines
On Wasm (JS/WASI), Aether uses standard Kotlin Coroutines. I/O operations are non-blocking and suspend execution, returning control to the single-threaded event loop.

## Database Abstraction

Aether DB does not simply concatenate SQL strings.
1.  **Model Definition**: You define Kotlin objects.
2.  **Query DSL**: You write type-safe queries (`Users.filter(...)`).
3.  **AST Construction**: The framework builds an Abstract Syntax Tree of the query.
4.  **Driver Translation**: The active `DatabaseDriver` translates the AST into the specific SQL dialect (PostgreSQL, SQLite, etc.) or even an HTTP request (for Wasm clients talking to a data API).
