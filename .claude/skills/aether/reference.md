# Aether Reference

## Database Drivers

### PostgreSQL (JVM only)

```kotlin
val driver = VertxPgDriver.create(
    host = "localhost",
    port = 5432,
    database = "mydb",
    user = "postgres",
    password = "secret",
    poolSize = 10
)
```

### Supabase (JVM + Wasm)

```kotlin
val driver = SupabaseDriver.create(
    projectUrl = "https://xxx.supabase.co",
    apiKey = "your-anon-key"  // or service key for server-side
)
```

### Firestore (JVM + Wasm)

```kotlin
// With API key (client-side)
val driver = FirestoreDriver.create(
    projectId = "your-project",
    apiKey = "your-api-key"
)

// With OAuth token (server-side)
val driver = FirestoreDriver.createWithToken(
    projectId = "your-project",
    accessToken = "your-oauth-token"
)
```

**Firestore limitations:** No JOINs, no LIKE queries, no complex aggregations. Use denormalized data patterns.

## QueryAST Examples

```kotlin
// Simple filter
Users.objects.filter { it.isActive eq true }

// Multiple conditions
Users.objects.filter { (it.age gte 18) and (it.status eq "verified") }

// OR conditions
Users.objects.filter { (it.role eq "admin") or (it.role eq "moderator") }

// Ordering
Users.objects.filter { it.isActive eq true }.orderBy(Users.createdAt, desc = true)

// Limiting
Users.objects.filter { it.isActive eq true }.limit(10).offset(20)

// Selecting specific fields
Users.objects.select(Users.id, Users.username).filter { it.isActive eq true }

// Count
val count = Users.objects.filter { it.isActive eq true }.count()

// Exists
val hasAdmins = Users.objects.filter { it.role eq "admin" }.exists()
```

## gRPC Support

```kotlin
// Define service
val grpcService = grpcService("UserService") {
    unary("GetUser") { request: GetUserRequest ->
        val user = Users.findById(request.id)
        GetUserResponse(user)
    }

    serverStream("ListUsers") { request: ListUsersRequest ->
        flow {
            Users.objects.filter { it.isActive eq true }.forEach {
                emit(UserResponse(it))
            }
        }
    }
}

// Mount on router
router {
    grpc("/grpc", grpcService)
}
```

## Server Startup

```kotlin
fun main() {
    // Initialize database
    initDatabase()

    // Build pipeline
    val pipeline = pipeline {
        installRecovery()
        installCallLogging()
        use(router.asMiddleware())
    }

    // Start server
    AetherServer.start(
        port = System.getenv("PORT")?.toInt() ?: 8080,
        host = "0.0.0.0",
        pipeline = pipeline
    )
}
```

## Environment Configuration

```kotlin
object Config {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val dbHost = System.getenv("DB_HOST") ?: "localhost"
    val dbPort = System.getenv("DB_PORT")?.toInt() ?: 5432
    val dbName = System.getenv("DB_NAME") ?: "app"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "postgres"
    val jwtSecret = System.getenv("JWT_SECRET") ?: error("JWT_SECRET required")
    val isProduction = System.getenv("ENV") == "production"
}
```

## Error Handling

```kotlin
// Custom exception
class ValidationException(val errors: Map<String, String>) : Exception()

// In recovery middleware
pipeline {
    installRecovery { exchange, error ->
        when (error) {
            is ValidationException -> {
                exchange.respondJson(mapOf("errors" to error.errors), status = 400)
            }
            is NotFoundException -> {
                exchange.respond(404, "Not Found")
            }
            is UnauthorizedException -> {
                exchange.respond(401, "Unauthorized")
            }
            else -> {
                if (Config.isProduction) {
                    exchange.respond(500, "Internal Server Error")
                } else {
                    exchange.respond(500, error.stackTraceToString())
                }
            }
        }
    }
}
```

## CORS Configuration

```kotlin
pipeline {
    installCors(CorsConfig(
        allowedOrigins = listOf("https://myapp.com", "https://admin.myapp.com"),
        allowedMethods = listOf("GET", "POST", "PUT", "DELETE"),
        allowedHeaders = listOf("Content-Type", "Authorization"),
        allowCredentials = true,
        maxAge = 3600
    ))
}
```

## Response Helpers

```kotlin
// JSON response
exchange.respondJson(data)
exchange.respondJson(data, status = 201)

// HTML response
exchange.respondHtml("<h1>Hello</h1>")
exchange.respondHtml(htmlContent, status = 200)

// Plain text
exchange.respond(200, "OK")
exchange.respond(204)  // No content

// Redirect
exchange.redirect("/login")
exchange.redirect("/new-location", permanent = true)

// File download
exchange.respondFile(path = "/files/doc.pdf", filename = "document.pdf")

// Streaming
exchange.respondStream(contentType = "text/event-stream") { output ->
    while (true) {
        output.write("data: ${getUpdate()}\n\n")
        delay(1000)
    }
}
```

## Request Helpers

```kotlin
// Path parameters
val id = exchange.pathParam("id")
val id = exchange.pathParamInt("id")

// Query parameters
val page = exchange.queryParam("page")?.toIntOrNull() ?: 1
val tags = exchange.queryParams("tag")  // Multiple values

// Headers
val auth = exchange.header("Authorization")
val contentType = exchange.contentType

// Body
val json = exchange.receiveJson<MyRequest>()
val text = exchange.receiveText()
val bytes = exchange.receiveBytes()

// Form data
val params = exchange.formParams()
val username = exchange.formParam("username")

// Files
val file = exchange.file("avatar")
val files = exchange.files("attachments")

// Session
val session = exchange.session
val userId = session["user_id"]

// Auth principal
val principal = exchange.principal
val isAdmin = principal?.roles?.contains("admin") == true
```
