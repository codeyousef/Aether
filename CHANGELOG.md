# Changelog

## [0.4.2.1] - 2026-01-06
### Fixed
- **Publishing**: Improved Maven Central publish task with better HTTP status tracking and bundle debugging
- **Publishing**: Added deployment status verification after upload to Central Portal

## [0.4.2.0] - 2026-01-06
### Added
- **Authentication**: Added comprehensive RBAC support with `User`, `Groups`, and `Permissions`
- **Testing**: Added End-to-End (E2E) tests for authentication flow and RBAC verification
- **JWT for Wasm**: Full `JwtService` implementation for WasmJS and WasmWasi targets
  - Pure Kotlin SHA-256 and HMAC-SHA256 implementation (`Sha256`, `HmacSha256`)
  - Base64URL encoding/decoding for JWT serialization (`Base64Url`)
  - `PureJwt` object for cross-platform token generation and verification
  - HS256 algorithm support matching JVM implementation
  - Cross-platform tests for cryptographic primitives

### Fixed
- **Authentication**: Fixed unchecked cast warnings in `AbstractUser`
- **Networking**: Updated `RouterServer` to use modern Vert.x `setKeyCertOptions` for SSL configuration, replacing deprecated methods
- **Core**: Fixed various deprecation warnings in `StreamingProxyClient` and `VertxWebSocket`

## [0.4.1.0] - 2026-01-05
### Added
- **Aether Start**: New `aetherStart {}` entry point for simplified server startup
  - Supports both pipeline-based and router-based configuration
  - **WebSocket Integration**: Automatically registers and handles WebSocket routes defined in the Router
  - Automatic ASCII banner display on startup
  - Graceful shutdown handling with "⚡ Shutting down Aether..." messages
  - Replaces direct `VertxServer` usage in user code
- **CLI**: Added `runserver` command (alias `run`) to start the development server
- **CLI**: Added ASCII banner to help output and startup messages

### Changed
- **Branding**: Replaced Vert.x branding with Aether branding in customer-facing output
  - Terminal output now shows "Aether Framework" instead of Vert.x messages
  - Gradle tasks now described as "Run ... with Virtual Threads" instead of "Vert.x"
  - Example app UI updated to highlight "Virtual Threads" instead of "Vert.x"
- **Templates**: Updated `startproject` and Gradle plugin templates to use `aetherStart`
- **Testing**: Added comprehensive E2E tests for `aetherStart` and WebSocket functionality

## [0.4.0.1] - 2026-01-04
### Fixed
- **Publishing**: Added missing modules to Maven Central publish list
  - `aether-signals`: Now published to Maven Central
  - `aether-tasks`: Now published to Maven Central  
  - `aether-channels`: Now published to Maven Central
- **Multiplatform Compatibility**: Fixed `synchronized` and `Math` usage in common code
  - Replaced `synchronized` with `Mutex.withLock` in Signal dispatch
  - Replaced `Math.random()` with `kotlin.random.Random.nextDouble()`
  - Replaced `Math.pow()` with `kotlin.math.pow()`
- **Build**: Disabled wasmJs browser tests (use Node.js instead) to fix CI without Chrome

## [0.4.0.0] - 2026-01-04
### Added
- **Aether Signals** (`aether-signals`): Django-style event dispatch system
  - `Signal<T>`: Type-safe event signal with sender payload
  - `signal.connect { }`: Register receivers with automatic lifecycle management
  - `signal.send()` / `signal.sendAsync()`: Synchronous and async event dispatch
  - `Disposable`: Disconnect receivers to prevent memory leaks
  - Built-in signals: `preSave`, `postSave`, `preDelete`, `postDelete` in `aether-db`
  - `SignalMiddleware`: Pipeline middleware for request lifecycle signals

- **Aether Tasks** (`aether-tasks`): Persistent background job queue
  - `TaskDispatcher`: Enqueue, cancel, and track async tasks
  - `TaskWorker`: Process tasks with configurable concurrency and polling
  - `TaskRegistry`: Register task handlers with `register<A, R>("name") { }`
  - `InMemoryTaskStore`: Development/testing store (tasks lost on restart)
  - `DatabaseTaskStore`: Production store using `aether-db` (persistent)
  - `TaskStatus`: PENDING, SCHEDULED, PROCESSING, COMPLETED, FAILED, CANCELLED, RETRYING
  - `TaskPriority`: LOW, NORMAL, HIGH, CRITICAL with queue ordering
  - `RetryConfig`: Exponential backoff with jitter for failed tasks
  - `@BackgroundTask` annotation for KSP code generation (future)

- **Aether Channels** (`aether-channels`): WebSocket pub/sub layer
  - `ChannelLayer`: Interface for group-based message routing
  - `InMemoryChannelLayer`: Single-server implementation with atomic operations
  - `groupAdd()` / `groupDiscard()`: Manage session membership
  - `groupSend()` / `groupSendBinary()`: Broadcast text or binary to groups
  - `Channels` singleton: Global access with `Channels.group("name").broadcast()`
  - `ChannelMessage`: Typed message with type, payload, sender, target, timestamp
  - `SendResult`: Track sent count, failures, and errors

