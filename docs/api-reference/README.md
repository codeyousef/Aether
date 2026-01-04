# API Reference

This section provides detailed API documentation for each module in the Aether framework.

## Core Modules

| Module | Description |
| :----- | :---------- |
| [Core](core.md) | Exchange interface, Pipeline middleware system, Rate Limit & Proxy |
| [Routing](routing.md) | Radix tree router, path parameters, route groups |
| [Database](database.md) | ORM, Models, QueryAST, PostgreSQL/Supabase/Firestore drivers |
| [Authentication](authentication.md) | Session management, JWT, OAuth2, TOTP |
| [Session Management](session-management.md) | Cookie and token-based sessions |
| [Network](network.md) | TCP/UDP transport abstractions |
| [WebSockets](websockets.md) | Real-time bidirectional communication |
| [UI](ui.md) | Composable UI DSL, SSR rendering |

## Reactive & Async Modules

| Module | Description |
| :----- | :---------- |
| [Signals](signals.md) | In-process event dispatch system (pub/sub) |
| [Tasks](tasks.md) | Background job queue and scheduled tasks |
| [Channels](channels.md) | WebSocket channel layer for real-time pub/sub |

## Admin & Forms

| Module | Description |
| :----- | :---------- |
| [Migrations](migrations.md) | Schema versioning and migration management |

---

## Quick Links

### Common Patterns

- **Request handling**: See [Exchange](core.md#exchange) for response methods
- **Middleware**: See [Pipeline](core.md#pipeline) for middleware chain
- **Rate limiting**: See [Rate Limit Middleware](core.md#rate-limit-middleware)
- **Reverse proxy**: See [HTTP Reverse Proxy](core.md#http-reverse-proxy)
- **Database queries**: See [QueryAST](database.md#queryast) for type-safe queries
- **Model events**: See [Model Signals](database.md#model-signals) for lifecycle hooks
- **Event dispatch**: See [Signals](signals.md) for in-process pub/sub
- **Background jobs**: See [Tasks](tasks.md) for async job processing
- **Real-time messaging**: See [Channels](channels.md) for WebSocket pub/sub

### Platform Targets

Aether supports multiple Kotlin targets:

| Target | Use Case |
| :----- | :------- |
| JVM | Traditional server deployment (Vert.x + Virtual Threads) |
| wasmJs | Cloudflare Workers, browser-based apps |
| wasmWasi | Edge computing, serverless functions |

### Version History

- **0.4.0** — Signals, Tasks, Channels, Admin Widgets, Rate Limit Middleware
- **0.3.1** — HTTP Proxy Middleware, Circuit Breaker
- **0.3.0** — Supabase & Firestore drivers, Admin improvements
- **0.2.0** — Database migrations, Session management, TOTP auth
