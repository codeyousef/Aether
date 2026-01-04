plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilations.all {
            compilerOptions.configure {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            }
        }
    }

    wasmJs {
        browser {
            testTask {
                enabled = false  // Skip browser tests, use nodejs instead
            }
        }
    }

    wasmWasi {
        nodejs()
    }

    jvmToolchain(21)

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":aether-core"))
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.cbor)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.slf4j.api)
            }
        }
    }
}
