plugins {
    kotlin("jvm")
    application
}

configurations.configureEach {
    // The identity CLI exchanges only opaque RFC 8628 credentials.
    exclude(group = "com.auth0", module = "java-jwt")
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }

    sourceSets {
        main {
            kotlin.srcDir("src/jvmMain/kotlin")
        }
        test {
            kotlin.srcDir("src/jvmTest/kotlin")
        }
    }
}

dependencies {
    implementation(project(":aether-core"))
    implementation(project(":aether-db"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.vertx.pg.client)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("codes.yousef.aether.cli.MainKt")
}
