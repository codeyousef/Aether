pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "aether-private-suite-consumer"

val aetherRepository = providers.gradleProperty("aetherRepository").orNull
    ?: error("Missing -PaetherRepository=<isolated Maven repository>")

dependencyResolutionManagement {
    repositories {
        maven { url = uri(aetherRepository) }
        mavenCentral()
        google()
    }
}

fun requiredSourceProperty(name: String): String =
    providers.gradleProperty(name).orNull
        ?: error("Missing -P$name=<absolute checkout path>")

fun verifySourcePin(path: String, expectedCommit: String, label: String) {
    val checkout = file(path).canonicalFile
    require(checkout.isDirectory) { "$label source checkout does not exist: $checkout" }
    val actualCommit = providers.exec {
        workingDir(checkout)
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim()
    require(actualCommit == expectedCommit) {
        "$label source pin mismatch: expected $expectedCommit, found $actualCommit at $checkout"
    }
}

val aetherSource = requiredSourceProperty("aetherSource")
val aetherCommit = requiredSourceProperty("aetherCommit")
verifySourcePin(aetherSource, aetherCommit, "Aether")

val summonSource = requiredSourceProperty("summonSource")
val summonCommit = requiredSourceProperty("summonCommit")
verifySourcePin(summonSource, summonCommit, "Summon")
