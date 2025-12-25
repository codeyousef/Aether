package codes.yousef.aether.example

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.installCallLogging
import codes.yousef.aether.core.pipeline.installContentNegotiation
import codes.yousef.aether.core.pipeline.installRecovery
import codes.yousef.aether.auth.*
import codes.yousef.aether.admin.AdminSite
import codes.yousef.aether.admin.ModelAdmin
import codes.yousef.aether.db.DatabaseDriverRegistry
import codes.yousef.aether.db.jvm.VertxPgDriver
import codes.yousef.aether.ui.*
import codes.yousef.aether.web.pathParam
import codes.yousef.aether.web.pathParamInt
import codes.yousef.aether.web.router
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Main entry point for the Aether example application.
 * Demonstrates all features of the Aether framework.
 */
fun main() = runBlocking(AetherDispatcher.dispatcher) {
    // Initialize database driver with environment variables
    val dbHost = System.getenv("DB_HOST") ?: "localhost"
    val dbPort = System.getenv("DB_PORT")?.toIntOrNull() ?: 5432
    val dbName = System.getenv("DB_NAME") ?: "aether_example"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "postgres"

    val driver = VertxPgDriver.create(
        host = dbHost,
        port = dbPort,
        database = dbName,
        user = dbUser,
        password = dbPassword
    )
    DatabaseDriverRegistry.initialize(driver)

    // Create tables if they don't exist
    Users.createTable()
    Sessions.createTable()

    // Initialize Admin Site
    val adminSite = AdminSite()
    adminSite.register(Users, object : ModelAdmin<User>(Users) {
        override val listDisplay = listOf("id", "username", "email", "isStaff", "isActive")
        override val listDisplayLinks = listOf("id", "username")
        override val searchFields = listOf("username", "email")
        override val listFilter = listOf("isStaff", "isActive")
    })

    // Create router with all routes
    val router = router {
        // Home page with UI DSL
        get("/") { exchange ->
            val currentUser = exchange.attributes.get(UserKey) as? User
            exchange.render {
                element("html") {
                    head {
                        title { text("Aether Example App") }
                    }
                    body {
                        h1 { text("Welcome to Aether Framework") }
                        if (currentUser != null) {
                            p { text("Logged in as: ${currentUser.username}") }
                            div {
                                a("/logout") { text("Logout") }
                            }
                        } else {
                            div {
                                a("/login") { text("Login") }
                                text(" | ")
                                a("/register") { text("Register") }
                            }
                        }
                        p { text("A modern Kotlin Multiplatform web framework") }
                        div {
                            h2 { text("Features") }
                            ul {
                                li { text("Django-style ORM with ActiveRecord pattern") }
                                li { text("Express-style routing with path parameters") }
                                li { text("Pipeline middleware system") }
                                li { text("UI DSL for server-side rendering") }
                                li { text("Content negotiation (HTML/JSON/CBOR)") }
                            }
                        }
                        div {
                            h2 { text("Quick Links") }
                            ul {
                                li { a("/users") { text("View All Users") } }
                                li { a("/api/users") { text("JSON API - List Users") } }
                                li { a("/about") { text("About Page") } }
                            }
                        }
                    }
                }
            }
        }

        // Login Page
        get("/login") { exchange ->
            val form = LoginForm()
            exchange.render {
                element("html") {
                    head { title { text("Login") } }
                    body {
                        h1 { text("Login") }
                        form(action = "/login", method = "post") {
                            form.asP(this)
                            div {
                                input(type = "submit", attributes = mapOf("value" to "Login"))
                            }
                        }
                        div { a("/") { text("Back") } }
                    }
                }
            }
        }

        // Handle Login
        post("/login") { exchange ->
            val body = exchange.request.bodyText()
            val params = body.split("&").associate {
                val parts = it.split("=")
                val key = parts.getOrElse(0) { "" }
                val value = parts.getOrElse(1) { "" }
                key to value
            }
            
            val form = LoginForm()
            form.bind(params)
            
            if (form.isValid()) {
                val username = form.get<String>("username")!!
                val password = form.get<String>("password")!!
                
                val user = Auth.authenticate(username, password, Users) as? User
                if (user != null) {
                    Auth.login(exchange, user)
                    exchange.redirect("/")
                } else {
                    form.addError("__all__", "Invalid username or password")
                    exchange.render {
                        element("html") {
                            body {
                                h1 { text("Login") }
                                form(action = "/login", method = "post") {
                                    form.asP(this)
                                    div {
                                        input(type = "submit", attributes = mapOf("value" to "Login"))
                                    }
                                }
                                div { a("/") { text("Back") } }
                            }
                        }
                    }
                }
            } else {
                exchange.render {
                    element("html") {
                        body {
                            h1 { text("Login") }
                            form(action = "/login", method = "post") {
                                form.asP(this)
                                div {
                                    input(type = "submit", attributes = mapOf("value" to "Login"))
                                }
                            }
                            div { a("/") { text("Back") } }
                        }
                    }
                }
            }
        }

        // Logout
        get("/logout") { exchange ->
            Auth.logout(exchange)
            exchange.redirect("/")
        }

        // Register Page
        get("/register") { exchange ->
             val form = RegisterForm()
             exchange.render {
                element("html") {
                    head { title { text("Register") } }
                    body {
                        h1 { text("Register") }
                        form(action = "/register", method = "post") {
                            form.asP(this)
                            div {
                                input(type = "submit", attributes = mapOf("value" to "Register"))
                            }
                        }
                        div { a("/") { text("Back") } }
                    }
                }
            }
        }

        // Handle Register
        post("/register") { exchange ->
            val body = exchange.request.bodyText()
            val params = body.split("&").associate {
                val parts = it.split("=")
                val key = parts.getOrElse(0) { "" }
                val value = parts.getOrElse(1) { "" }
                key to value
            }
            
            val form = RegisterForm()
            form.bind(params)
            
            if (form.isValid()) {
                val username = form.get<String>("username")!!
                val email = form.get<String>("email")!!.replace("%40", "@")
                val password = form.get<String>("password")!!

                if (User.findByUsername(username) != null) {
                     form.addError("username", "Username already taken")
                     exchange.render {
                        element("html") {
                            body {
                                h1 { text("Register") }
                                form(action = "/register", method = "post") {
                                    form.asP(this)
                                    div {
                                        input(type = "submit", attributes = mapOf("value" to "Register"))
                                    }
                                }
                                div { a("/") { text("Back") } }
                            }
                        }
                    }
                } else {
                    val user = User.create(username, email, null, password)
                    Auth.login(exchange, user)
                    exchange.redirect("/")
                }
            } else {
                exchange.render {
                    element("html") {
                        body {
                            h1 { text("Register") }
                            form(action = "/register", method = "post") {
                                form.asP(this)
                                div {
                                    input(type = "submit", attributes = mapOf("value" to "Register"))
                                }
                            }
                            div { a("/") { text("Back") } }
                        }
                    }
                }
            }
        }

        // List all users with rendered HTML
        get("/users") { exchange ->
            val users = User.all()
            exchange.render {
                element("html") {
                    head {
                        title { text("Users - Aether Example") }
                    }
                    body {
                        h1 { text("User List") }
                        if (users.isEmpty()) {
                            p { text("No users found.") }
                        } else {
                            ul {
                                for (user in users) {
                                    li {
                                        text("${user.username} (${user.email})")
                                        if (user.age != null) {
                                            text(" - Age: ${user.age}")
                                        }
                                        text(" ")
                                        a("/users/${user.id}") { text("[View Details]") }
                                    }
                                }
                            }
                        }
                        div {
                            a("/") { text("Back to Home") }
                        }
                    }
                }
            }
        }

        // Show user by ID with path parameter
        get("/users/:id") { exchange ->
            val userId = exchange.pathParam("id")?.toLongOrNull()
            if (userId == null) {
                exchange.badRequest("Invalid user ID")
                return@get
            }

            val user = User.findById(userId)
            if (user == null) {
                exchange.notFound("User not found")
                return@get
            }

            exchange.render {
                element("html") {
                    head {
                        title { text("User: ${user.username}") }
                    }
                    body {
                        h1 { text("User Details") }
                        div {
                            p { text("ID: ${user.id}") }
                            p { text("Username: ${user.username}") }
                            p { text("Email: ${user.email}") }
                            if (user.age != null) {
                                p { text("Age: ${user.age}") }
                            }
                        }
                        div {
                            a("/users") { text("Back to User List") }
                            text(" | ")
                            a("/") { text("Home") }
                        }
                    }
                }
            }
        }

        // JSON API endpoint - List all users
        get("/api/users") { exchange ->
            val users = User.all()
            val userDtos = users.map { user ->
                UserDto(
                    id = user.id,
                    username = user.username,
                    email = user.email,
                    age = user.age
                )
            }
            respondJson(exchange, userDtos)
        }

        // JSON API endpoint - Create user
        post("/api/users") { exchange ->
            try {
                val body = exchange.request.bodyBytes().decodeToString()
                val createRequest = Json.decodeFromString<CreateUserRequest>(body)

                if (createRequest.username.isNullOrBlank() || createRequest.email.isNullOrBlank()) {
                    exchange.badRequest("Username and email are required")
                    return@post
                }

                val user = User.create(
                    username = createRequest.username,
                    email = createRequest.email,
                    age = createRequest.age,
                    password = createRequest.password
                )

                val userDto = UserDto(
                    id = user.id,
                    username = user.username,
                    email = user.email,
                    age = user.age
                )
                respondJson(exchange, userDto, statusCode = 201)
            } catch (e: Exception) {
                exchange.internalError("Failed to create user: ${e.message}")
            }
        }

        // About page
        get("/about") { exchange ->
            exchange.render {
                element("html") {
                    head {
                        title { text("About - Aether Example") }
                    }
                    body {
                        h1 { text("About Aether") }
                        p { text("Aether is a modern Kotlin Multiplatform web framework inspired by Django and Express.js.") }
                        div {
                            h2 { text("Key Features") }
                            ul {
                                li { text("Kotlin Multiplatform - Write once, run everywhere (JVM, Wasm)") }
                                li { text("Django-style ORM with ActiveRecord pattern") }
                                li { text("Express-style routing with Radix Tree for O(k) matching") }
                                li { text("Pipeline middleware system") }
                                li { text("UI DSL for SSR and UWW mode") }
                                li { text("Built on Vert.x for high performance on JVM") }
                                li { text("Virtual Threads support (Java 21+)") }
                            }
                        }
                        div {
                            a("/") { text("Back to Home") }
                        }
                    }
                }
            }
        }
    }

    // Create pipeline with middleware
    val pipeline = Pipeline().apply {
        installRecovery()
        installCallLogging()
        installContentNegotiation()
        use(authMiddleware(Users))
        use(adminSite.urls().asMiddleware())
        use(router.asMiddleware())
    }

    // Configure server
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val config = VertxServerConfig(port = port)

    // Create and start server
    val server = VertxServer(config, pipeline) { exchange ->
        // Fallback handler for routes not matched by router
        exchange.notFound("Route not found")
    }

    // Register shutdown hook for graceful shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutting down Aether example app...")
        runBlocking {
            try {
                server.stop()
                driver.close()
                println("Shutdown complete")
            } catch (e: Exception) {
                println("Error during shutdown: ${e.message}")
            }
        }
    })

    // Start server
    server.start()
    println("Aether example app started on http://localhost:$port")
    println("Press Ctrl+C to stop")
}

/**
 * Data transfer objects for JSON API.
 */
@Serializable
data class UserDto(
    val id: Long?,
    val username: String?,
    val email: String?,
    val age: Int?
)

@Serializable
data class CreateUserRequest(
    val username: String?,
    val email: String?,
    val age: Int?,
    val password: String? = null
)

/**
 * Helper function to respond with JSON.
 */
private suspend fun <T> respondJson(exchange: Exchange, data: T, statusCode: Int = 200) {
    exchange.response.statusCode = statusCode
    exchange.response.setHeader("Content-Type", "application/json; charset=utf-8")
    val json = when (data) {
        is List<*> -> {
            @Suppress("UNCHECKED_CAST")
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(UserDto.serializer()), data as List<UserDto>)
        }
        is UserDto -> Json.encodeToString(UserDto.serializer(), data)
        else -> throw IllegalArgumentException("Unsupported data type")
    }
    exchange.response.write(json)
    exchange.response.end()
}
