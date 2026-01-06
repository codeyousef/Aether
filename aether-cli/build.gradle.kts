plugins {
    kotlin("jvm")
    alias(libs.plugins.ksp)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":aether-core"))
    implementation(project(":aether-db"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.vertx.pg.client)
}

application {
    mainClass.set("codes.yousef.aether.cli.MainKt")
}
