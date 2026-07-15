package codes.yousef.aether.cli.identity

import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

object JvmIdentityCliFactory {
    fun create(
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name", "unknown"),
    ): IdentityCli {
        // Parsing happens inside IdentityCli so a malformed environment value becomes a safe
        // usage error instead of escaping the command boundary as an exception.
        val defaultServer = environment["AETHER_IDENTITY_URL"] ?: IdentityCliProtocol.DEFAULT_SERVER
        return IdentityCli(
            http = JdkCliHttpClient(),
            clock = CliClock(Instant::now),
            delay = CliDelay(::delay),
            io = SystemCliIo,
            credentialStore = OperatingSystemCredentialStore.create(osName, environment),
            cancellation = CliCancellation { Thread.currentThread().isInterrupted },
            defaultServer = defaultServer,
        )
    }
}

class JdkCliHttpClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(JavaDuration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val requestTimeout: JavaDuration = JavaDuration.ofSeconds(30),
) : CliHttpClient {
    override suspend fun execute(request: CliHttpRequest): CliHttpResponse {
        val builder = HttpRequest.newBuilder(URI(request.url)).timeout(requestTimeout)
        request.headers.forEach(builder::header)
        val publisher = request.body
            ?.let { HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8) }
            ?: HttpRequest.BodyPublishers.noBody()
        builder.method(request.method, publisher)
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (_: IOException) {
            throw CliTransportException()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Identity HTTP request interrupted")
        }
        val bytes = response.body().use { stream -> stream.readNBytes(MAX_RESPONSE_BYTES + 1) }
        if (bytes.size > MAX_RESPONSE_BYTES) {
            bytes.fill(0)
            throw CliOperationException("The identity server response was too large.")
        }
        val body = bytes.toString(StandardCharsets.UTF_8)
        bytes.fill(0)
        return CliHttpResponse(
            status = response.statusCode(),
            headers = response.headers().map(),
            body = body,
        )
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}

object SystemCliIo : CliIo {
    override fun output(message: String) = println(message)
    override fun error(message: String) = System.err.println(message)
}

internal data class SecureCommandResult(
    val exitCode: Int,
    val output: String,
) {
    override fun toString(): String =
        "SecureCommandResult(exitCode=$exitCode, output=${if (output.isEmpty()) "" else "<redacted>"})"
}

internal fun interface SecureCommandRunner {
    fun run(command: List<String>, stdin: String?): SecureCommandResult
}

internal class JvmSecureCommandRunner : SecureCommandRunner {
    override fun run(command: List<String>, stdin: String?): SecureCommandResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val inputBytes = stdin?.toByteArray(StandardCharsets.UTF_8)
        try {
            process.outputStream.use { output ->
                if (inputBytes != null) output.write(inputBytes)
            }
        } finally {
            inputBytes?.fill(0)
        }
        val captured = ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (captured.size() <= MAX_COMMAND_OUTPUT_BYTES) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    captured.write(buffer, 0, count)
                }
                buffer.fill(0)
            }
        }.apply {
            isDaemon = true
            start()
        }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1_000)
            throw CliOperationException("The operating-system credential store did not respond.")
        }
        reader.join(1_000)
        val bytes = captured.toByteArray()
        if (bytes.size > MAX_COMMAND_OUTPUT_BYTES) {
            bytes.fill(0)
            throw CliOperationException("The operating-system credential store returned too much data.")
        }
        val output = bytes.toString(StandardCharsets.UTF_8)
        bytes.fill(0)
        return SecureCommandResult(process.exitValue(), output)
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 15L
        const val MAX_COMMAND_OUTPUT_BYTES = 1_048_576
    }
}

object OperatingSystemCredentialStore {
    fun create(
        osName: String = System.getProperty("os.name", "unknown"),
        environment: Map<String, String> = System.getenv(),
    ): CredentialStore {
        val runner = JvmSecureCommandRunner()
        return when {
            osName.lowercase(Locale.ROOT).contains("mac") -> MacOsKeychainCredentialStore(runner)
            osName.lowercase(Locale.ROOT).contains("win") -> WindowsDpapiCredentialStore(
                runner = runner,
                localAppData = environment["LOCALAPPDATA"],
            )
            osName.lowercase(Locale.ROOT).contains("linux") -> LinuxSecretServiceCredentialStore(
                runner = runner,
                hasSessionBus = !environment["DBUS_SESSION_BUS_ADDRESS"].isNullOrBlank(),
            )
            else -> UnavailableCredentialStore
        }
    }
}

