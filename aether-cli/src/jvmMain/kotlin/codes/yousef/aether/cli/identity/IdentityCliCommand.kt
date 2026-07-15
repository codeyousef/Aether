package codes.yousef.aether.cli.identity

internal sealed interface IdentityCliCommand {
    val server: String

    data class Login(
        override val server: String,
        val scopes: Set<String>,
        val noStore: Boolean,
    ) : IdentityCliCommand

    data class WhoAmI(override val server: String) : IdentityCliCommand

    data class OrganizationList(override val server: String) : IdentityCliCommand

    data class OrganizationUse(
        override val server: String,
        val organizationId: String,
    ) : IdentityCliCommand

    data class Logout(override val server: String) : IdentityCliCommand

    data object Help : IdentityCliCommand {
        override val server: String = IdentityCliProtocol.DEFAULT_SERVER
    }
}

internal object IdentityCliCommandParser {
    fun parse(args: List<String>, defaultServer: String = IdentityCliProtocol.DEFAULT_SERVER): IdentityCliCommand {
        if (args.isEmpty() || args == listOf("--help") || args == listOf("-h") || args == listOf("help")) {
            return IdentityCliCommand.Help
        }
        if (args.first() != "auth") {
            throw CliUsageException("Identity commands must start with 'auth'.")
        }
        if (args.size == 1 || args.drop(1) == listOf("--help") || args.drop(1) == listOf("-h")) {
            return IdentityCliCommand.Help
        }
        return when (args[1]) {
            "login" -> parseLogin(args.drop(2), defaultServer)
            "whoami" -> IdentityCliCommand.WhoAmI(parseServerOnly(args.drop(2), defaultServer))
            "org" -> parseOrganization(args.drop(2), defaultServer)
            "logout" -> IdentityCliCommand.Logout(parseServerOnly(args.drop(2), defaultServer))
            else -> throw CliUsageException("Unknown auth command.")
        }
    }

    private fun parseLogin(args: List<String>, defaultServer: String): IdentityCliCommand.Login {
        var server = defaultServer
        var noStore = false
        val scopes = linkedSetOf<String>()
        var index = 0
        while (index < args.size) {
            when (val argument = args[index]) {
                "--server" -> server = requiredValue(args, ++index, argument)
                "--scope" -> {
                    val raw = requiredValue(args, ++index, argument)
                    raw.split(',', ' ').filterTo(scopes) { it.isNotBlank() }
                }
                "--no-store" -> noStore = true
                "--help", "-h" -> throw CliUsageException("Use 'aether-cli auth --help' for command help.")
                else -> throw CliUsageException("Unknown auth login option.")
            }
            index++
        }
        val requested = (scopes.ifEmpty {
            IdentityCliProtocol.DEFAULT_SCOPE.split(' ').toCollection(linkedSetOf())
        }).onEach(::validateScope)
        return IdentityCliCommand.Login(
            server = IdentityCliProtocol.normalizedServer(server),
            scopes = requested,
            noStore = noStore,
        )
    }

    private fun parseOrganization(args: List<String>, defaultServer: String): IdentityCliCommand {
        if (args.isEmpty()) throw CliUsageException("Expected 'auth org list' or 'auth org use <organization-id>'.")
        return when (args.first()) {
            "list" -> IdentityCliCommand.OrganizationList(parseServerOnly(args.drop(1), defaultServer))
            "use" -> {
                if (args.size < 2 || args[1].startsWith('-')) {
                    throw CliUsageException("'auth org use' requires an organization ID.")
                }
                val organizationId = IdentityCliProtocol.routeId(args[1])
                val server = parseServerOnly(args.drop(2), defaultServer)
                IdentityCliCommand.OrganizationUse(server, organizationId)
            }
            else -> throw CliUsageException("Unknown auth org command.")
        }
    }

    private fun parseServerOnly(args: List<String>, defaultServer: String): String {
        var server = defaultServer
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            if (argument != "--server") {
                throw CliUsageException("Unknown option.")
            }
            server = requiredValue(args, ++index, argument)
            index++
        }
        return IdentityCliProtocol.normalizedServer(server)
    }

    private fun requiredValue(args: List<String>, index: Int, option: String): String =
        args.getOrNull(index)?.takeUnless { it.startsWith('-') }
            ?: throw CliUsageException("$option requires a value.")

    private fun validateScope(scope: String) {
        if (scope.length !in 1..128 || scope.any { !it.isLetterOrDigit() && it !in ".:_-" }) {
            throw CliUsageException("A requested scope is invalid.")
        }
    }
}
