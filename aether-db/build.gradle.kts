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
            // Testcontainers 1.20 defaults to Docker API 1.32; current Docker requires at least 1.40.
            systemProperty("api.version", System.getProperty("api.version") ?: "1.40")
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
                // QueryAST and its public model types are @Serializable. Their
                // generated companions expose serialization runtime supertypes,
                // so consumers need the core runtime on their compile classpath.
                api(libs.kotlinx.serialization.core)
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
                // Optional in Vert.x's POM, but required by PostgreSQL's default SCRAM authentication.
                implementation("com.ongres.scram:client:2.1")
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
