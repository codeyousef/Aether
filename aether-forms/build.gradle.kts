plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    wasmJs {
        browser()
        nodejs()
    }
    wasmWasi {
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":aether-core"))
                implementation(project(":aether-ui"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
