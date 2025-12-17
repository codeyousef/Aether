package codes.yousef.aether.cli

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * Aether CLI - Command line tools for the Aether framework.
 *
 * Available commands:
 * - migrate: Generate and apply database migrations
 * - init: Initialize a new Aether project
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
            "migrate" -> handleMigrate(args.drop(1))
            "init" -> handleInit(args.drop(1))
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

    // In a full implementation, this would:
    // 1. Connect to the database
    // 2. Check which migrations have already been applied
    // 3. Execute pending migrations in order
    // 4. Record applied migrations in a migrations table

    migrations.forEach { migration ->
        println("  [DRY RUN] Would apply: ${migration.name}")
    }

    println()
    println("Note: This is a dry run. Full implementation requires database connection.")
    println("      Configure your database connection in application.conf")
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
 * Prints general help information.
 */
private fun printHelp() {
    println("""
        Aether CLI - Command line tools for the Aether framework

        Usage: aether-cli <command> [options]

        Commands:
          migrate               Manage database migrations
          init [directory]      Initialize a new Aether project
          help                  Show this help message

        Examples:
          aether-cli migrate --create add_users_table
          aether-cli migrate --apply
          aether-cli init my-project
          aether-cli help

        For command-specific help:
          aether-cli <command> --help

    """.trimIndent())
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
