@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

configurations.configureEach {
    // The passkey example must not accidentally acquire Aether Core's unrelated JWT provider.
    exclude(group = "com.auth0", module = "java-jwt")
}

kotlin {
    jvm {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    wasmJs {
        browser()
        nodejs()
        binaries.executable()
    }

    jvmToolchain(21)

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":aether-auth"))
                implementation(project(":aether-auth-summon"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmMain {
            dependencies {
                // Development-only in-memory authority for this executable reference app.
                implementation(project(":aether-auth-testkit"))
                implementation(project(":aether-core"))
                implementation(project(":aether-db"))
                implementation(project(":aether-web"))
                implementation(project(":aether-ui"))
                implementation(project(":aether-forms"))
                implementation(project(":aether-admin"))
                implementation(project(":aether-signals"))
                implementation(project(":aether-tasks"))
                implementation(project(":aether-channels"))
                implementation(libs.vertx.core)
                implementation(libs.logback.classic)
            }
        }

        jvmTest {
            resources.srcDir(rootProject.file("contract-fixtures"))
            dependencies {
                implementation(libs.kotlin.test)
                implementation("org.junit.jupiter:junit-jupiter:5.10.1")
                implementation(libs.testcontainers.core)
                implementation(libs.testcontainers.postgresql)
                implementation("org.testcontainers:junit-jupiter:1.20.4")
                implementation(libs.netty.codec.http)
                implementation(libs.vertx.kotlin.coroutines)
                implementation("io.vertx:vertx-web-client:4.5.11")
                implementation("io.vertx:vertx-junit5:4.5.11")
            }
        }
    }
}

val jvmMain = kotlin.targets.named("jvm").flatMap { target ->
    target.compilations.named("main")
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the Aether example application."
    dependsOn("jvmMainClasses", "wasmJsBrowserDistribution")
    mainClass.set("codes.yousef.aether.example.MainKt")
    systemProperty(
        "aether.example.webAssets",
        layout.buildDirectory.dir("dist/wasmJs/productionExecutable").get().asFile.absolutePath
    )
    classpath(jvmMain.map { compilation -> compilation.output.allOutputs })
    classpath(jvmMain.map { compilation -> compilation.runtimeDependencyFiles ?: files() })
}
