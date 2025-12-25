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

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":aether-core"))
                implementation(project(":aether-db"))
            }
        }
        
        jvmMain {
            dependencies {
                implementation(libs.bcrypt)
            }
        }
    }
}
