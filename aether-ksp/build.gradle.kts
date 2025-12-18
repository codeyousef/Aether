plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilations.all {
            compilerOptions.configure {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            }
        }
    }

    jvmToolchain(21)

    sourceSets {
        jvmMain {
            dependencies {
                implementation(project(":aether-db"))
                implementation(libs.ksp.api)
                implementation(libs.kotlinpoet)
                implementation(libs.kotlinpoet.ksp)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
