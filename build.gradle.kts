import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.atomicfu) apply false
    alias(libs.plugins.ksp) apply false
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        load(localFile.inputStream())
    }
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        load(versionPropsFile.inputStream())
    }
}

group = "codes.yousef.aether"
version = versionProps.getProperty("VERSION")
    ?: error("version.properties must define VERSION")

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        google()
        mavenCentral()
    }

    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
    }
}

subprojects {
    apply(plugin = "maven-publish")

    val javadocJar by tasks.registering(Jar::class) {
        archiveClassifier.set("javadoc")
        from(rootProject.file("README.md"))
    }

    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication> {
            artifact(javadocJar)
            pom {
                name.set(project.name)
                description.set("Aether - A Kotlin Multiplatform Web Framework")
                url.set("https://github.com/codeyousef/aether")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("codeyousef")
                        name.set("Yousef")
                        email.set("code.yousef@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/codeyousef/aether")
                    connection.set("scm:git:git://github.com/codeyousef/aether.git")
                    developerConnection.set("scm:git:ssh://github.com/codeyousef/aether.git")
                }
            }
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    tasks.register("resolveAndLockAll") {
        group = "verification"
        description = "Resolves every lockable configuration; run with --write-locks after dependency changes."
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { configuration ->
                configuration.resolve()
            }
        }
    }
}

tasks.register("resolveAndLockAll") {
    group = "verification"
    description = "Resolves and locks dependencies in every Aether module."
    dependsOn(subprojects.map { ":${it.name}:resolveAndLockAll" })
}

fun expectedKmpCompileTasks(module: String, targets: List<String>): List<String> =
    targets.flatMap { target ->
        listOf(
            ":$module:compileKotlin$target",
            ":$module:compileTestKotlin$target"
        )
    }

val identityAuthorityTargets = listOf("Jvm", "WasmJs", "WasmWasi")
val expectedSourceTaskPaths =
    expectedKmpCompileTasks("aether-auth", identityAuthorityTargets) +
        expectedKmpCompileTasks("aether-auth-postgresql", identityAuthorityTargets) +
        expectedKmpCompileTasks("aether-auth-firestore", identityAuthorityTargets) +
        expectedKmpCompileTasks("aether-auth-oidc", identityAuthorityTargets) +
        expectedKmpCompileTasks("aether-auth-saml", identityAuthorityTargets) +
        expectedKmpCompileTasks("aether-auth-scim", identityAuthorityTargets) +
        expectedKmpCompileTasks("aether-auth-testkit", identityAuthorityTargets) +
        expectedKmpCompileTasks("aether-auth-summon", listOf("Jvm", "WasmJs")) +
        listOf(
            ":aether-cli:compileKotlin",
            ":aether-cli:compileTestKotlin"
        ) +
        expectedKmpCompileTasks("example-app", listOf("Jvm", "WasmJs"))

val verifyIdentityRuntimeClasspaths by tasks.registering {
    group = "verification"
    description = "Fails if legacy JWT or optional adapter/UI dependencies leak into identity runtimes."

    doLast {
        data class Guard(
            val projectPath: String,
            val configurationName: String,
            val forbidden: Set<Pair<String, String>>
        )

        val legacyJwt = "com.auth0" to "java-jwt"
        val coreForbidden = setOf(
            legacyJwt,
            "codes.yousef.aether" to "aether-auth-postgresql",
            "codes.yousef.aether" to "aether-auth-firestore",
            "codes.yousef.aether" to "aether-auth-summon",
            "codes.yousef.aether" to "aether-auth-oidc",
            "codes.yousef.aether" to "aether-auth-saml",
            "codes.yousef.aether" to "aether-auth-scim",
            "codes.yousef.summon" to "summon"
        )
        fun runtimeGuards(projectPath: String, forbidden: Set<Pair<String, String>>): List<Guard> =
            project(projectPath).configurations
                .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
                .map { Guard(projectPath, it.name, forbidden) }

        val guards = runtimeGuards(":aether-auth", coreForbidden) +
            runtimeGuards(":aether-cli", setOf(legacyJwt)) +
            runtimeGuards(":example-app", setOf(legacyJwt))

        guards.forEach { guard ->
            val guardedProject = project(guard.projectPath)
            val configuration = guardedProject.configurations.getByName(guard.configurationName)
            val leaked = configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
                val module = component.moduleVersion ?: return@mapNotNull null
                (module.group to module.name).takeIf { it in guard.forbidden }
            }.toSet()
            if (leaked.isNotEmpty()) {
                throw GradleException(
                    "Forbidden identity runtime dependencies in ${guard.projectPath}:${guard.configurationName}: " +
                        leaked.joinToString { (group, name) -> "$group:$name" }
                )
            }
        }
    }
}

