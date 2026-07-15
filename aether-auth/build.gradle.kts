@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilations.all {
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
        browser {
            testTask {
                enabled = false  // Skip browser tests, use nodejs instead
            }
        }
        nodejs()
    }
    wasmWasi {
        nodejs()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":aether-core")) {
                    // Aether Identity uses only core HTTP/pipeline contracts. Keep the generic
                    // core JWT provider available to unrelated applications without placing its
                    // implementation on the passkey authority runtime classpath.
                    exclude(group = "com.auth0", module = "java-jwt")
                }
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

    }
}

// Kotlin project dependencies consume the JVM archive, so keep its task edge explicit. Without
// this edge Gradle can publish a manifest-only archive while the KMP compilation is still running,
// leaving downstream identity modules with an empty compile classpath.
tasks.named<Jar>("jvmJar") {
    val mainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(mainCompilation.compileTaskProvider)
    from(mainCompilation.output.allOutputs)
}