- **Admin Dashboard Widgets** (`aether-admin`): Pluggable dashboard components
  - `DashboardWidget` interface: Custom widgets with async data loading
  - `StatWidget`: Simple stat display with icon, color, and optional link
  - `ListWidget<T>`: Table of items with headers and row renderer
  - `QuickActionsWidget`: Action buttons with icons and variants
  - `HtmlWidget`: Custom rendering with full ComposableScope access
  - `AlertWidget`: Notifications with info/warning/error/success variants
  - `ProgressWidget`: Progress bars with value, max, unit, and color
  - `AdminSite.registerWidget()` / `unregisterWidget()`: Widget management
  - `WidgetContext`: Site name, current path, and registered models

- **Rate Limit Middleware** (`aether-core`): Quota-based request limiting
  - `QuotaProvider` interface: Pluggable quota checking strategies
  - `InMemoryQuotaProvider`: Sliding window rate limiting with atomic counters
  - `QuotaUsage`: Track used, limit, remaining, resetsAt, percentUsed
  - `RateLimitConfig`: keyExtractor, costFunction, excludedPaths, exhaustedHandler
  - `Pipeline.installRateLimit { }`: DSL for middleware configuration
  - `Pipeline.installRateLimitWithCredits()`: Database-backed credit systems
  - Rate limit headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`
  - HTTP 429 "Rate limit exceeded" response (configurable status code)

- **HTTP Reverse Proxy** (`aether-core`): Production-ready streaming HTTP proxy middleware
  - `Exchange.proxyTo()`: One-liner proxy forwarding with full header/body/query passthrough
  - `Exchange.proxyRequest()`: Manual response inspection before forwarding
  - `Pipeline.installProxy()`: Declarative middleware DSL for path-prefix routing
  - **Streaming Support**: True streaming via `Flow<ByteArray>` with SSE/chunked transfer encoding
  - **Header Management**: Add, remove, replace headers; automatic `X-Forwarded-*` headers
  - **Path Rewriting**: Strip prefixes, transform paths with custom rewriters
  - **Circuit Breaker**: Sliding window failure tracking with CLOSED/OPEN/HALF_OPEN states
  - **Typed Exceptions**: `ProxyConnectionException` (502), `ProxyTimeoutException` (504), `ProxyCircuitOpenException` (503)
  - **Recovery Integration**: `handleProxyExceptions()` for automatic error response handling
  - JVM: Vert.x HttpClient with connection pooling and HTTP/2 support
  - WasmJS/WasmWASI: Stub implementations (proxy requires server-side execution)

- **Supabase Database Support** (`aether-db`): Use Supabase as a backend for the Aether ORM
  - `SupabaseDriver`: Full DatabaseDriver implementation using PostgREST API
  - `SupabaseTranslator`: Converts QueryAST to PostgREST query format
  - Support for SELECT, INSERT, UPDATE, DELETE operations
  - WHERE clauses with AND/OR/IN/IS_NULL/IS_NOT_NULL
  - ORDER BY, LIMIT, OFFSET pagination
  - JVM extension: `SupabaseDriver.fromEnvironment()` for easy configuration

- **Firestore Database Support** (`aether-db`): Use Google Firestore as a backend for the Aether ORM
  - `FirestoreDriver`: Full DatabaseDriver implementation using Firestore REST API
  - `FirestoreTranslator`: Converts QueryAST to Firestore structuredQuery format
  - Support for SELECT, INSERT, UPDATE, DELETE operations
  - WHERE clauses with AND/OR/IN comparisons
  - ORDER BY and LIMIT support
  - JVM extension: `FirestoreDriver.fromEnvironment()` for easy configuration
  - Helper methods: `createDocument()`, `getDocument()` for document-centric access

- **HTTP Client Abstraction** (`aether-db`): Platform-agnostic HTTP client for database drivers
  - `expect/actual` pattern for full KMP support
  - JVM: Uses Java 11 HttpClient with virtual threads
  - WasmJS: Uses browser/Node.js fetch API
  - WasmWASI: Stub implementation (throws at runtime)

- **Comprehensive Test Suite** for new database backends
  - Unit tests for `SupabaseTranslator` and `FirestoreTranslator` (commonTest)
  - Integration tests with WireMock HTTP mocking (jvmTest)
  - Optional E2E tests against real services (enabled via environment variables)

### Changed
- `FirestoreDriver.buildQueryParams()`: Fixed API key not being added when query params are empty

### Notes
- Firestore has NoSQL limitations: JOINs, LIKE, DISTINCT, NOT operators are not supported
- Supabase requires a PostgreSQL table schema; Firestore is schemaless

## [0.3.6.0] - 2026-01-03
### Fixed
- **Vert.x body reading**: Set up body handlers SYNCHRONOUSLY in request handler before launching coroutine
  - Fixes "Request has already been read" error on Cloud Run
  - Body is now collected on the Vert.x event loop thread before any async processing
  - Added `createVertxExchangeWithBody()` for pre-read body bytes

## [0.3.5.0] - 2026-01-03
### Fixed
- **Vert.x body reading**: Simplified body reading to use `body().coAwait()` with error logging
  - Previous handler/endHandler approach didn't work correctly
  - Added error logging for debugging body read failures

## [0.3.4.0] - 2026-01-03
### Fixed
- **Vert.x body reading**: Fixed request body not being read properly in some environments (e.g., Cloud Run)
  - Changed from `vertxRequest.body().coAwait()` to using `handler`/`endHandler` pattern
  - Ensures body stream is properly resumed and collected before processing
  - Fixes forms returning empty data on POST requests

## [0.3.3.0] - 2026-01-03
### Added
- **ModelAdmin enhancements**: New configuration options for form customization
  - `multilineFields`: List of field names that should render as textarea instead of single-line input
  - `excludeFields`: List of field names to hide from forms (values still saved via defaults)
  - `defaultValues`: Map of field names to default values for new objects
- **ModelForm defaults**: Constructor now accepts `defaultValues` parameter for pre-filling forms

### Changed
- Form rendering now respects `multilineFields` and `excludeFields` from ModelAdmin
- Default values are automatically merged when submitting new objects

## [0.3.2.0] - 2026-01-03
### Added
- **Modern Admin UI**: Complete redesign of the admin interface with CMS-style look
  - `AdminTheme`: Design tokens (colors, spacing, typography, shadows) with Tailwind-inspired slate/blue palette
  - `AdminComponents`: Reusable UI building blocks (cards, tables, forms, filters, badges)
  - Dashboard with stats cards, quick actions, and content overview
  - Improved list views with search toolbar, filter sidebar, and action buttons
  - Modern form pages with proper layout and validation styling
  - Styled delete confirmation pages
  - Responsive sidebar navigation with SVG icons
  - Inter font and smooth transitions

### Changed
- `Form.allFields()`: New public method to access form fields (replaces protected access)
- `Form.getFieldError()`: New method to get single field error message

## [0.3.1.4] - 2025-12-28
### Fixed
- Admin site URLs no longer duplicate the admin prefix (e.g., `/admin/admin/services` → `/admin/services`)

## [0.3.1.3] - 2025-12-27
### Fixed
- Redirect now defers `response.end()` so SessionMiddleware can set cookies before headers are finalized
- Vert.x server finalizes responses after middleware completes to ensure Set-Cookie is emitted on redirects

## [0.3.1.2] - 2025-12-27
### Fixed
- Publish workflow now includes `aether-auth`, `aether-forms`, and `aether-admin` modules

## [0.3.1.1] - 2025-12-26
### Fixed
- AdminSite routing issue where dashboard handler was not properly registered for root path

## [0.3.1.0] - 2025-12-26
### Added
- **First Maven Central publish** for `aether-auth`, `aether-forms`, and `aether-admin` modules

## [0.3.0.0] - 2025-12-26
### Added
- **Admin Module** (`aether-admin`): Django-like admin interface for managing database models
  - `AdminSite`: Central registry for model admin configuration with auto-generated routes
  - `ModelAdmin`: Customizable list display, search fields, filters, and display links
  - `ModelForm`: Auto-generated forms from Model columns with CRUD operations
  - Bootstrap-styled responsive UI
- **Auth Module** (`aether-auth`): Authentication and authorization system
  - `User` model with password hashing and session management
  - `AuthMiddleware` for protected routes
  - `Permission` system for fine-grained access control
- **Forms Module** (`aether-forms`): Form handling and validation
  - Form field types with automatic HTML rendering
  - Server-side validation with error messages
  - CSRF protection integration
- **Security Pipeline**: Added `installCsrfProtection()` and `installSecurityHeaders()` middleware
- **Debug Toolbar**: Added `installDebugToolbar()` for development mode debugging

### Changed
- Upgraded to Kotlin 2.2.20

## [0.2.0.0] - 2025-12-23
### Added
- **Session Management**: Added `SessionMiddleware` and `Exchange.session()` / `Exchange.requireSession()` for managing user sessions.
- **JSON DSL**: Added reified inline `Exchange.respondJson(statusCode, data)` for simplified JSON responses.
- **Form Parsing**: Added `Exchange.receiveParameters()` (JVM) to handle `application/x-www-form-urlencoded` requests.
- **Path Parameters**: Added `Exchange.pathParamOrThrow()` for safer parameter extraction.

## [0.1.0] - 2025-12-22
### Added
- Initial release of Aether Framework.
