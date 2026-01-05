package codes.yousef.aether.cli

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess
import codes.yousef.aether.db.*
import codes.yousef.aether.db.jvm.VertxPgDriver
import kotlinx.coroutines.runBlocking

/**
 * Aether CLI - Command line tools for the Aether framework.
 *
 * Available commands:
 * - runserver: Start the development server
 * - migrate: Generate and apply database migrations
 * - init: Initialize a new Aether project
 * - startproject: Create a new Aether project structure
 * - startapp: Create a new Aether app (module)
 * - inspectdb: Introspect database and generate Model classes
 * - help: Show help information
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printHelp()
        exitProcess(1)
    }

    val command = args[0].lowercase()

    try {
        when (command) {
            "runserver", "run" -> handleRunServer(args.drop(1))
            "migrate" -> handleMigrate(args.drop(1))
            "init" -> handleInit(args.drop(1))
            "startproject" -> handleStartProject(args.drop(1))
            "startapp" -> handleStartApp(args.drop(1))
            "inspectdb" -> handleInspectDb(args.drop(1))
            "shell" -> handleShell(args.drop(1))
            "help", "--help", "-h" -> printHelp()
            else -> {
                println("Unknown command: $command")
                println()
                printHelp()
                exitProcess(1)
            }
        }
    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

/**
 * Handles the 'runserver' command.
 * Starts the Aether development server.
 */
private fun handleRunServer(args: List<String>) {
    var port = 8080
    var host = "0.0.0.0"

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port", "-p" -> if (i + 1 < args.size) port = args[++i].toIntOrNull() ?: 8080
            "--host", "-h" -> if (i + 1 < args.size) host = args[++i]
            "--help" -> {
                println("""
                    Usage: aether-cli runserver [options]
                    
                    Options:
                      --port, -p <port>   Port to listen on (default: 8080)
                      --host <host>       Host to bind to (default: 0.0.0.0)
                      --help              Show this help message
                      
                    Examples:
                      aether-cli runserver
                      aether-cli runserver --port 3000
                      aether-cli runserver --host 127.0.0.1 --port 8000
                """.trimIndent())
                return
            }
        }
        i++
    }

    println("""
       ___        __  __             
      / _ | ___  / /_/ /  ___  ____  
     / __ |/ -_)/ __/ _ \/ -_)/ __/  
    /_/ |_|\__/ \__/_//_/\__//_/     
    
    ⚡ Starting Aether development server...
    """.trimIndent())

    val displayHost = if (host == "0.0.0.0") "localhost" else host
    println("▸ Server: http://$displayHost:$port")
    println("▸ Press Ctrl+C to stop")
    println()
    println("Note: This command looks for your Application.kt or Main.kt in the current project.")
    println("      Run './gradlew run' or './gradlew aetherRunJvm' to start your application.")
}

/**
 * Handles the 'migrate' command.
 *
 * Generates and applies database migrations based on model definitions.
 * In a full implementation, this would use KSP to reflect on Model classes
 * and generate SQL migration files.
 */
private fun handleMigrate(args: List<String>) {
    println("=== Aether Database Migrations ===")
    println()

    val projectDir = File(System.getProperty("user.dir"))
    val migrationsDir = File(projectDir, "migrations")

    if (!migrationsDir.exists()) {
        println("Creating migrations directory...")
        migrationsDir.mkdirs()
    }

    println("Project directory: ${projectDir.absolutePath}")
    println("Migrations directory: ${migrationsDir.absolutePath}")
    println()

    // Parse options
    var createMigration = false
    var migrationName = "migration"
    var applyMigrations = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--create", "-c" -> {
                createMigration = true
                if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                    migrationName = args[i + 1]
                    i++
                }
            }
            "--apply", "-a" -> applyMigrations = true
            "--help", "-h" -> {
                printMigrateHelp()
                return
            }
        }
        i++
    }

    if (createMigration) {
        createMigrationFile(migrationsDir, migrationName)
    } else if (applyMigrations) {
        applyMigrationFiles(migrationsDir)
    } else {
        listMigrations(migrationsDir)
    }
}

/**
 * Creates a new migration file.
 */
