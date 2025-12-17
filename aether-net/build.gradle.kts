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
        browser()
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
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.vertx.core)
                implementation(libs.vertx.kotlin.coroutines)
            }
        }
    }
}
