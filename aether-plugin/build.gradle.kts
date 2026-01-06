plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(gradleApi())
    implementation(libs.kotlin.stdlib)
}

gradlePlugin {
    plugins {
        create("aetherPlugin") {
            id = "codes.yousef.aether.plugin"
            implementationClass = "codes.yousef.aether.plugin.AetherPlugin"
        }
    }
}