internal object UnavailableCredentialStore : CredentialStore {
    override suspend fun isAvailable(): Boolean = false
    override suspend fun read(account: String): StoredCredentials? = unavailable()
    override suspend fun write(account: String, credentials: StoredCredentials): Unit = unavailable()
    override suspend fun delete(account: String): Unit = unavailable()

    private fun unavailable(): Nothing =
        throw CliOperationException("Secure credential storage is unavailable on this platform.")
}

internal class LinuxSecretServiceCredentialStore(
    private val runner: SecureCommandRunner,
    private val hasSessionBus: Boolean,
) : CredentialStore {
    override suspend fun isAvailable(): Boolean {
        if (!hasSessionBus) return false
        return runCatching {
            runner.run(listOf("secret-tool", "search", "--all", "service", SERVICE), null).exitCode == 0
        }.getOrDefault(false)
    }

    override suspend fun read(account: String): StoredCredentials? {
        val result = runner.run(secretTool("lookup", account), null)
        if (result.exitCode != 0 || result.output.isBlank()) return null
        return IdentityCliProtocol.decodeCredentials(result.output.trimEnd('\r', '\n'))
    }

    override suspend fun write(account: String, credentials: StoredCredentials) {
        val encoded = IdentityCliProtocol.encodeCredentials(credentials)
        val result = runner.run(
            listOf(
                "secret-tool", "store", "--label=Aether Identity CLI",
                "service", SERVICE, "account", account,
            ),
            "$encoded\n",
        )
        if (result.exitCode != 0) throw CliOperationException("Linux Secret Service could not store credentials.")
    }

    override suspend fun delete(account: String) {
        runner.run(secretTool("clear", account), null)
    }

    private fun secretTool(operation: String, account: String): List<String> =
        listOf("secret-tool", operation, "service", SERVICE, "account", account)
}

internal class MacOsKeychainCredentialStore(
    private val runner: SecureCommandRunner,
) : CredentialStore {
    override suspend fun isAvailable(): Boolean =
        runCatching { runner.run(listOf("security", "list-keychains", "-d", "user"), null).exitCode == 0 }
            .getOrDefault(false)

    override suspend fun read(account: String): StoredCredentials? {
        val result = runner.run(
            listOf("security", "find-generic-password", "-s", SERVICE, "-a", account, "-w"),
            null,
        )
        if (result.exitCode != 0 || result.output.isBlank()) return null
        return IdentityCliProtocol.decodeCredentials(result.output.trimEnd('\r', '\n'))
    }

    override suspend fun write(account: String, credentials: StoredCredentials) {
        val encoded = IdentityCliProtocol.encodeCredentials(credentials)
        val passwordHex = encoded.toByteArray(StandardCharsets.UTF_8).let { bytes ->
            try {
                buildString(bytes.size * 2) {
                    bytes.forEach { byte ->
                        val value = byte.toInt() and 0xff
                        append(HEX_DIGITS[value ushr 4])
                        append(HEX_DIGITS[value and 0x0f])
                    }
                }
            } finally {
                bytes.fill(0)
            }
        }
        // Interactive mode reads the command from stdin, so the credential payload never appears
        // in the process argument list. `-X` is security(1)'s exact-byte hexadecimal input form.
        val interactiveCommand = buildString {
            append("add-generic-password -U -s ")
            append(interactiveArgument(SERVICE))
            append(" -a ")
            append(interactiveArgument(account))
            append(" -X ")
            append(passwordHex)
            append('\n')
        }
        if (interactiveCommand.length > MAX_INTERACTIVE_COMMAND_CHARS) {
            throw CliOperationException("The credentials are too large for macOS Keychain storage.")
        }
        val result = runner.run(
            listOf("security", "-i"),
            interactiveCommand,
        )
        if (result.exitCode != 0) throw CliOperationException("macOS Keychain could not store credentials.")
    }

    override suspend fun delete(account: String) {
        runner.run(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", account), null)
    }

    private fun interactiveArgument(value: String): String {
        if ('\u0000' in value || '\r' in value || '\n' in value) {
            throw CliOperationException("The macOS Keychain account is invalid.")
        }
        return buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                if (character == '"' || character == '\\') append('\\')
                append(character)
            }
            append('"')
        }
    }

    private companion object {
        // security(1) currently bounds a line submitted through `-i` to 4096 bytes including
        // its terminator. Keep one byte for the newline and one for the native NUL terminator.
        const val MAX_INTERACTIVE_COMMAND_CHARS = 4_094
        const val HEX_DIGITS = "0123456789abcdef"
    }
}

