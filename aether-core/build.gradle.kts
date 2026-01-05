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
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.cbor)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.datetime)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.vertx.core)
                implementation(libs.vertx.kotlin.coroutines)
                implementation(libs.slf4j.api)
                implementation(libs.java.jwt)
                implementation(libs.kotlinx.datetime)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.logback.classic)
                implementation(libs.mockk)
                implementation("org.junit.jupiter:junit-jupiter:5.10.1")
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
