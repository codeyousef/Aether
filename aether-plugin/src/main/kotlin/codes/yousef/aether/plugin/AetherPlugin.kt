package codes.yousef.aether.plugin

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Gradle plugin for Aether framework projects.
 *
 * This plugin provides tasks and configuration for Aether applications,
 * including project initialization, JVM server execution, and Wasm deployment.
 *
 * Apply this plugin in your build.gradle.kts:
 * ```
 * plugins {
 *     id("codes.yousef.aether.plugin")
 * }
 *
 * aether {
 *     jvmPort = 8080
 *     enableHotReload = true
 * }
 * ```
 */
class AetherPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Create extension for configuration
        val extension = project.extensions.create("aether", AetherExtension::class.java)

        // Register tasks
        project.tasks.register("aetherInit", AetherInitTask::class.java)
        project.tasks.register("aetherRunJvm", AetherRunJvmTask::class.java)
        project.tasks.register("aetherRunWasm", AetherRunWasmTask::class.java)

        // Configure after project evaluation
        project.afterEvaluate(object : Action<Project> {
            override fun execute(evaluatedProject: Project) {
                println("Aether plugin configured for project: ${evaluatedProject.name}")
                println("  JVM Port: ${extension.jvmPort}")
                println("  Hot Reload: ${extension.enableHotReload}")
            }
        })
    }
}

/**
 * Task for initializing an Aether project structure.
 */
abstract class AetherInitTask : DefaultTask() {
    init {
        group = "aether"
        description = "Initialize a new Aether project structure"
    }

    @TaskAction
    fun run() {
        println("Initializing Aether project structure...")

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
            val directory = File(project.projectDir, dir)
            if (!directory.exists()) {
                directory.mkdirs()
                println("  Created: $dir/")
            } else {
                println("  Exists:  $dir/")
            }
        }

        // Create sample application file
        val sampleApp = File(project.projectDir, "src/jvmMain/kotlin/Application.kt")
        if (!sampleApp.exists()) {
            sampleApp.parentFile.mkdirs()
            sampleApp.writeText("""
                import codes.yousef.aether.core.Exchange
                import codes.yousef.aether.web.router

                fun main() {
                    val app = router {
                        get("/") { exchange: Exchange ->
                            exchange.respond("Hello, Aether!")
                        }
                    }

                    println("Aether application started")
                }
            """.trimIndent())
            println("  Created: src/jvmMain/kotlin/Application.kt")
        }

        println()
        println("Aether project initialized successfully!")
        println("Next steps:")
        println("  1. Review src/jvmMain/kotlin/Application.kt")
        println("  2. Run: ./gradlew aetherRunJvm")
    }
}

/**
 * Task for running the Aether application on JVM.
 */
abstract class AetherRunJvmTask : JavaExec() {
    init {
        group = "aether"
        description = "Run the Aether application on JVM with Vert.x"
    }

    @TaskAction
    override fun exec() {
        val extension = project.extensions.getByType(AetherExtension::class.java)

        println("Starting Aether JVM server...")
        println("  Port: ${extension.jvmPort}")
        println("  Environment: ${extension.environment}")
        println("  Hot Reload: ${extension.enableHotReload}")
        println()

        // Note: In a real implementation, this would configure
        // classpath and main class properly
        super.exec()
    }
}

/**
 * Task for running the Aether application on Wasm.
 */
abstract class AetherRunWasmTask : Exec() {
    init {
        group = "aether"
        description = "Run the Aether application on Wasm"
    }

    @TaskAction
    override fun exec() {
        val extension = project.extensions.getByType(AetherExtension::class.java)

        println("Starting Aether Wasm application...")
        println("  Target: ${extension.wasmTarget}")
        println()

        when (extension.wasmTarget) {
            "cloudflare" -> {
                println("Using Cloudflare Workers (wrangler)...")
                println("Make sure wrangler is installed: npm install -g wrangler")
                commandLine("wrangler", "dev")
            }
            "browser" -> {
                println("Starting local development server for browser...")
                commandLine(
                    "python3", "-m", "http.server",
                    extension.wasmPort.toString()
                )
            }
            else -> {
                throw IllegalArgumentException(
                    "Unknown Wasm target: ${extension.wasmTarget}. " +
                    "Supported: 'cloudflare', 'browser'"
                )
            }
        }

        super.exec()
    }
}

/**
 * Extension for configuring Aether plugin settings.
 *
 * Usage in build.gradle.kts:
 * ```
 * aether {
 *     jvmPort = 8080
 *     mainClass = "com.example.ApplicationKt"
 *     enableHotReload = true
 *     wasmTarget = "cloudflare"
 * }
 * ```
 */
open class AetherExtension {
    /**
     * Port for the JVM server.
     * Default: 8080
     */
    var jvmPort: Int = 8080

    /**
     * Port for the Wasm development server (browser mode).
     * Default: 8000
     */
    var wasmPort: Int = 8000

    /**
     * Main class for the JVM application.
     * Default: "ApplicationKt"
     */
    var mainClass: String = "ApplicationKt"

    /**
     * Enable hot reload for development.
     * Default: true
     */
    var enableHotReload: Boolean = true

    /**
     * Environment (development, production, test).
     * Default: "development"
     */
    var environment: String = "development"

    /**
     * Wasm deployment target (cloudflare, browser).
     * Default: "browser"
     */
    var wasmTarget: String = "browser"

    /**
     * Path to migrations directory.
     * Default: "migrations"
     */
    var migrationsPath: String = "migrations"

    /**
     * Database connection URL for migrations.
     * Default: null (not configured)
     */
    var databaseUrl: String? = null
}
