@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    sourceSets {
        commonMain.dependencies {
            api(project(":aether-auth"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":aether-auth-testkit"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlin.test)
        }
    }
}

// A separate, never-up-to-date execution of the JVM test compilation. The test itself is skipped
// during ordinary `jvmTest`; this task turns on the release gate and fails if the real emulator is
// absent, unreachable, or violates the adapter's atomicity contract.
val jvmTestTask = tasks.named<Test>("jvmTest")
tasks.register<Test>("firestoreEmulatorTest") {
    group = "verification"
    description = "Runs the identity store race suite through the real Firestore REST emulator"
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "codes.yousef.aether.auth.firestore.FirestoreIdentityStoreEmulatorTest"
        )
    }
    systemProperty("aether.firestore.emulator.gate", "true")
    outputs.upToDateWhen { false }
}
