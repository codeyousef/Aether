package codes.yousef.aether.cli.identity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class IdentityCli(
    private val http: CliHttpClient,
    private val clock: CliClock,
    private val delay: CliDelay,
    private val io: CliIo,
    private val credentialStore: CredentialStore,
    private val cancellation: CliCancellation = CliCancellation { false },
    private val defaultServer: String = IdentityCliProtocol.DEFAULT_SERVER,
) {
    suspend fun execute(args: List<String>): Int {
        val command = try {
            IdentityCliCommandParser.parse(args, defaultServer)
        } catch (failure: CliUsageException) {
            io.error(failure.message ?: "Invalid command.")
            return EXIT_USAGE
        }

        return try {
            when (command) {
                is IdentityCliCommand.Login -> login(command)
                is IdentityCliCommand.WhoAmI -> whoAmI(command)
                is IdentityCliCommand.OrganizationList -> listOrganizations(command)
                is IdentityCliCommand.OrganizationUse -> useOrganization(command)
                is IdentityCliCommand.Logout -> logout(command)
                IdentityCliCommand.Help -> printHelp()
            }
            EXIT_SUCCESS
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CliOperationException) {
            io.error(failure.message ?: "The identity operation failed.")
            EXIT_FAILURE
        } catch (_: Exception) {
            // Deliberately do not serialize exception text: network and platform exceptions may
            // contain request bodies, command arguments, or protected store output.
            io.error("The identity operation failed. No credentials were displayed.")
            EXIT_FAILURE
        }
    }

    private suspend fun login(command: IdentityCliCommand.Login) {
        if (!command.noStore && !credentialStore.isAvailable()) {
            throw CliOperationException(
                "Secure credential storage is unavailable. Configure the operating-system credential store or retry with --no-store.",
            )
        }

        val response = http.execute(
            formRequest(
                method = "POST",
                server = command.server,
                path = IdentityCliProtocol.DEVICE_AUTHORIZATION_PATH,
                fields = mapOf(
                    "client_id" to IdentityCliProtocol.CLIENT_ID,
                    "scope" to command.scopes.sorted().joinToString(" "),
                ),
            ),
        )
        if (response.status !in 200..299) {
            throw CliOperationException("Device authorization could not be started.")
        }
        val authorization = IdentityCliProtocol.parseDeviceAuthorization(response.body)
        io.output("Open ${authorization.verificationUri}")
        io.output("Enter code: ${authorization.userCode}")
        io.output("Waiting for approval…")

        val grant = awaitDeviceGrant(command.server, authorization)
        val now = clock.now()
        val credentials = StoredCredentials(
            server = command.server,
            accessToken = grant.accessToken,
            refreshToken = grant.refreshToken,
            accessTokenExpiresAt = now.plusSeconds(grant.accessExpiresInSeconds),
            refreshTokenExpiresAt = now.plusSeconds(grant.refreshExpiresInSeconds),
            grantedScopes = grant.scopes.ifEmpty { command.scopes },
        )
        if (command.noStore) {
            io.output("Authorization approved. Credentials were not stored (--no-store).")
        } else {
            credentialStore.write(IdentityCliProtocol.account(command.server), credentials)
            io.output("Authorization approved and credentials stored securely.")
        }
    }

    private suspend fun awaitDeviceGrant(server: String, authorization: DeviceAuthorization): TokenGrant {
        val deadline = clock.now().plusSeconds(authorization.expiresInSeconds)
        var interval = authorization.intervalSeconds
        try {
            while (true) {
                if (cancellation.isCancellationRequested()) {
                    cancelAuthorization(server, authorization.deviceCode)
                    throw CliOperationException("Login cancelled.")
                }
                val remainingMillis = deadline.toEpochMilli() - clock.now().toEpochMilli()
                if (remainingMillis <= 0) throw CliOperationException("The device authorization expired.")
                val waitMillis = (interval * 1_000).coerceAtMost(remainingMillis)
                delay.wait(waitMillis.milliseconds)

                if (cancellation.isCancellationRequested()) {
                    cancelAuthorization(server, authorization.deviceCode)
                    throw CliOperationException("Login cancelled.")
                }
                if (!clock.now().isBefore(deadline)) {
                    throw CliOperationException("The device authorization expired.")
                }

                val response = try {
                    http.execute(
                        formRequest(
                            method = "POST",
                            server = server,
                            path = IdentityCliProtocol.TOKEN_PATH,
                            fields = mapOf(
                                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                                "device_code" to authorization.deviceCode,
                                "client_id" to IdentityCliProtocol.CLIENT_ID,
                            ),
                        ),
                    )
                } catch (_: CliTransportException) {
                    // RFC 8628 requires clients to reduce their polling frequency after a
                    // connection timeout. Double the bounded interval, then retry until the
                    // original authorization deadline; never restart or extend the grant.
                    interval = (interval * 2).coerceAtMost(300)
                    continue
                }
                if (response.status in 200..299) return IdentityCliProtocol.parseToken(response.body)
                when (IdentityCliProtocol.parseOAuthError(response.body)) {
                    OAuthError.AUTHORIZATION_PENDING -> Unit
                    OAuthError.SLOW_DOWN -> interval = (interval + 5).coerceAtMost(300)
                    OAuthError.ACCESS_DENIED -> throw CliOperationException("Device authorization was denied.")
                    OAuthError.EXPIRED_TOKEN -> throw CliOperationException("The device authorization expired.")
                    else -> throw CliOperationException("The identity server rejected the device authorization.")
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancelAuthorization(server, authorization.deviceCode) }
            throw cancelled
        }
    }

    private suspend fun whoAmI(command: IdentityCliCommand.WhoAmI) {
        val (_, response) = authenticatedRequest(command.server, "GET", IdentityCliProtocol.WHO_AM_I_PATH)
        if (response.status !in 200..299) throw authorizationFailure(response.status)
        val identity = IdentityCliProtocol.parseWhoAmI(response.body)
        io.output(identity.displayName?.let { "$it (${identity.userId})" } ?: identity.userId)
        identity.assurance?.let { io.output("Assurance: $it") }
    }

    private suspend fun listOrganizations(command: IdentityCliCommand.OrganizationList) {
        val (credentials, response) = authenticatedRequest(command.server, "GET", IdentityCliProtocol.ORGANIZATIONS_PATH)
        if (response.status !in 200..299) throw authorizationFailure(response.status)
        val organizations = IdentityCliProtocol.parseOrganizations(response.body)
        if (organizations.isEmpty()) {
            io.output("No organizations are available.")
            return
        }
        organizations.forEach { organization ->
            val selected = if (organization.id == credentials.selectedOrganizationId) "*" else " "
            io.output("$selected ${organization.id}  ${organization.name}  ${organization.role.lowercase()}")
        }
    }

    private suspend fun useOrganization(command: IdentityCliCommand.OrganizationUse) {
        val account = IdentityCliProtocol.account(command.server)
        val (credentials, response) = authenticatedRequest(
            command.server,
            "GET",
            "${IdentityCliProtocol.ORGANIZATIONS_PATH}/${command.organizationId}",
        )
        if (response.status == 403 || response.status == 404) throw CliOperationException("Organization not found.")
        if (response.status !in 200..299) throw authorizationFailure(response.status)
        val organization = IdentityCliProtocol.parseOrganizations("[${response.body}]").single()
        if (organization.id != command.organizationId) {
            throw CliOperationException("The identity server returned an invalid organization response.")
        }
        credentialStore.write(account, credentials.copy(selectedOrganizationId = command.organizationId))
        io.output("Using organization ${organization.name} (${organization.id}).")
    }

    private suspend fun logout(command: IdentityCliCommand.Logout) {
        val account = IdentityCliProtocol.account(command.server)
        val credentials = credentialStore.read(account)
        if (credentials == null) {
            io.output("No stored login was found.")
            return
        }
        var remotelyRevoked = false
        try {
            val response = http.execute(
                formRequest(
                    method = "POST",
                    server = command.server,
                    path = IdentityCliProtocol.REVOCATION_PATH,
                    fields = mapOf(
                        "token" to credentials.refreshToken,
                        "token_type_hint" to "refresh_token",
                        "client_id" to IdentityCliProtocol.CLIENT_ID,
                    ),
                ),
            )
            remotelyRevoked = response.status in 200..299
        } finally {
            // Local removal must never depend on network availability.
            credentialStore.delete(account)
        }
        if (!remotelyRevoked) {
            throw CliOperationException("Local credentials were removed, but server revocation could not be confirmed.")
        }
        io.output("Logged out and removed stored credentials.")
    }

    private suspend fun authenticatedRequest(
        server: String,
        method: String,
        path: String,
    ): Pair<StoredCredentials, CliHttpResponse> {
        val account = IdentityCliProtocol.account(server)
        var credentials = credentialStore.read(account)
            ?: throw CliOperationException("Not logged in. Run 'aether-cli auth login' first.")
        if (credentials.server != server) throw CliOperationException("Stored credentials do not match this identity server.")
        if (!clock.now().isBefore(credentials.refreshTokenExpiresAt)) {
            credentialStore.delete(account)
            throw CliOperationException("The stored login expired. Run 'aether-cli auth login' again.")
        }
        if (shouldRefresh(credentials.accessTokenExpiresAt)) {
            credentials = refresh(account, credentials)
        }
        var response = bearerRequest(credentials, method, server, path)
        if (response.status == 401) {
            credentials = refresh(account, credentials)
            response = bearerRequest(credentials, method, server, path)
        }
        return credentials to response
    }

    private suspend fun bearerRequest(
        credentials: StoredCredentials,
        method: String,
        server: String,
        path: String,
    ): CliHttpResponse = http.execute(
        CliHttpRequest(
            method = method,
            url = IdentityCliProtocol.endpoint(server, path),
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer ${credentials.accessToken}",
            ),
        ),
    )

    private suspend fun refresh(account: String, credentials: StoredCredentials): StoredCredentials {
        if (!clock.now().isBefore(credentials.refreshTokenExpiresAt)) {
            credentialStore.delete(account)
            throw CliOperationException("The stored login expired. Run 'aether-cli auth login' again.")
        }
        val response = http.execute(
            formRequest(
                method = "POST",
                server = credentials.server,
                path = IdentityCliProtocol.TOKEN_PATH,
                fields = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to credentials.refreshToken,
                    "client_id" to IdentityCliProtocol.CLIENT_ID,
                ),
            ),
        )
        if (response.status !in 200..299) {
            if (IdentityCliProtocol.parseOAuthError(response.body) == OAuthError.INVALID_GRANT) {
                credentialStore.delete(account)
                throw CliOperationException("The stored login is no longer valid. Run 'aether-cli auth login' again.")
            }
            throw CliOperationException("The identity server could not refresh the login.")
        }
        val grant = IdentityCliProtocol.parseToken(response.body)
        val now = clock.now()
        val rotated = credentials.copy(
            accessToken = grant.accessToken,
            refreshToken = grant.refreshToken,
            accessTokenExpiresAt = now.plusSeconds(grant.accessExpiresInSeconds),
            refreshTokenExpiresAt = now.plusSeconds(grant.refreshExpiresInSeconds),
            grantedScopes = grant.scopes.ifEmpty { credentials.grantedScopes },
        )
        credentialStore.write(account, rotated)
        return rotated
    }

    private suspend fun cancelAuthorization(server: String, deviceCode: String) {
        runCatching {
            http.execute(
                CliHttpRequest(
                    method = "POST",
                    url = IdentityCliProtocol.endpoint(server, IdentityCliProtocol.DEVICE_CANCELLATION_PATH),
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                    ),
                    body = IdentityCliProtocol.encodeDeviceCancellation(deviceCode),
                ),
            )
        }
    }

    private fun formRequest(
        method: String,
        server: String,
        path: String,
        fields: Map<String, String>,
    ): CliHttpRequest = CliHttpRequest(
        method = method,
        url = IdentityCliProtocol.endpoint(server, path),
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/x-www-form-urlencoded",
        ),
        body = IdentityCliProtocol.form(fields),
    )

    private fun shouldRefresh(expiresAt: Instant): Boolean =
        !clock.now().plusSeconds(30).isBefore(expiresAt)

    private fun authorizationFailure(status: Int): CliOperationException = when (status) {
        401 -> CliOperationException("The login is no longer valid. Run 'aether-cli auth login' again.")
        403, 404 -> CliOperationException("The requested identity resource was not found.")
        else -> CliOperationException("The identity request failed.")
    }

    private fun printHelp() {
        io.output(
            """
            Aether identity commands:
              aether-cli auth login [--server URL] [--scope SCOPE] [--no-store]
              aether-cli auth whoami [--server URL]
              aether-cli auth org list [--server URL]
              aether-cli auth org use <organization-id> [--server URL]
              aether-cli auth logout [--server URL]

            Credentials are stored only in macOS Keychain, Windows DPAPI, or Linux Secret Service.
            """.trimIndent(),
        )
    }

    companion object {
        const val EXIT_SUCCESS = 0
        const val EXIT_FAILURE = 1
        const val EXIT_USAGE = 2
    }
}
