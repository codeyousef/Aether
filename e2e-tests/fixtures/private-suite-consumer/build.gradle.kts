import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
}

group = "codes.yousef.aether.fixture"
version = "0.1.0"

repositories {
    maven { url = uri(providers.gradleProperty("aetherRepository").get()) }
    mavenCentral()
    google()
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

val duplicateVersionProbe by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.failOnVersionConflict()
    resolutionStrategy.deactivateDependencyLocking()
}

dependencies {
    duplicateVersionProbe("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    duplicateVersionProbe("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.register("resolveDuplicateVersionProbe") {
    group = "verification"
    description = "Must fail visibly because the probe declares conflicting coroutine versions."
    doLast {
        duplicateVersionProbe.resolve()
    }
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    js(IR) {
        browser()
        binaries.executable()
    }

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation("codes.yousef:summon:0.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation("codes.yousef.aether:aether-core:0.7.0.0")
            implementation("codes.yousef.aether:aether-web:0.7.0.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation("org.junit.jupiter:junit-jupiter:5.10.1")
        }
        jsMain.dependencies {
            implementation("codes.yousef.aether:aether-browser-client:0.7.0.0")
        }
    }
}

val verifyBrowserDistributionBoundary by tasks.registering {
    group = "verification"
    description = "Rejects authority, server runtime, admin, PostgreSQL, or synthetic secrets in the browser distribution."
    dependsOn("jsBrowserDistribution")

    val distribution = layout.buildDirectory.dir("dist/js/productionExecutable")
    inputs.dir(distribution)
    doLast {
        val forbidden = listOf(
            "codes.yousef.aether.auth",
            "codes.yousef.aether.admin",
            "org.postgresql",
            "VertxPgDriver",
            "java-jwt",
            "AE00_PRIVATE_MARKER"
        )
        val files = distribution.get().asFileTree.files.filter(File::isFile)
        check(files.isNotEmpty()) { "Kotlin/JS browser distribution is empty" }
        val leaks = files.flatMap { file ->
            val text = file.readText()
            forbidden.filter(text::contains).map { marker -> "${file.relativeTo(projectDir)}: $marker" }
        }
        check(leaks.isEmpty()) { "Forbidden browser distribution content:\n${leaks.joinToString("\n")}" }
    }
}

val verifyNoProductionWasi by tasks.registering {
    group = "verification"
    description = "Confirms the R1 consumer has no production WASI target or output."
    doLast {
        val wasiTasks = tasks.names.filter { it != name && it.contains("wasi", ignoreCase = true) }
        check(wasiTasks.isEmpty()) { "Production WASI tasks are present: ${wasiTasks.sorted()}" }
        val wasmOutputs = layout.buildDirectory.asFile.get().walkTopDown()
            .filter { it.isFile && it.extension == "wasm" }
            .toList()
        check(wasmOutputs.isEmpty()) { "Production WASM/WASI output is present: $wasmOutputs" }
    }
}

tasks.register("verifyAeT00") {
    group = "verification"
    description = "Compiles JVM and browser main/tests and verifies the AE-T00 packaging boundary."
    dependsOn(
        "jvmMainClasses",
        "jvmTestClasses",
        "compileKotlinJs",
        "compileTestKotlinJs",
        verifyBrowserDistributionBoundary,
        verifyNoProductionWasi
    )
}