private fun createMigrationFile(migrationsDir: File, name: String) {
    val timestamp = System.currentTimeMillis() / 1000
    val fileName = "${timestamp}_${name}.sql"
    val migrationFile = File(migrationsDir, fileName)

    val template = """
        -- Migration: $name
        -- Generated: ${java.time.Instant.now()}

        -- Add your migration SQL here
        -- Example:
        -- CREATE TABLE users (
        --     id SERIAL PRIMARY KEY,
        --     username VARCHAR(255) NOT NULL UNIQUE,
        --     email VARCHAR(255) NOT NULL UNIQUE,
        --     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        -- );

    """.trimIndent()

    migrationFile.writeText(template)
    println("Created migration: ${migrationFile.name}")
    println("Location: ${migrationFile.absolutePath}")
    println()
    println("Edit the migration file to add your schema changes, then run:")
    println("  aether-cli migrate --apply")
}

/**
 * Lists all migration files.
 */
private fun listMigrations(migrationsDir: File) {
    val migrations = migrationsDir.listFiles { _, name -> name.endsWith(".sql") }
        ?.sortedBy { it.name }
        ?: emptyList()

    if (migrations.isEmpty()) {
        println("No migrations found.")
        println()
        println("Create a new migration with:")
        println("  aether-cli migrate --create <name>")
        return
    }

    println("Found ${migrations.size} migration(s):")
    println()
    migrations.forEach { migration ->
        println("  - ${migration.name}")
    }
    println()
    println("Apply migrations with:")
    println("  aether-cli migrate --apply")
}

/**
 * Applies all pending migrations.
 */
private fun applyMigrationFiles(migrationsDir: File) {
    val migrations = migrationsDir.listFiles { _, name -> name.endsWith(".sql") }
        ?.sortedBy { it.name }
        ?: emptyList()

    if (migrations.isEmpty()) {
        println("No migrations to apply.")
        return
    }

    println("Applying ${migrations.size} migration(s)...")
    println()

    // Connect to DB
    val dbHost = System.getenv("DB_HOST") ?: "localhost"
    val dbPort = System.getenv("DB_PORT")?.toIntOrNull() ?: 5432
    val dbName = System.getenv("DB_NAME") ?: "aether_example"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "postgres"

    runBlocking {
        println("Connecting to database $dbName at $dbHost:$dbPort...")
        val driver = VertxPgDriver.create(
            host = dbHost,
            port = dbPort,
            database = dbName,
            user = dbUser,
            password = dbPassword
        )
        DatabaseDriverRegistry.initialize(driver)

        try {
            // Create migrations table
            driver.execute("""
                CREATE TABLE IF NOT EXISTS _migrations (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL UNIQUE,
                    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
            """.trimIndent())

            // Get applied migrations
            val appliedMigrations = try {
                val rows = driver.executeQuery(SelectQuery(
                    columns = listOf(Expression.ColumnRef(column = "name")),
                    from = "_migrations"
                ))
                rows.map { it.getValue("name") as String }.toSet()
            } catch (e: Exception) {
                println("Error fetching applied migrations: ${e.message}")
                emptySet()
            }

            var appliedCount = 0
            migrations.forEach { migration ->
                if (migration.name in appliedMigrations) {
                    // println("  [SKIP] ${migration.name} (already applied)")
                } else {
                    println("  [APPLY] ${migration.name}")
                    try {
                        val sql = migration.readText()
                        // Execute migration SQL
                        // We split by semicolon to handle multiple statements if driver doesn't support it?
                        // Vertx driver usually supports multiple statements if enabled, but let's assume single block or use execute.
                        driver.execute(sql)

                        // Record migration
                        driver.executeUpdate(InsertQuery(
                            table = "_migrations",
                            columns = listOf("name"),
                            values = listOf(Expression.Literal(SqlValue.StringValue(migration.name)))
                        ))
                        println("    -> Success")
                        appliedCount++
                    } catch (e: Exception) {
                        println("    -> FAILED: ${e.message}")
                        throw e // Stop on error
                    }
                }
            }
            
            if (appliedCount == 0) {
                println("Database is up to date.")
            } else {
                println("Successfully applied $appliedCount migration(s).")
            }

        } catch (e: Exception) {
            println("Error applying migrations: ${e.message}")
            e.printStackTrace()
        } finally {
            driver.close()
        }
    }
}

/**
 * Handles the 'init' command.
 *
 * Initializes a new Aether project with the standard directory structure.
 */
