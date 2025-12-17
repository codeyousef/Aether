plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvm {
        compilations.all {
            compilerOptions.configure {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            }
        }
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    jvmToolchain(21)

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":aether-core"))
                implementation(project(":aether-db"))
                implementation(project(":aether-web"))
                implementation(project(":aether-ui"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.vertx.core)
                implementation(libs.logback.classic)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation("org.junit.jupiter:junit-jupiter:5.10.1")
                implementation(libs.testcontainers.core)
                implementation(libs.testcontainers.postgresql)
                implementation("org.testcontainers:junit-jupiter:1.20.4")
            }
        }
    }
}

application {
    mainClass.set("codes.yousef.aether.example.MainKt")
}
