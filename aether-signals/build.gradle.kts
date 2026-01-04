plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.atomicfu)
}

kotlin {
    jvm {
        compilations.all {
            compilerOptions.configure {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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

    jvmToolchain(21)

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
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