private fun handleInit(args: List<String>) {
    println("=== Aether Project Initialization ===")
    println()

    val projectDir = if (args.isNotEmpty() && !args[0].startsWith("-")) {
        File(args[0])
    } else {
        File(System.getProperty("user.dir"))
    }

    println("Initializing Aether project in: ${projectDir.absolutePath}")
    println()

    // Create directory structure
    val directories = listOf(
        "src/commonMain/kotlin",
        "src/jvmMain/kotlin",
        "src/wasmJsMain/kotlin",
        "src/wasmWasiMain/kotlin",
        "src/commonMain/resources",
        "src/jvmMain/resources",
        "migrations",
        "static",
        "templates"
    )

    directories.forEach { dir ->
        val directory = File(projectDir, dir)
        if (!directory.exists()) {
            directory.mkdirs()
            println("  Created: $dir/")
        } else {
            println("  Exists:  $dir/")
        }
    }

    println()
    println("Project structure created successfully!")
    println()
    println("Next steps:")
    println("  1. Configure your build.gradle.kts with Aether dependencies")
    println("  2. Create your model classes in src/commonMain/kotlin")
    println("  3. Define routes and controllers")
    println("  4. Run: ./gradlew run")
}

/**
 * Handles the 'startproject' command.
 */
private fun handleStartProject(args: List<String>) {
    if (args.isEmpty()) {
        println("Error: Project name is required.")
        println("Usage: aether-cli startproject <project_name>")
        return
    }

    val projectName = args[0]
    val projectDir = File(projectName)

    if (projectDir.exists()) {
        println("Error: Directory '$projectName' already exists.")
        return
    }

    println("Creating Aether project '$projectName'...")
    projectDir.mkdirs()

    // Create build.gradle.kts
    File(projectDir, "build.gradle.kts").writeText("""
        plugins {
            alias(libs.plugins.kotlin.multiplatform)
            alias(libs.plugins.kotlin.serialization)
            application
        }

        repositories {
            mavenCentral()
        }

        kotlin {
            jvm {
                compilations.all {
                    compilerOptions.configure {
                        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                    }
                }
            }
            
            sourceSets {
                val commonMain by getting {
                    dependencies {
                        implementation("codes.yousef.aether:aether-core:0.1.0")
                        implementation("codes.yousef.aether:aether-db:0.1.0")
                        implementation("codes.yousef.aether:aether-web:0.1.0")
                        implementation("codes.yousef.aether:aether-ui:0.1.0")
                    }
                }
                val jvmMain by getting {
                    dependencies {
                        implementation("ch.qos.logback:logback-classic:1.4.14")
                    }
                }
            }
        }

        application {
            mainClass.set("$projectName.MainKt")
        }
    """.trimIndent())

    // Create settings.gradle.kts
    File(projectDir, "settings.gradle.kts").writeText("""
        rootProject.name = "$projectName"
        
        dependencyResolutionManagement {
            repositories {
                mavenCentral()
            }
        }
    """.trimIndent())

    // Create gradle.properties
    File(projectDir, "gradle.properties").writeText("""
        kotlin.code.style=official
    """.trimIndent())

    // Create source structure
    val srcDir = File(projectDir, "src/jvmMain/kotlin/$projectName")
    srcDir.mkdirs()

    // Create Main.kt
    File(srcDir, "Main.kt").writeText("""
        package $projectName

        import codes.yousef.aether.web.aetherStart

        fun main() = aetherStart(port = 8080) {
            get("/") { exchange ->
                exchange.respondText("Hello, Aether!")
            }
            
            get("/health") { exchange ->
                exchange.respondJson(mapOf("status" to "healthy"))
            }
        }
    """.trimIndent())

    println("Project '$projectName' created successfully!")
}

/**
 * Handles the 'startapp' command.
 */
private fun handleStartApp(args: List<String>) {
    if (args.isEmpty()) {
        println("Error: App name is required.")
        println("Usage: aether-cli startapp <app_name>")
        return
    }

    val appName = args[0]
    val appDir = File(appName)

    if (appDir.exists()) {
        println("Error: Directory '$appName' already exists.")
        return
    }

    println("Creating Aether app '$appName'...")
    appDir.mkdirs()

    // Create build.gradle.kts
    File(appDir, "build.gradle.kts").writeText("""
        plugins {
            kotlin("multiplatform")
        }

        kotlin {
            jvm()
            sourceSets {
                val commonMain by getting {
                    dependencies {
                        implementation(project(":aether-core"))
                        implementation(project(":aether-db"))
                    }
                }
            }
        }
    """.trimIndent())

    // Create source structure
    val srcDir = File(appDir, "src/commonMain/kotlin/$appName")
    srcDir.mkdirs()

    // Create Models.kt
    File(srcDir, "Models.kt").writeText("""
        package $appName

        import codes.yousef.aether.db.Model

        // object MyModel : Model<MyEntity>() { ... }
    """.trimIndent())

    println("App '$appName' created successfully!")
    println("Don't forget to include ':$appName' in your settings.gradle.kts")
}

