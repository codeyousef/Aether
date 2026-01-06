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
                implementation(project(":aether-core"))
                implementation(project(":aether-db"))
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
                implementation(libs.bcrypt)
                implementation(libs.java.jwt)
            }
        }

        jvmTest {
            dependencies {

            }
        }
    }
}