tasks.register("verifyExpectedSourceTasks") {
    group = "verification"
    description = "Fails when an expected identity, CLI, or example compilation is silently skipped as NO-SOURCE."
    dependsOn(expectedSourceTaskPaths)
    dependsOn(verifyIdentityRuntimeClasspaths)

    doLast {
        val tasksByPath = gradle.taskGraph.allTasks.associateBy { it.path }
        val missing = expectedSourceTaskPaths.filterNot(tasksByPath::containsKey)
        if (missing.isNotEmpty()) {
            throw GradleException("Expected source tasks were not scheduled: ${missing.joinToString()}")
        }

        val noSource = expectedSourceTaskPaths.filter { tasksByPath.getValue(it).state.noSource }
        if (noSource.isNotEmpty()) {
            throw GradleException("Expected source tasks reported NO-SOURCE: ${noSource.joinToString()}")
        }
    }
}

val centralPortalModules = listOf(
    "aether-core",
    "aether-signals",
    "aether-tasks",
    "aether-channels",
    "aether-db",
    "aether-web",
    "aether-ui",
    "aether-net",
    "aether-ksp",
    "aether-plugin",
    "aether-auth",
    "aether-auth-postgresql",
    "aether-auth-firestore",
    "aether-auth-summon",
    "aether-auth-oidc",
    "aether-auth-saml",
    "aether-auth-scim",
    "aether-forms",
    "aether-admin",
    "aether-grpc"
)
val centralPortalVariants = listOf("", "-jvm", "-wasm-js", "-wasm-wasi")
val centralPluginMarkerArtifactId = "codes.yousef.aether.plugin.gradle.plugin"
val centralPluginMarkerGroupPath = "codes/yousef/aether/plugin"
val centralPortalArtifactIds = centralPortalModules.flatMap { module ->
    when (module) {
        "aether-plugin" -> listOf(module)
        "aether-ksp" -> listOf(module, "$module-jvm")
        "aether-auth-summon" -> listOf(module, "$module-jvm", "$module-wasm-js")
        else -> centralPortalVariants.map { variant -> "$module$variant" }
    }
}
val centralDeploymentIdPattern = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

fun centralExpectedPurls(version: String): Set<String> = buildSet {
    check(centralPortalArtifactIds.size == centralPortalArtifactIds.toSet().size) {
        "Central publication artifact IDs must be unique"
    }
    centralPortalArtifactIds.forEach { artifactId ->
        add("pkg:maven/codes.yousef.aether/$artifactId@$version")
    }
    add("pkg:maven/codes.yousef.aether.plugin/$centralPluginMarkerArtifactId@$version")
}.also { purls ->
    check(centralPortalArtifactIds.size == 74 && purls.size == 75) {
        "Central coordinate manifest must contain exactly 74 artifacts plus the plugin marker"
    }
}

fun centralExpectedReportedPurls(version: String): Set<String> {
    val basePurls = centralExpectedPurls(version)
    val klibArtifactIds = centralPortalArtifactIds
        .filter { artifactId -> artifactId.endsWith("-wasm-js") || artifactId.endsWith("-wasm-wasi") }
    check(klibArtifactIds.size == 35) { "Central status manifest must contain exactly 35 KLIB variants" }
    return buildSet {
        addAll(basePurls)
        klibArtifactIds.forEach { artifactId ->
            add("pkg:maven/codes.yousef.aether/$artifactId@$version?type=klib")
        }
        add("pkg:maven/codes.yousef.aether.plugin/$centralPluginMarkerArtifactId@$version?type=pom")
    }.also { reportedPurls ->
        check(reportedPurls.size == 111 && reportedPurls.containsAll(basePurls)) {
            "Central status manifest must contain exactly 111 PURLs including all release coordinates"
        }
    }
}

