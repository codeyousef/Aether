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
                implementation(project(":aether-core"))
                implementation(project(":aether-signals"))
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
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
                implementation(libs.vertx.sql.client)
                implementation(libs.vertx.pg.client)
                implementation(libs.vertx.kotlin.coroutines)
                implementation(libs.hikaricp)
                implementation(libs.postgres.driver)
                implementation(libs.slf4j.api)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.logback.classic)
                implementation(libs.testcontainers.core)
                implementation(libs.testcontainers.postgresql)
                implementation(libs.wiremock)
                implementation(libs.mockk)
            }
        }
    }
}
