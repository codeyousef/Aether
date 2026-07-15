import java.security.MessageDigest
import java.util.*

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

// Custom task to bundle and publish artifacts to Maven Central Portal
tasks.register("publishToCentralPortalManually") {
    group = "publishing"
    description = "Publish to Maven Central using Central Portal API"
    
    dependsOn(subprojects.map { ":${it.name}:publishToMavenLocal" })

    doLast {
        val username = localProperties.getProperty("mavenCentralUsername")
            ?: System.getenv("mavenCentralUsername")
            ?: throw GradleException("mavenCentralUsername not found")

        val password = localProperties.getProperty("mavenCentralPassword")
            ?: System.getenv("mavenCentralPassword")
            ?: throw GradleException("mavenCentralPassword not found")

        println("🚀 Publishing to Maven Central via Central Portal API...")
        println("📦 Username: $username")

        // Create bundle directory with proper Maven structure
        val bundleDir = file("${layout.buildDirectory.get()}/central-portal-bundle")
        bundleDir.deleteRecursively()
        bundleDir.mkdirs()

        // List of modules to publish
        val modules = listOf(
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
        // List of variants for each module
        val variants = listOf("", "-jvm", "-wasm-js", "-wasm-wasi") 

        val allFilesToProcess = mutableListOf<File>()
        var foundModuleCount = 0
        var missingModuleCount = 0
        val missingRequiredCoordinates = mutableListOf<String>()

        val mavenLocalRepository = System.getProperty("maven.repo.local")
            ?.takeIf { it.isNotBlank() }
            ?.let { file(it) }
            ?: file("${System.getProperty("user.home")}/.m2/repository")
        val mavenLocalRoot = file("$mavenLocalRepository/codes/yousef/aether")
        println("📂 Looking for artifacts in: $mavenLocalRoot")

        if (!mavenLocalRoot.exists()) {
            throw GradleException("Maven local repository path does not exist: $mavenLocalRoot. Did publishToMavenLocal run?")
        }

        modules.forEach { module ->
            variants.forEach { variant ->
                val artifactId = "$module$variant"
                val version = project.version.toString()
                
                // Construct path in local repo
                val localMavenDir = file("${mavenLocalRoot.absolutePath}/$artifactId/$version")

                if (localMavenDir.exists()) {
                    foundModuleCount++
                    val mavenPath = "codes/yousef/aether/$artifactId/$version"
                    val targetDir = file("$bundleDir/$mavenPath")
                    targetDir.mkdirs()

                    val files = localMavenDir.listFiles() ?: emptyArray()
                    val artifactFiles = files.filter { file ->
                        (file.name.endsWith(".jar") || file.name.endsWith(".pom") || file.name.endsWith(".klib") || file.name.endsWith(
                            ".module"
                        )) &&
                                !file.name.endsWith(".md5") && !file.name.endsWith(".sha1") && !file.name.endsWith(".asc")
                    }

                    if (artifactFiles.isNotEmpty()) {
                        println("📦 Processing $artifactId (${artifactFiles.size} files)...")
                        artifactFiles.forEach { file ->
                            file.copyTo(File(targetDir, file.name), overwrite = true)
                            allFilesToProcess.add(File(targetDir, file.name))
                        }
                    } else {
                        println("⚠️ Directory exists but no artifact files found for $artifactId")
                        if (variant.isEmpty()) {
                            missingRequiredCoordinates.add("codes.yousef.aether:$artifactId:${project.version}")
                        }
                    }
                } else {
                    missingModuleCount++
                    // Only warn for base modules without variant suffix, as variants may not exist for all modules
                    if (variant.isEmpty()) {
                        println("⚠️ No artifacts found for $artifactId at $localMavenDir")
                        missingRequiredCoordinates.add("codes.yousef.aether:$artifactId:${project.version}")
                    }
                }
            }
        }

        // java-gradle-plugin publishes a marker coordinate outside the project group. Include it
        // alongside the plugin implementation so the plugins DSL can resolve the released ID.
        val pluginMarkerArtifactId = "codes.yousef.aether.plugin.gradle.plugin"
        val pluginMarkerGroupPath = "codes/yousef/aether/plugin"
        val pluginMarkerCoordinate = "codes.yousef.aether.plugin:$pluginMarkerArtifactId:${project.version}"
        val pluginMarkerSource = file(
            "$mavenLocalRepository/$pluginMarkerGroupPath/$pluginMarkerArtifactId/${project.version}"
        )
        val pluginMarkerFiles = pluginMarkerSource.listFiles()
            ?.filter { file ->
                (file.name.endsWith(".jar") || file.name.endsWith(".pom") ||
                    file.name.endsWith(".klib") || file.name.endsWith(".module")) &&
                    !file.name.endsWith(".md5") && !file.name.endsWith(".sha1") &&
                    !file.name.endsWith(".asc")
            }
            .orEmpty()
        if (pluginMarkerFiles.isEmpty()) {
            missingRequiredCoordinates.add(pluginMarkerCoordinate)
        } else {
            foundModuleCount++
            val targetDir = file(
                "$bundleDir/$pluginMarkerGroupPath/$pluginMarkerArtifactId/${project.version}"
            )
            targetDir.mkdirs()
            println("📦 Processing Gradle plugin marker (${pluginMarkerFiles.size} files)...")
            pluginMarkerFiles.forEach { source ->
                val target = File(targetDir, source.name)
                source.copyTo(target, overwrite = true)
                allFilesToProcess.add(target)
            }
        }

        println("📊 Found $foundModuleCount module variants, $missingModuleCount not found")

        if (missingRequiredCoordinates.isNotEmpty()) {
            throw GradleException(
                "Required Maven publications are missing or empty: " +
                    missingRequiredCoordinates.distinct().sorted().joinToString()
            )
        }

        if (allFilesToProcess.isEmpty()) {
            throw GradleException("No Maven artifacts found. Make sure publishToMavenLocal ran successfully.")
        }

        println("📝 Generating checksums and signatures...")

        allFilesToProcess.forEach { file ->
            // Generate MD5 checksum
            val md5Hash = MessageDigest.getInstance("MD5")
                .digest(file.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            File(file.parent, "${file.name}.md5").writeText(md5Hash)

            // Generate SHA1 checksum
            val sha1Hash = MessageDigest.getInstance("SHA-1")
                .digest(file.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            File(file.parent, "${file.name}.sha1").writeText(sha1Hash)

            // Generate GPG signature
            val sigFile = File(file.parent, "${file.name}.asc")
            println("   Creating GPG signature for ${file.name}...")

            val privateKeyFile = rootProject.file("private-key.asc")
            if (!privateKeyFile.exists()) {
                throw GradleException("private-key.asc not found. Cannot sign artifacts.")
            }

            val signScript = rootProject.file("sign-artifact.sh")
            if (!signScript.exists()) {
                throw GradleException("sign-artifact.sh not found. Cannot sign artifacts.")
            }

            val signingPassword = localProperties.getProperty("signingPassword")
                 ?: System.getenv("signingPassword")
                 ?: throw GradleException("signingPassword not found")

            exec {
                commandLine(
                    "bash",
                    signScript.absolutePath,
                    signingPassword,
                    privateKeyFile.absolutePath,
                    sigFile.absolutePath,
                    file.absolutePath
                )
            }
        }

        // Verify we have files to publish
        val filesInBundle = bundleDir.walkTopDown().filter { it.isFile }.toList()
        println("📦 Total files in bundle: ${filesInBundle.size}")
        if (filesInBundle.isEmpty()) {
            throw GradleException("Bundle directory is empty. No artifacts to publish.")
        }

        // Show bundle structure for debugging
        println("📋 Bundle contents:")
        filesInBundle.take(20).forEach { println("   - ${it.relativeTo(bundleDir)}") }
        if (filesInBundle.size > 20) {
            println("   ... and ${filesInBundle.size - 20} more files")
        }

        println("📦 Zipping bundle...")
        val zipFile = file("${layout.buildDirectory.get()}/central-portal-bundle.zip")
        if (zipFile.exists()) zipFile.delete()

        exec {
            workingDir = bundleDir
            commandLine("zip", "-r", zipFile.absolutePath, ".")
        }

        val bundleSizeKB = zipFile.length() / 1024
        println("📋 Bundle size: $bundleSizeKB KB")

        if (bundleSizeKB == 0L) {
            throw GradleException("Bundle zip file is empty!")
        }

        println("🚀 Uploading to Central Portal...")

        // Base64 encode credentials for UserToken auth
        val userPass = "$username:$password"
        val userPassBase64 = java.util.Base64.getEncoder().encodeToString(userPass.toByteArray())

        // Temp files for curl output
        val responseFile = file("${layout.buildDirectory.get()}/central-portal-response.txt")
        val httpCodeFile = file("${layout.buildDirectory.get()}/central-portal-httpcode.txt")
        val curlStderrFile = file("${layout.buildDirectory.get()}/central-portal-curl-stderr.txt")

        // Upload using curl with proper error handling
        exec {
            commandLine(
                "bash", "-c", """
                HTTP_CODE=${'$'}(curl --request POST \
                    --url "https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC" \
                    --header "Authorization: UserToken $userPassBase64" \
                    --form "bundle=@${zipFile.absolutePath}" \
                    --write-out "%{http_code}" \
                    --output "${responseFile.absolutePath}" \
                    --fail-with-body \
                    --silent \
                    2>"${curlStderrFile.absolutePath}")
                echo "${'$'}HTTP_CODE" > "${httpCodeFile.absolutePath}"
                echo "HTTP Code: ${'$'}HTTP_CODE"
                echo "Response:"
                cat "${responseFile.absolutePath}" || echo "(empty)"
                echo ""
                if [ "${'$'}HTTP_CODE" -ge 200 ] && [ "${'$'}HTTP_CODE" -lt 300 ]; then
                    exit 0
                else
                    echo "Curl stderr:"
                    cat "${curlStderrFile.absolutePath}" || echo "(empty)"
                    exit 1
                fi
                """.trimIndent()
            )
        }

        // Read and display results
        val httpCode = if (httpCodeFile.exists()) httpCodeFile.readText().trim() else "unknown"
        val response = if (responseFile.exists()) responseFile.readText().trim() else ""

        println("📋 HTTP Status Code: $httpCode")
        println("📋 Response Body: $response")

        if (httpCode.toIntOrNull()?.let { it < 200 || it >= 300 } != false) {
            val stderr = if (curlStderrFile.exists()) curlStderrFile.readText().trim() else ""
            throw GradleException("Failed to upload to Maven Central. HTTP Code: $httpCode, Response: $response, Stderr: $stderr")
        }

        println("✅ Upload complete!")
    }
}
