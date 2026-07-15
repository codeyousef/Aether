@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            // Testcontainers 1.x otherwise negotiates Docker API 1.32, rejected by current daemons.
            systemProperty("api.version", "1.40")
        }
    }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    sourceSets {
        commonMain.dependencies {
            api(project(":aether-auth"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":aether-auth-testkit"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.vertx.pg.client)
            implementation(libs.vertx.kotlin.coroutines)
            // Optional in Vert.x's POM, but required by PostgreSQL's default SCRAM authentication.
            implementation("com.ongres.scram:client:2.1")
        }
        jvmTest.dependencies {
            implementation(libs.testcontainers.postgresql)
            implementation(libs.logback.classic)
        }
    }
}
