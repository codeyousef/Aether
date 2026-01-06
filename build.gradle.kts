import java.util.Properties
import java.security.MessageDigest
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
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
version = versionProps.getProperty("VERSION") ?: "0.1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        google()
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

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
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

        // List of modules to publish
        val modules = listOf("aether-core", "aether-signals", "aether-tasks", "aether-channels", "aether-db", "aether-web", "aether-ui", "aether-net", "aether-ksp", "aether-auth", "aether-forms", "aether-admin")
        // List of variants for each module
        val variants = listOf("", "-jvm", "-wasm-js", "-wasm-wasi") 

        val allFilesToProcess = mutableListOf<File>()

        modules.forEach { module ->
            variants.forEach { variant ->
                val artifactId = "$module$variant"
                val version = project.version.toString()
                
                // Construct path in local repo
                val localMavenDir = file("${System.getProperty("user.home")}/.m2/repository/codes/yousef/aether/$artifactId/$version")
                
                if (localMavenDir.exists()) {
                    val mavenPath = "codes/yousef/aether/$artifactId/$version"
                    val targetDir = file("$bundleDir/$mavenPath")
                    targetDir.mkdirs()

                    println("📦 Processing $artifactId artifacts...")

                    localMavenDir.listFiles()?.forEach { file ->
                        if ((file.name.endsWith(".jar") || file.name.endsWith(".pom") || file.name.endsWith(".klib") || file.name.endsWith(".module")) &&
                            !file.name.endsWith(".md5") && !file.name.endsWith(".sha1") && !file.name.endsWith(".asc")) {
                            
                            file.copyTo(File(targetDir, file.name), overwrite = true)
                            allFilesToProcess.add(File(targetDir, file.name))
                        }
                    }
                } else {
                    println("⚠️ No artifacts found for $artifactId at $localMavenDir")
                }
            }
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

            providers.exec {
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

        println("📦 Zipping bundle...")
        val zipFile = file("${layout.buildDirectory.get()}/central-portal-bundle.zip")
        if (zipFile.exists()) zipFile.delete()

        providers.exec {
            workingDir = bundleDir
            commandLine("zip", "-r", zipFile.absolutePath, ".")
        }

        println("🚀 Uploading to Central Portal...")
        // Base64 encode credentials for Basic Auth
        val userPass = "$username:$password"
        val userPassBase64 = java.util.Base64.getEncoder().encodeToString(userPass.toByteArray())

        // Upload using curl with proper Basic Auth and response capture
        val responseFile = file("${layout.buildDirectory.get()}/central-portal-response.txt")
        val httpCodeFile = file("${layout.buildDirectory.get()}/central-portal-httpcode.txt")

        val execResult = providers.exec {
            commandLine(
                "bash", "-c",
                """
                HTTP_CODE=${'$'}(curl --request POST \
                    --url "https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC" \
                    --header "Authorization: UserToken $userPassBase64" \
                    --form "bundle=@${zipFile.absolutePath}" \
                    --silent \
                    --show-error \
                    --write-out "%{http_code}" \
                    --output "${responseFile.absolutePath}")
                echo "${'$'}HTTP_CODE" > "${httpCodeFile.absolutePath}"
                if [ "${'$'}HTTP_CODE" -ge 200 ] && [ "${'$'}HTTP_CODE" -lt 300 ]; then
                    exit 0
                else
                    exit 1
                fi
                """.trimIndent()
            )
            isIgnoreExitValue = true
        }

        val exitCode = execResult.result.get().exitValue
        val response = if (responseFile.exists()) responseFile.readText().trim() else ""
        val httpCode = if (httpCodeFile.exists()) httpCodeFile.readText().trim() else "unknown"

        println("📋 HTTP Status Code: $httpCode")
        println("📋 Response Body: $response")

        if (exitCode != 0) {
            println("❌ Upload failed with exit code $exitCode")
            throw GradleException("Failed to upload to Maven Central. HTTP Code: $httpCode, Response: $response")
        }
        
        if (response.isNotEmpty()) {
            println("📋 Deployment ID: $response")
            println("🔗 Check status at: https://central.sonatype.com/publishing/deployments")

            // Check deployment status
            println("⏳ Checking deployment status...")
            val statusFile = file("${layout.buildDirectory.get()}/central-portal-status.txt")
            providers.exec {
                commandLine(
                    "bash", "-c",
                    """
                    sleep 5
                    curl --request POST \
                        --url "https://central.sonatype.com/api/v1/publisher/status?id=$response" \
                        --header "Authorization: UserToken $userPassBase64" \
                        --silent \
                        --show-error \
                        --output "${statusFile.absolutePath}"
                    """.trimIndent()
                )
                isIgnoreExitValue = true
            }
            val status = if (statusFile.exists()) statusFile.readText().trim() else "Unable to fetch status"
            println("📋 Deployment Status: $status")
        } else {
            println("⚠️ No deployment ID returned. Check https://central.sonatype.com/publishing/deployments manually.")
        }
        println("✅ Upload complete! Artifacts submitted for publishing.")
        println("📝 Note: It may take several minutes for artifacts to appear on Maven Central after validation.")
    }
}
