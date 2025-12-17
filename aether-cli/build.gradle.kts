plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
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
    }

    jvmToolchain(21)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":aether-core"))
                implementation(project(":aether-db"))
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

application {
    mainClass.set("codes.yousef.aether.cli.MainKt")
}