/**
 * Handles the 'inspectdb' command.
 */
private fun handleInspectDb(args: List<String>) {
    // Parse args
    var host = "localhost"
    var port = 5432
    var db = "postgres"
    var user = "postgres"
    var password = "postgres"

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> if (i + 1 < args.size) host = args[++i]
            "--port" -> if (i + 1 < args.size) port = args[++i].toIntOrNull() ?: 5432
            "--db" -> if (i + 1 < args.size) db = args[++i]
            "--user" -> if (i + 1 < args.size) user = args[++i]
            "--password" -> if (i + 1 < args.size) password = args[++i]
            "--help", "-h" -> {
                println("Usage: aether-cli inspectdb --host <host> --port <port> --db <db> --user <user> --password <password>")
                return
            }
        }
        i++
    }

    runBlocking {
        val driver = VertxPgDriver.create(host, port, db, user, password)
        try {
            val tables = driver.getTables()
            
            println("// Auto-generated by Aether inspectdb")
            println("package codes.yousef.aether.generated")
            println()
            println("import codes.yousef.aether.db.*")
            println("import kotlinx.serialization.Serializable")
            println()

            for (tableName in tables) {
                if (tableName.startsWith("_")) continue // Skip internal tables

                val className = tableName.split("_")
                    .joinToString("") { it.replaceFirstChar { char -> char.uppercase() } }
                val entityName = className.removeSuffix("s") // Simple plural to singular

                println("@Serializable")
                println("data class $entityName(")
                
                val columns = driver.getColumns(tableName)
                val pkColumn = columns.find { it.primaryKey }
                
                // Generate Entity class
                columns.forEachIndexed { index, col ->
                    val type = when {
                        col.type.contains("BIGINT") || col.type.contains("BIGSERIAL") -> "Long"
                        col.type.contains("INT") || col.type.contains("SERIAL") -> "Int"
                        col.type.contains("CHAR") || col.type.contains("TEXT") -> "String"
                        col.type.contains("BOOL") -> "Boolean"
                        else -> "String" // Fallback
                    }
                    val nullable = if (col.nullable || col.primaryKey) "?" else ""
                    val comma = if (index < columns.size - 1) "," else ""
                    println("    val ${col.name}: $type$nullable = null$comma")
                }
                println(") : BaseEntity<$entityName>")
                println()

                // Generate Model object
                println("object $className : Model<$entityName>() {")
                println("    override val tableName = \"$tableName\"")
                
                columns.forEach { col ->
                    val fieldType = when {
                        col.type.contains("BIGINT") || col.type.contains("BIGSERIAL") -> "long"
                        col.type.contains("INT") || col.type.contains("SERIAL") -> "integer"
                        col.type.contains("TEXT") -> "text"
                        col.type.contains("BOOL") -> "boolean"
                        else -> "varchar"
                    }
                    
                    val params = mutableListOf<String>()
                    params.add("\"${col.name}\"")
                    if (col.primaryKey) params.add("primaryKey = true")
                    if (col.autoIncrement) params.add("autoIncrement = true")
                    if (col.nullable) params.add("nullable = true")
                    if (col.unique) params.add("unique = true")
                    
                    println("    val ${col.name} = $fieldType(${params.joinToString(", ")})")
                }
                println("}")
                println()
            }
        } catch (e: Exception) {
            println("Error inspecting database: ${e.message}")
            e.printStackTrace()
        } finally {
            driver.close()
        }
    }
}

/**
 * Prints general help information.
 */
private fun printHelp() {
    println("""
       ___        __  __             
      / _ | ___  / /_/ /  ___  ____  
     / __ |/ -_)/ __/ _ \/ -_)/ __/  
    /_/ |_|\__/ \__/_//_/\__//_/     

        Aether CLI - Command line tools for the Aether framework

        Usage: aether-cli <command> [options]

        Commands:
          runserver             Start the development server
          migrate               Manage database migrations
          init [directory]      Initialize a new Aether project
          startproject [name]   Create a new Aether project structure
          startapp [name]       Create a new Aether app (module)
          inspectdb             Introspect database and generate Model classes
          shell                 Start an interactive SQL shell
          help                  Show this help message

        Examples:
          aether-cli runserver --port 8080
          aether-cli startproject myproject
          aether-cli startapp blog
          aether-cli migrate --apply
          aether-cli shell

        For command-specific help:
          aether-cli <command> --help

    """.trimIndent())
}