internal class WindowsDpapiCredentialStore(
    private val runner: SecureCommandRunner,
    localAppData: String?,
) : CredentialStore {
    private val directory = localAppData?.takeIf(String::isNotBlank)?.let { Path.of(it, "Aether", "identity-cli") }

    override suspend fun isAvailable(): Boolean {
        if (directory == null) return false
        return runCatching {
            runner.run(
                powershell(
                    "\$data=[byte[]](1); " +
                        "\$protected=[Security.Cryptography.ProtectedData]::Protect(" +
                        "\$data,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); " +
                        "if(\$protected.Length -gt 0){exit 0}else{exit 1}",
                ),
                null,
            ).exitCode == 0
        }.getOrDefault(false)
    }

    override suspend fun read(account: String): StoredCredentials? {
        val path = credentialPath(account) ?: return null
        val escaped = powershellLiteral(path.toString())
        val script =
            "if(-not [IO.File]::Exists('$escaped')){exit 3}; " +
                "\$cipher=[IO.File]::ReadAllBytes('$escaped'); " +
                "\$plain=[Security.Cryptography.ProtectedData]::Unprotect(" +
                "\$cipher,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); " +
                "[Console]::Out.Write([Text.Encoding]::UTF8.GetString(\$plain)); " +
                "[Array]::Clear(\$plain,0,\$plain.Length)"
        val result = runner.run(powershell(script), null)
        if (result.exitCode == 3) return null
        if (result.exitCode != 0 || result.output.isBlank()) {
            throw CliOperationException("Windows DPAPI could not read credentials.")
        }
        return IdentityCliProtocol.decodeCredentials(result.output)
    }

    override suspend fun write(account: String, credentials: StoredCredentials) {
        val path = credentialPath(account)
            ?: throw CliOperationException("Windows DPAPI storage is unavailable.")
        val escapedPath = powershellLiteral(path.toString())
        val escapedDirectory = powershellLiteral(path.parent.toString())
        val script =
            "[IO.Directory]::CreateDirectory('$escapedDirectory')|Out-Null; " +
                "\$text=[Console]::In.ReadToEnd(); \$plain=[Text.Encoding]::UTF8.GetBytes(\$text); " +
                "\$cipher=[Security.Cryptography.ProtectedData]::Protect(" +
                "\$plain,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); " +
                "\$temp='$escapedPath.'+[Guid]::NewGuid().ToString('N')+'.tmp'; " +
                "try{[IO.File]::WriteAllBytes(\$temp,\$cipher); " +
                "if([IO.File]::Exists('$escapedPath')){[IO.File]::Replace(\$temp,'$escapedPath',\$null)}" +
                "else{[IO.File]::Move(\$temp,'$escapedPath')}}" +
                "finally{if([IO.File]::Exists(\$temp)){[IO.File]::Delete(\$temp)}; " +
                "[Array]::Clear(\$plain,0,\$plain.Length); [Array]::Clear(\$cipher,0,\$cipher.Length)}"
        val result = runner.run(powershell(script), IdentityCliProtocol.encodeCredentials(credentials))
        if (result.exitCode != 0) throw CliOperationException("Windows DPAPI could not store credentials.")
    }

    override suspend fun delete(account: String) {
        val path = credentialPath(account) ?: return
        val escaped = powershellLiteral(path.toString())
        runner.run(powershell("if([IO.File]::Exists('$escaped')){[IO.File]::Delete('$escaped')}"), null)
    }

    private fun credentialPath(account: String): Path? = directory?.resolve("${sha256Hex(account)}.dpapi")

    private fun powershell(script: String): List<String> =
        listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script)

    private fun powershellLiteral(value: String): String = value.replace("'", "''")
}

private const val SERVICE = "codes.yousef.aether.cli.identity"

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
