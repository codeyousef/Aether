package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Service workflows shared by actual adapter tests. Delivery is deliberately recorded, not qualified live. */
class AccountSecurityConformanceSuite(private val store: IdentityStore, private val runtime: IdentityRuntime,
    private val passwordProvider: IdentityPasswordProvider, private val namespace: String) {
    suspend fun runAll() {
        val ids = IdentityIdFactory(runtime)
        val clock = DeterministicIdentityClock(runtime.clock.now())
        val serviceRuntime = IdentityRuntime(clock, runtime.secureRandom, runtime.crypto, runtime.http, runtime.secrets)
        val config = accountSecurityTestConfig()
        val user = IdentityFixtures.user(ids.newUserId()).copy(primaryEmail = EmailAddress("$namespace@example.invalid"),
            createdAt = clock.now(), updatedAt = clock.now(), activatedAt = clock.now())
        val setupAudit = audit(ids, user.id, AuditAction.SCIM_MUTATION_APPLIED, clock)
        requireSuccess(store.applyScimMutation(ApplyScimMutationCommand(ScimMutation(ids.newScimOperationId(),
            "account-security-$namespace", ScimMutationType.UPSERT_USER, ExternalSubject(namespace), user = user,
            occurredAt = clock.now()), setupAudit)))
        val issuer = IdentitySessionIssuer(serviceRuntime, config)
        suspend fun passkeyContext(): IdentityContext {
            val currentUser = requireNotNull(requireSuccess(store.findUser(user.id)))
            val issued = issuer.issue(currentUser, AuthenticationAssurance.PASSKEY, SessionAuthenticationMethod.PASSKEY, clock.now())
            requireSuccess(store.createSession(CreateSessionCommand(issued.session, audit(ids, user.id, AuditAction.SESSION_CREATED, clock))))
            return accountSecurityContext(issued.session, user.displayName)
        }
        var delivered: IdentityEmailDelivery? = null
        var deliverOk = true
        var throttle = false
        val service = IdentityAccountSecurityService(store, serviceRuntime, config,
            IdentityAccountAttemptLimiter { key, _ -> check(key.length == 43); !throttle }, passwordProvider,
            IdentityEmailDeliveryProvider { delivered = it; deliverOk })
        var context = passkeyContext()
        val password = "PRIVATE_PASSWORD_CANARY_2522"
        requireOperation(service.replacePassword(context, password))
        check(requireSuccess(store.findSession(context.session!!.id))!!.state == SessionState.REVOKED)
        check(requireSuccess(store.findUser(user.id))!!.sessionEpoch == 1L)
        val login = requireOperation(service.login(user.primaryEmail!!.value, password, "test-peer"))
        check(login.session.assurance == AuthenticationAssurance.SESSION)
        check(login.session.authenticationMethod == SessionAuthenticationMethod.PASSWORD)
        check(login.cookieValue().contains(login.session.id.value))
        check(login.cookieValue() != "attacker-cookie")
        check(service.login(user.primaryEmail!!.value, "wrong-password", "test-peer") is IdentityOperationResult.Failure)
        check(service.login("unknown@example.invalid", password, "test-peer") is IdentityOperationResult.Failure)
        check(service.replacePassword(accountSecurityContext(login.session, user.displayName), password) is IdentityOperationResult.Failure)
        check(service.replacePassword(context, password) is IdentityOperationResult.Failure) // revoked enrollment session
        check(service.login(user.primaryEmail!!.value, "x".repeat(1025), "test-peer") is IdentityOperationResult.Failure)
        throttle = true
        check(service.login(user.primaryEmail!!.value, password, "test-peer") == IdentityOperationResult.Failure(IdentityErrorCode.RATE_LIMITED))
        throttle = false

        context = passkeyContext()
        clock.advanceMilliseconds(1)
        requireOperation(service.requestEmail(context))
        check(!requireOperation(service.emailStatus(context)).verified)
        val code = requireNotNull(delivered).code()
        check(!requireSuccess(store.findAccountSecurity(user.id)).toString().contains(code))
        check(service.verifyEmail(context, Base64Url.encode(ByteArray(32))) is IdentityOperationResult.Failure)
        val race = coroutineScope { List(2) { async { service.verifyEmail(context, code) } }.awaitAll() }
        check(race.count { it is IdentityOperationResult.Success } == 1) { "Verification must consume exactly once" }
        check(requireOperation(service.emailStatus(context)).verified)
        check(service.verifyEmail(context, code) is IdentityOperationResult.Failure)
        requireOperation(service.revokeEmail(context))
        check(!requireOperation(service.emailStatus(context)).verified)
        requireOperation(service.requestEmail(context))
        val exhaustedCode = requireNotNull(delivered).code()
        repeat(5) { check(service.verifyEmail(context, Base64Url.encode(ByteArray(32))) is IdentityOperationResult.Failure) }
        check(service.verifyEmail(context, exhaustedCode) is IdentityOperationResult.Failure)
        requireOperation(service.requestEmail(context))
        val expiredCode = requireNotNull(delivered).code()
        clock.advanceMilliseconds(15 * 60 * 1000)
        check(service.verifyEmail(context, expiredCode) is IdentityOperationResult.Failure)
        context = passkeyContext()
        deliverOk = false
        check(service.requestEmail(context) is IdentityOperationResult.Failure)
        check(service.verifyEmail(context, requireNotNull(delivered).code()) is IdentityOperationResult.Failure)
        deliverOk = true
        requireOperation(service.requestEmail(context))
        val staleCode = requireNotNull(delivered).code()
        requireOperation(service.changeEmail(context, EmailAddress("changed-$namespace@example.invalid")))
        check(service.verifyEmail(context, staleCode) is IdentityOperationResult.Failure)
        requireOperation(service.requestEmail(context))
        val newCode = requireNotNull(delivered).code()
        requireOperation(service.verifyEmail(context, newCode))
        check(requireOperation(service.emailStatus(context)).verified)
        // Lifecycle changes and credential resets never authenticate suspended accounts.
        val currentUser = requireSuccess(store.findUser(user.id))!!
        suspend fun setUserState(current: User, state: UserState): User {
            clock.advanceMilliseconds(1)
            val next = current.copy(state = state, version = current.version + 1, updatedAt = clock.now())
            requireSuccess(store.applyScimMutation(ApplyScimMutationCommand(ScimMutation(ids.newScimOperationId(),
                "account-security-$namespace", ScimMutationType.UPSERT_USER, ExternalSubject(namespace), user = next,
                occurredAt = clock.now()), audit(ids, user.id, AuditAction.SCIM_MUTATION_APPLIED, clock))))
            return next
        }
        val suspended = setUserState(currentUser, UserState.SUSPENDED)
        check(service.login("changed-$namespace@example.invalid", password, "test-peer") is IdentityOperationResult.Failure)
        setUserState(suspended, UserState.ACTIVE)
        context = passkeyContext()
        val resetPassword = "REPLACEMENT_PASSWORD_CANARY_2522"
        val resetRace = coroutineScope { List(2) { async { service.replacePassword(context, resetPassword) } }.awaitAll() }
        check(resetRace.count { it is IdentityOperationResult.Success } == 1) { "Credential reset must commit exactly once" }
        check(service.login("changed-$namespace@example.invalid", password, "test-peer") is IdentityOperationResult.Failure)
        requireOperation(service.login("changed-$namespace@example.invalid", resetPassword, "test-peer"))
        context = passkeyContext()
        val oldVersion = requireSuccess(store.findAccountSecurity(user.id))!!.version
        requireOperation(service.disablePassword(context))
        check(requireSuccess(store.findAccountSecurity(user.id))!!.version == oldVersion + 1)
        check(requireSuccess(store.findSession(login.session.id))!!.state == SessionState.REVOKED)
        check(service.login("changed-$namespace@example.invalid", password, "test-peer") is IdentityOperationResult.Failure)
        check(requireSuccess(store.findAccountSecurity(user.id))!!.password == null)
    }
    private fun audit(ids: IdentityIdFactory, user: UserId, action: AuditAction, clock: IdentityClock) = AuditEvent(
        ids.newAuditEventId(), AuditActor(type = AuditActorType.USER, userId = user), action = action,
        target = AuditTarget(AuditTargetType.USER, user.value), outcome = AuditOutcome.SUCCEEDED, occurredAt = clock.now())
}

fun accountSecurityTestConfig(): IdentityConfig {
    fun key(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
    return IdentityConfig(environment = IdentityEnvironment.TEST, publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig("localhost", "Test", setOf("http://localhost:8080")),
        keys = IdentityKeyConfig(sessionPepper = key("session"), recoveryPepper = key("recovery"),
            deviceTokenPepper = key("device"), serviceCredentialPepper = key("service"),
            auditPseudonymizationKey = key("audit"), encryptionKey = key("encryption"), signingKey = key("signing")))
}
fun accountSecurityContext(session: IdentitySession, displayName: String = "Test"): IdentityContext = IdentityContext(
    IdentityPrincipal(IdentityPrincipalKind.USER, session.userId, displayName = displayName, assurance = session.assurance,
        authenticatedAt = session.authenticatedAt, sessionId = session.id), session)
private fun <T> requireSuccess(result: StoreResult<T>): T = (result as? StoreResult.Success)?.value ?: error("Store conformance failed: $result")
private fun <T> requireOperation(result: IdentityOperationResult<T>): T = (result as? IdentityOperationResult.Success)?.value ?: error("Service conformance failed: $result")
