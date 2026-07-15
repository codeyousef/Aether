package codes.yousef.aether.auth

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Server-trusted provenance for an account-onboarding or passkey-enrollment request.
 *
 * Callers must derive this value from an authenticated session or a verified one-time credential;
 * it is never accepted from an HTTP request body. A future public-registration route may use
 * [PUBLIC], but the 0.6 identity HTTP API intentionally does not expose such a route.
 */
enum class IdentityRegistrationSource {
    PUBLIC,
    INVITATION,
    ADMIN_INVITATION,
    BOOTSTRAP,
    RECOVERY,
    EXISTING_ACCOUNT
}

/** Central registration-policy matrix shared by all onboarding entry points. */
fun IdentityConfig.allowsRegistration(source: IdentityRegistrationSource): Boolean = when (source) {
    IdentityRegistrationSource.PUBLIC ->
        environment == IdentityEnvironment.DEVELOPMENT && registrationPolicy == RegistrationPolicy.OPEN

    IdentityRegistrationSource.INVITATION ->
        registrationPolicy == RegistrationPolicy.OPEN ||
            registrationPolicy == RegistrationPolicy.INVITATION_ONLY

    IdentityRegistrationSource.ADMIN_INVITATION ->
        registrationPolicy == RegistrationPolicy.OPEN ||
            registrationPolicy == RegistrationPolicy.INVITATION_ONLY ||
            registrationPolicy == RegistrationPolicy.ADMIN_ONLY

    // These sources prove prior authority and are deliberately independent of account creation
    // policy: bootstrap is single-use, recovery is constrained, and an existing account is not a
    // new registration.
    // The authenticated bootstrap session is itself the durable proof that the single-use secret
    // was consumed. Enrollment must remain possible after operators retire that secret reference.
    IdentityRegistrationSource.BOOTSTRAP -> true

    IdentityRegistrationSource.RECOVERY,
    IdentityRegistrationSource.EXISTING_ACCOUNT -> true
}

internal fun IdentitySession.passkeyRegistrationSource(): IdentityRegistrationSource =
    when (authenticationMethod) {
        SessionAuthenticationMethod.BOOTSTRAP -> IdentityRegistrationSource.BOOTSTRAP
        SessionAuthenticationMethod.RECOVERY_CODE,
        SessionAuthenticationMethod.ADMINISTRATIVE_RECOVERY -> IdentityRegistrationSource.RECOVERY
        SessionAuthenticationMethod.INVITATION -> IdentityRegistrationSource.ADMIN_INVITATION
        SessionAuthenticationMethod.PASSKEY,
        SessionAuthenticationMethod.OIDC,
        SessionAuthenticationMethod.SAML -> IdentityRegistrationSource.EXISTING_ACCOUNT
    }

/** Account-level mutations which can create or replace durable authentication material. */
internal enum class IdentityAccountSecurityAction {
    ENROLL_PASSKEY,
    REVOKE_PASSKEY,
    REPLACE_RECOVERY_CODES
}

/**
 * Central authorization policy for account-level credential mutations exposed by the HTTP API.
 *
 * Existing accounts must prove a recent passkey authentication. The only exception is passkey
 * enrollment from a deliberately restricted, one-time bootstrap/invitation/recovery session; those
 * sessions are constrained by [IdentityHttpApi] to enrollment and logout routes.
 */
internal fun IdentityContext.accountSecurityError(
    action: IdentityAccountSecurityAction,
    now: Instant,
    recentPasskeyLifetime: IdentityDuration
): IdentityErrorCode? {
    val userPrincipal = principal?.takeIf { it.kind == IdentityPrincipalKind.USER }
        ?: return IdentityErrorCode.AUTHENTICATION_REQUIRED
    val userSession = session?.takeIf {
        it.id == userPrincipal.sessionId && it.userId == userPrincipal.userId
    } ?: return IdentityErrorCode.AUTHENTICATION_REQUIRED
    if (!isSessionUsableAt(now)) return IdentityErrorCode.AUTHENTICATION_REQUIRED

    if (action == IdentityAccountSecurityAction.ENROLL_PASSKEY &&
        userSession.authenticationMethod in CONSTRAINED_PASSKEY_ENROLLMENT_METHODS
    ) {
        return null
    }

    val recentPasskey = userSession.authenticationMethod == SessionAuthenticationMethod.PASSKEY &&
        userSession.assurance.satisfies(AuthenticationAssurance.PASSKEY) &&
        userSession.authenticatedAt <= now &&
        now - userSession.authenticatedAt <= recentPasskeyLifetime.seconds.seconds
    return if (recentPasskey) null else IdentityErrorCode.STEP_UP_REQUIRED
}

private val CONSTRAINED_PASSKEY_ENROLLMENT_METHODS = setOf(
    SessionAuthenticationMethod.BOOTSTRAP,
    SessionAuthenticationMethod.INVITATION,
    SessionAuthenticationMethod.RECOVERY_CODE,
    SessionAuthenticationMethod.ADMINISTRATIVE_RECOVERY
)