val writeExpectedCentralPurls by tasks.registering {
    group = "publishing"
    description = "Writes the exact component set expected in a Central Portal deployment."
    val outputFile = layout.buildDirectory.file("central-expected-purls.txt")
    val expectedPurls = provider { centralExpectedPurls(project.version.toString()).sorted() }
    inputs.property("expectedPurls", expectedPurls)
    outputs.file(outputFile)

    doLast {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(expectedPurls.get().joinToString("\n", postfix = "\n"))
        }
    }
}

val writeExpectedCentralReportedPurls by tasks.registering {
    group = "publishing"
    description = "Writes the exact PURL set reported by Central Portal for the release components."
    val outputFile = layout.buildDirectory.file("central-expected-reported-purls.txt")
    val expectedPurls = provider { centralExpectedReportedPurls(project.version.toString()).sorted() }
    inputs.property("expectedPurls", expectedPurls)
    outputs.file(outputFile)

    doLast {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(expectedPurls.get().joinToString("\n", postfix = "\n"))
        }
    }
}

val verifyCentralPublicationArtifacts by tasks.registering {
    group = "verification"
    description = "Verifies every Maven Central component has its required primary, sources, and Javadoc artifacts."
    dependsOn(centralPortalModules.map { ":$it:publishToMavenLocal" })

    doLast {
        val releaseVersion = project.version.toString()
        val mavenLocalRepository = System.getProperty("maven.repo.local")
            ?.takeIf { it.isNotBlank() }
            ?.let(::file)
            ?: file("${System.getProperty("user.home")}/.m2/repository")
        val failures = mutableListOf<String>()
        var validatedCoordinates = 0

        fun verifyCoordinate(groupPath: String, artifactId: String, required: Boolean) {
            val coordinate = "${groupPath.replace('/', '.')}:$artifactId:$releaseVersion"
            val publicationDir = file("$mavenLocalRepository/$groupPath/$artifactId/$releaseVersion")
            if (!publicationDir.isDirectory) {
                if (required) failures += "$coordinate: publication directory is missing"
                return
            }

            val prefix = "$artifactId-$releaseVersion"
            val pom = File(publicationDir, "$prefix.pom")
            if (!pom.isFile || pom.length() == 0L) {
                failures += "$coordinate: POM is missing or empty"
                return
            }

            val pomOnly = Regex("<packaging>\\s*pom\\s*</packaging>", RegexOption.IGNORE_CASE)
                .containsMatchIn(pom.readText())
            if (pomOnly) {
                validatedCoordinates++
                return
            }

            val primaryArtifacts = listOf(
                File(publicationDir, "$prefix.jar"),
                File(publicationDir, "$prefix.klib")
            ).filter { it.isFile && it.length() > 0L }
            if (primaryArtifacts.isEmpty()) {
                failures += "$coordinate: primary JAR or KLIB is missing or empty"
            }

            val sourcesJar = File(publicationDir, "$prefix-sources.jar")
            if (!sourcesJar.isFile || sourcesJar.length() == 0L) {
                failures += "$coordinate: sources JAR is missing or empty"
            } else if (artifactId == "aether-plugin") {
                val hasSources = runCatching {
                    ZipFile(sourcesJar).use { archive ->
                        archive.entries().asSequence().any { entry ->
                            !entry.isDirectory &&
                                (entry.name.endsWith(".kt") || entry.name.endsWith(".java"))
                        }
                    }
                }.getOrDefault(false)
                if (!hasSources) failures += "$coordinate: sources JAR contains no Kotlin or Java source"
            }

            val javadocJar = File(publicationDir, "$prefix-javadoc.jar")
            if (!javadocJar.isFile || javadocJar.length() == 0L) {
                failures += "$coordinate: Javadoc JAR is missing or empty"
            }
            validatedCoordinates++
        }

        centralPortalArtifactIds.forEach { artifactId ->
            verifyCoordinate(
                groupPath = "codes/yousef/aether",
                artifactId = artifactId,
                required = true
            )
        }
        verifyCoordinate(
            groupPath = centralPluginMarkerGroupPath,
            artifactId = centralPluginMarkerArtifactId,
            required = true
        )

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Maven Central publication preflight failed:\n" +
                    failures.distinct().sorted().joinToString("\n") { " - $it" }
            )
        }
        println("Verified $validatedCoordinates Maven Central publication coordinates in $mavenLocalRepository")
    }
}

