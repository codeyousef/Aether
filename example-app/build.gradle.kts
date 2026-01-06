plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
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
    implementation(project(":aether-web"))
    implementation(project(":aether-ui"))
    implementation(project(":aether-auth"))
    implementation(project(":aether-forms"))
    implementation(project(":aether-admin"))
    implementation(project(":aether-signals"))
    implementation(project(":aether-tasks"))
    implementation(project(":aether-channels"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.vertx.core)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation(libs.netty.codec.http)
    testImplementation(libs.vertx.kotlin.coroutines)
    testImplementation("io.vertx:vertx-web-client:4.5.11")
    testImplementation("io.vertx:vertx-junit5:4.5.11")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("codes.yousef.aether.example.MainKt")
}