/**
 * Handles the 'shell' command.
 * Starts an interactive SQL shell.
 */
private fun handleShell(args: List<String>) {
    var host = System.getenv("DB_HOST") ?: "localhost"
    var port = System.getenv("DB_PORT")?.toIntOrNull() ?: 5432
    var db = System.getenv("DB_NAME") ?: "postgres"
    var user = System.getenv("DB_USER") ?: "postgres"
    var password = System.getenv("DB_PASSWORD") ?: "postgres"

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> if (i + 1 < args.size) host = args[++i]
            "--port" -> if (i + 1 < args.size) port = args[++i].toIntOrNull() ?: 5432
            "--db" -> if (i + 1 < args.size) db = args[++i]
            "--user" -> if (i + 1 < args.size) user = args[++i]
            "--password" -> if (i + 1 < args.size) password = args[++i]
            "--help", "-h" -> {
                println("Usage: aether-cli shell --host <host> --port <port> --db <db> --user <user> --password <password>")
                return
            }
        }
        i++
    }

    runBlocking {
        val driver = VertxPgDriver.create(host, port, db, user, password)
        println("Connected to postgres://$user@$host:$port/$db")
        println("Type 'exit' or 'quit' to leave.")
        println("Type SQL queries ending with ';'")
        println()

        try {
            val scanner = java.util.Scanner(System.`in`)
            var buffer = StringBuilder()

            print("aether> ")
            while (scanner.hasNextLine()) {
                val line = scanner.nextLine().trim()
                
                if (line.equals("exit", ignoreCase = true) || line.equals("quit", ignoreCase = true)) {
                    break
                }

                if (line.isEmpty()) {
                    if (buffer.isEmpty()) {
                        print("aether> ")
                    } else {
                        print("      > ")
                    }
                    continue
                }

                buffer.append(line).append(" ")

                if (line.endsWith(";")) {
                    val sql = buffer.toString().trim().removeSuffix(";")
                    buffer.clear()
                    
                    try {
                        val start = System.currentTimeMillis()
                        val rows = driver.executeQueryRaw(sql)
                        val duration = System.currentTimeMillis() - start
                        
                        if (rows.isEmpty()) {
                            println("Empty result set (${duration}ms)")
                        } else {
                            printTable(rows)
                            println("(${rows.size} rows, ${duration}ms)")
                        }
                    } catch (e: Exception) {
                        println("Error: ${e.message}")
                    }
                    println()
                    print("aether> ")
                } else {
                    print("      > ")
                }
            }
        } finally {
            driver.close()
            println("Bye!")
        }
    }
}

private fun printTable(rows: List<Row>) {
    if (rows.isEmpty()) return
    
    val columns = rows[0].getColumnNames()
    val widths = columns.map { it.length }.toMutableList()
    
    // Calculate widths
    rows.forEach { row ->
        columns.forEachIndexed { index, col ->
            val value = row.getValue(col)?.toString() ?: "NULL"
            widths[index] = kotlin.math.max(widths[index], value.length)
        }
    }
    
    // Print header
    columns.forEachIndexed { index, col ->
        print(col.padEnd(widths[index] + 2))
    }
    println()
    
    // Print separator
    columns.forEachIndexed { index, _ ->
        print("-".repeat(widths[index]).padEnd(widths[index] + 2))
    }
    println()
    
    // Print rows
    rows.forEach { row ->
        columns.forEachIndexed { index, col ->
            val value = row.getValue(col)?.toString() ?: "NULL"
            print(value.padEnd(widths[index] + 2))
        }
        println()
    }
}

/**
 * Prints help for the migrate command.
 */
private fun printMigrateHelp() {
    println("""
        Aether Migrations - Database migration management

        Usage: aether-cli migrate [options]

        Options:
          --create, -c [name]   Create a new migration file
          --apply, -a           Apply pending migrations
          --help, -h            Show this help message

        Examples:
          aether-cli migrate                          # List migrations
          aether-cli migrate --create add_users       # Create new migration
          aether-cli migrate --apply                  # Apply all pending migrations

    """.trimIndent())
}