val prepareCentralPortalBundle by tasks.registering {
    group = "publishing"
    description = "Builds, validates, signs, and archives the exact Central Portal publication set."
    dependsOn(verifyCentralPublicationArtifacts, writeExpectedCentralPurls)

    doLast {
        val bundleDir = layout.buildDirectory.dir("central-portal-bundle").get().asFile
        bundleDir.deleteRecursively()
        bundleDir.mkdirs()

        val mavenLocalRepository = System.getProperty("maven.repo.local")
            ?.takeIf(String::isNotBlank)
            ?.let(::file)
            ?: file("${System.getProperty("user.home")}/.m2/repository")
        val releaseVersion = project.version.toString()
        val allFilesToProcess = mutableListOf<File>()
        val missingCoordinates = mutableListOf<String>()

        fun collectCoordinate(groupPath: String, artifactId: String) {
            val sourceDir = file("$mavenLocalRepository/$groupPath/$artifactId/$releaseVersion")
            val artifactFiles = sourceDir.listFiles().orEmpty().filter { source ->
                source.extension in setOf("jar", "pom", "klib", "module")
            }
            if (artifactFiles.isEmpty()) {
                missingCoordinates += "${groupPath.replace('/', '.')}:$artifactId:$releaseVersion"
                return
            }
            val targetDir = file("$bundleDir/$groupPath/$artifactId/$releaseVersion").apply(File::mkdirs)
            artifactFiles.forEach { source ->
                val target = File(targetDir, source.name)
                source.copyTo(target, overwrite = true)
                allFilesToProcess += target
            }
        }

        centralPortalArtifactIds.forEach { artifactId ->
            collectCoordinate("codes/yousef/aether", artifactId)
        }
        collectCoordinate(centralPluginMarkerGroupPath, centralPluginMarkerArtifactId)

        if (missingCoordinates.isNotEmpty()) {
            throw GradleException(
                "Required Maven publications are missing: " + missingCoordinates.sorted().joinToString()
            )
        }

        val privateKeyFile = rootProject.file("private-key.asc")
        if (!privateKeyFile.isFile) throw GradleException("private-key.asc not found; cannot sign artifacts")
        val signScript = rootProject.file("sign-artifact.sh")
        if (!signScript.isFile) throw GradleException("sign-artifact.sh not found; cannot sign artifacts")
        val signingPassword = localProperties.getProperty("signingPassword")
            ?: System.getenv("signingPassword")
            ?: throw GradleException("signingPassword not found")

        println("Generating checksums and signatures for ${allFilesToProcess.size} artifacts...")
        allFilesToProcess.forEach { source ->
            val md5 = MessageDigest.getInstance("MD5").digest(source.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            File(source.parent, "${source.name}.md5").writeText(md5)
            val sha1 = MessageDigest.getInstance("SHA-1").digest(source.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            File(source.parent, "${source.name}.sha1").writeText(sha1)

            exec {
                environment("AETHER_SIGNING_PASSPHRASE", signingPassword)
                commandLine(
                    "bash",
                    signScript.absolutePath,
                    privateKeyFile.absolutePath,
                    File(source.parent, "${source.name}.asc").absolutePath,
                    source.absolutePath
                )
            }
        }

        val filesInBundle = bundleDir.walkTopDown().filter(File::isFile)
            .sortedBy { it.relativeTo(bundleDir).invariantSeparatorsPath }
            .toList()
        if (filesInBundle.isEmpty()) throw GradleException("Central Portal bundle is empty")

        val zipFile = layout.buildDirectory.file("central-portal-bundle.zip").get().asFile
        zipFile.parentFile.mkdirs()
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            filesInBundle.forEach { source ->
                val entry = ZipEntry(source.relativeTo(bundleDir).invariantSeparatorsPath).apply { time = 0L }
                zip.putNextEntry(entry)
                source.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (zipFile.length() == 0L) throw GradleException("Central Portal bundle ZIP is empty")
        println("Prepared ${filesInBundle.size} bundle entries (${zipFile.length() / 1024} KiB)")
    }
}

val uploadCentralPortalBundle by tasks.registering {
    group = "publishing"
    description = "Uploads exactly one prepared bundle and records the Central deployment ID."
    dependsOn(prepareCentralPortalBundle)

    doFirst {
        if (!System.getenv("AETHER_CENTRAL_DEPLOYMENT_ID").isNullOrBlank()) {
            throw GradleException(
                "uploadCentralPortalBundle cannot resume an existing deployment; " +
                    "run waitForCentralPortalPublication with that deployment ID"
            )
        }
    }

    doLast {
        val username = localProperties.getProperty("mavenCentralUsername")
            ?: System.getenv("mavenCentralUsername")
            ?: throw GradleException("mavenCentralUsername not found")
        val password = localProperties.getProperty("mavenCentralPassword")
            ?: System.getenv("mavenCentralPassword")
            ?: throw GradleException("mavenCentralPassword not found")
        val deploymentName = System.getenv("AETHER_CENTRAL_DEPLOYMENT_NAME")
            ?.trim()?.takeIf { it.matches(Regex("^[A-Za-z0-9._-]{1,128}$")) }
            ?: throw GradleException("AETHER_CENTRAL_DEPLOYMENT_NAME must contain only letters, digits, dot, underscore, or hyphen")
        val authorization = "Bearer " + Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray())
        val zipFile = layout.buildDirectory.file("central-portal-bundle.zip").get().asFile
        if (!zipFile.isFile || zipFile.length() == 0L) throw GradleException("Prepared bundle ZIP is missing")

        val responseFile = layout.buildDirectory.file("central-portal-response.txt").get().asFile
        val httpCodeFile = layout.buildDirectory.file("central-portal-httpcode.txt").get().asFile
        val stderrFile = layout.buildDirectory.file("central-portal-curl-stderr.txt").get().asFile
        val uploadResult = exec {
            isIgnoreExitValue = true
            environment("AETHER_CENTRAL_AUTHORIZATION", authorization)
            commandLine(
                "bash", "-c", """
                CURL_EXIT=0
                HTTP_CODE=${'$'}(curl --request POST \
                    --url "https://central.sonatype.com/api/v1/publisher/upload?name=$deploymentName&publishingType=AUTOMATIC" \
                    --header "Authorization: ${'$'}AETHER_CENTRAL_AUTHORIZATION" \
                    --form "bundle=@${zipFile.absolutePath}" \
                    --connect-timeout 15 \
                    --max-time 600 \
                    --write-out "%{http_code}" \
                    --output "${responseFile.absolutePath}" \
                    --silent --show-error \
                    2>"${stderrFile.absolutePath}") || CURL_EXIT=${'$'}?
                echo "${'$'}HTTP_CODE" > "${httpCodeFile.absolutePath}"
                exit "${'$'}CURL_EXIT"
                """.trimIndent()
            )
        }

        val httpCode = httpCodeFile.takeIf(File::isFile)?.readText()?.trim() ?: "unknown"
        val response = responseFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        val numericHttpCode = httpCode.toIntOrNull()
        val definitelyRejected = uploadResult.exitValue == 0 &&
            numericHttpCode in 400..499 && numericHttpCode !in setOf(408, 409, 425, 429)
        if (uploadResult.exitValue != 0 || httpCode != "201" || !centralDeploymentIdPattern.matches(response)) {
            if (definitelyRejected) {
                val stderr = stderrFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
                throw GradleException(
                    "Central definitively rejected the upload (HTTP $httpCode): " +
                        "${response.take(1000)} ${stderr.take(1000)}"
                )
            }
            throw GradleException(
                "Central upload outcome is indeterminate (curl ${uploadResult.exitValue}, HTTP $httpCode). " +
                    "Do not upload again. Find deployment '$deploymentName' in Central Portal and resume by ID."
            )
        }

        val deploymentIdFile = layout.buildDirectory.file("central-portal-deployment-id.txt").get().asFile
        deploymentIdFile.writeText("$response\n")
        layout.buildDirectory.file("central-portal-deployment-name.txt").get().asFile
            .writeText("$deploymentName\n")
        System.getenv("GITHUB_STEP_SUMMARY")?.takeIf(String::isNotBlank)?.let { summaryPath ->
            File(summaryPath).appendText("Central deployment: `$response` (`$deploymentName`)\n")
        }
        println("CENTRAL_DEPLOYMENT_ID=$response")
        println("Central accepted deployment $response as $deploymentName")
    }
}

val waitForCentralPortalPublication by tasks.registering {
    group = "publishing"
    description = "Polls one known Central deployment until it reaches PUBLISHED or FAILED."
    dependsOn(writeExpectedCentralPurls, writeExpectedCentralReportedPurls)

    doLast {
        val username = localProperties.getProperty("mavenCentralUsername")
            ?: System.getenv("mavenCentralUsername")
            ?: throw GradleException("mavenCentralUsername not found")
        val password = localProperties.getProperty("mavenCentralPassword")
            ?: System.getenv("mavenCentralPassword")
            ?: throw GradleException("mavenCentralPassword not found")
        val authorization = "Bearer " + Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray())
        val deploymentIdFile = layout.buildDirectory.file("central-portal-deployment-id.txt").get().asFile
        val deploymentId = System.getenv("AETHER_CENTRAL_DEPLOYMENT_ID")
            ?.trim()?.takeIf(String::isNotEmpty)
            ?: deploymentIdFile.takeIf(File::isFile)?.readText()?.trim()
            ?: throw GradleException("AETHER_CENTRAL_DEPLOYMENT_ID or a recorded deployment ID is required")
        if (!centralDeploymentIdPattern.matches(deploymentId)) {
            throw GradleException("Central deployment ID is not a UUID")
        }
        val deploymentNameFile = layout.buildDirectory.file("central-portal-deployment-name.txt").get().asFile
        val expectedDeploymentName = System.getenv("AETHER_CENTRAL_DEPLOYMENT_NAME")
            ?.trim()?.takeIf(String::isNotEmpty)
            ?: deploymentNameFile.takeIf(File::isFile)?.readText()?.trim()
            ?: throw GradleException("AETHER_CENTRAL_DEPLOYMENT_NAME or a recorded deployment name is required")
        val expectedPurls = centralExpectedReportedPurls(project.version.toString())
        val timeoutSeconds = System.getenv("AETHER_CENTRAL_STATUS_TIMEOUT_SECONDS")
            ?.toLongOrNull()?.coerceIn(60L, 7200L) ?: 1800L
        val pollSeconds = System.getenv("AETHER_CENTRAL_STATUS_POLL_SECONDS")
            ?.toLongOrNull()?.coerceIn(5L, 60L) ?: 10L
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        val statusFile = layout.buildDirectory.file("central-portal-status.json").get().asFile
        val httpCodeFile = layout.buildDirectory.file("central-portal-status-httpcode.txt").get().asFile
        val stderrFile = layout.buildDirectory.file("central-portal-status-stderr.txt").get().asFile

        while (true) {
            if (System.nanoTime() >= deadline) {
                throw GradleException(
                    "Timed out waiting for Central deployment $deploymentId; resume this exact deployment ID"
                )
            }
            val result = exec {
                isIgnoreExitValue = true
                environment("AETHER_CENTRAL_AUTHORIZATION", authorization)
                commandLine(
                    "bash", "-c", """
                    CURL_EXIT=0
                    HTTP_CODE=${'$'}(curl --request POST \
                        --url "https://central.sonatype.com/api/v1/publisher/status?id=$deploymentId" \
                        --header "Authorization: ${'$'}AETHER_CENTRAL_AUTHORIZATION" \
                        --connect-timeout 10 \
                        --max-time 30 \
                        --write-out "%{http_code}" \
                        --output "${statusFile.absolutePath}" \
                        --silent --show-error \
                        2>"${stderrFile.absolutePath}") || CURL_EXIT=${'$'}?
                    echo "${'$'}HTTP_CODE" > "${httpCodeFile.absolutePath}"
                    exit "${'$'}CURL_EXIT"
                    """.trimIndent()
                )
            }
            val httpCode = httpCodeFile.takeIf(File::isFile)?.readText()?.trim() ?: "unknown"
            if (result.exitValue != 0 || httpCode.toIntOrNull() !in 200..299) {
                val retryable = result.exitValue != 0 || httpCode == "429" || httpCode.toIntOrNull() in 500..599
                if (!retryable) {
                    val stderr = stderrFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
                    throw GradleException(
                        "Central status request failed for $deploymentId (curl ${result.exitValue}, " +
                            "HTTP $httpCode): ${stderr.take(1000)}"
                    )
                }
                println("Central status is transiently unavailable; retrying in $pollSeconds seconds")
            } else {
                val body = statusFile.readText()
                val status = runCatching { JsonSlurper().parseText(body) as? Map<*, *> }.getOrNull()
                    ?: throw GradleException("Central returned malformed status JSON for $deploymentId")
                val returnedId = status["deploymentId"] as? String
                val returnedName = status["deploymentName"] as? String
                val state = status["deploymentState"] as? String
                    ?: throw GradleException("Central status has no deploymentState for $deploymentId")
                val rawPurls = status["purls"]
                val purlValues = when (rawPurls) {
                    null -> emptyList<Any?>()
                    is List<*> -> rawPurls
                    else -> throw GradleException("Central status purls must be an array")
                }
                if (purlValues.any { it !is String }) {
                    throw GradleException("Central status purls must contain only strings")
                }
                val purlList = purlValues.filterIsInstance<String>()
                val purls = purlList.toSet()
                if (returnedId != deploymentId) throw GradleException("Central status returned a different deployment ID")
                if (returnedName != expectedDeploymentName) {
                    throw GradleException("Central status returned unexpected deployment name '$returnedName'")
                }
                if (purlList.size != purls.size) throw GradleException("Central status contains duplicate PURLs")
                val unexpectedPurls = purls - expectedPurls
                if (unexpectedPurls.isNotEmpty()) {
                    throw GradleException(
                        "Central deployment contains unexpected components: ${unexpectedPurls.sorted()}"
                    )
                }

                println("Central deployment $deploymentId is $state")
                when (state) {
                    "PUBLISHED" -> {
                        if (purls != expectedPurls) {
                            throw GradleException("Published deployment did not expose the exact expected component set")
                        }
                        println("Maven Central deployment $deploymentId is PUBLISHED")
                        break
                    }
                    "FAILED" -> throw GradleException(
                        "Central deployment $deploymentId failed: ${status["errors"].toString().take(4000)}"
                    )
                    "PENDING", "VALIDATING", "VALIDATED", "PUBLISHING" -> Unit
                    else -> throw GradleException("Central deployment $deploymentId returned unknown state '$state'")
                }
            }
            Thread.sleep(pollSeconds * 1000L)
        }
    }
}

tasks.register("publishToCentralPortalManually") {
    group = "publishing"
    description = "Prepares, uploads once, and waits for one Central Portal deployment."
    dependsOn(uploadCentralPortalBundle, waitForCentralPortalPublication)
    waitForCentralPortalPublication.configure { mustRunAfter(uploadCentralPortalBundle) }
}
