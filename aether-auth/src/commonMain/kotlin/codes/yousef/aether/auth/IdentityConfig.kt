package codes.yousef.aether.auth

import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class IdentityDuration private constructor(val seconds: Long) {
    init { require(seconds > 0) { "Identity duration must be positive" } }

    override fun toString(): String = "${seconds}s"

    companion object {
        fun seconds(value: Long): IdentityDuration = IdentityDuration(value)

        fun minutes(value: Long): IdentityDuration {
            require(value in 1..Long.MAX_VALUE / 60) { "Minute duration is out of range" }
            return IdentityDuration(value * 60)
        }

        fun hours(value: Long): IdentityDuration {
            require(value in 1..Long.MAX_VALUE / 3_600) { "Hour duration is out of range" }
            return IdentityDuration(value * 3_600)
        }

        fun days(value: Long): IdentityDuration {
            require(value in 1..Long.MAX_VALUE / 86_400) { "Day duration is out of range" }
            return IdentityDuration(value * 86_400)
        }
    }
}

@Serializable
data class RelyingPartyConfig(
    val id: String,
    val name: String,
    val allowedOrigins: Set<String>
) {
    init {
        requireValidRpId(id)
        require(name.isNotBlank() && name.length <= 200) { "Relying-party name must be 1..200 characters" }
        require(allowedOrigins.isNotEmpty()) { "At least one exact WebAuthn origin is required" }
        allowedOrigins.forEach { origin ->
            val parsed = parseOrigin(origin)
            require(parsed.host == id || parsed.host.endsWith(".$id")) {
                "Origin host must equal or be a subdomain of the RP ID"
            }
        }
    }
}

@Serializable
data class SessionCookieConfig(
    val name: String = "__Host-aether_session",
    val domain: String? = null,
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = true,
    val sameSite: SameSitePolicy = SameSitePolicy.LAX
) {
    init {
        require(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}").matches(name)) { "Invalid session cookie name" }
        require(path.startsWith('/') && ';' !in path && path.length <= 1024) { "Invalid session cookie path" }
        require(domain == null || isValidCookieDomain(domain)) { "Invalid session cookie domain" }
        require(httpOnly) { "Identity session cookies must be HttpOnly" }
        require(sameSite != SameSitePolicy.NONE || secure) { "SameSite=None requires a Secure cookie" }
        if (name.startsWith("__Host-")) {
            require(secure && domain == null && path == "/") {
                "__Host- cookies require Secure, no Domain, and Path=/"
            }
        }
        if (name.startsWith("__Secure-")) {
            require(secure) { "__Secure- cookies require Secure" }
        }
    }

}

@Serializable
data class IdentityLifetimes(
    val sessionAbsolute: IdentityDuration = IdentityDuration.days(30),
    val sessionIdle: IdentityDuration = IdentityDuration.hours(24),
    val challenge: IdentityDuration = IdentityDuration.minutes(5),
    val recentPasskey: IdentityDuration = IdentityDuration.minutes(5),
    val recoveryChallenge: IdentityDuration = IdentityDuration.minutes(15),
    val recoverySession: IdentityDuration = IdentityDuration.minutes(15),
    val invitation: IdentityDuration = IdentityDuration.days(7),
    val deviceGrant: IdentityDuration = IdentityDuration.minutes(10),
    val deviceAccessToken: IdentityDuration = IdentityDuration.minutes(15),
    val deviceRefreshToken: IdentityDuration = IdentityDuration.days(30),
    val serviceCredential: IdentityDuration = IdentityDuration.days(30),
    val serviceCredentialMaximum: IdentityDuration = IdentityDuration.days(90),
    val serviceCredentialOverlap: IdentityDuration = IdentityDuration.minutes(10),
    val replayReceipt: IdentityDuration = IdentityDuration.minutes(10)
) {
    init {
        require(sessionIdle.seconds <= sessionAbsolute.seconds) {
            "Session idle lifetime must not exceed absolute lifetime"
        }
        require(sessionAbsolute.seconds <= IdentityDuration.days(90).seconds) {
            "Session absolute lifetime must not exceed 90 days"
        }
        require(challenge.seconds in 30..IdentityDuration.minutes(10).seconds) {
            "Challenge lifetime must be between 30 seconds and 10 minutes"
        }
        require(recoveryChallenge.seconds in challenge.seconds..IdentityDuration.hours(1).seconds) {
            "Recovery challenge lifetime must be at least the normal challenge lifetime and at most one hour"
        }
        require(recentPasskey.seconds <= IdentityDuration.minutes(15).seconds) {
            "Recent-passkey lifetime must not exceed 15 minutes"
        }
        require(recoverySession.seconds <= IdentityDuration.minutes(30).seconds) {
            "Recovery-session lifetime must not exceed 30 minutes"
        }
        require(deviceGrant.seconds <= IdentityDuration.minutes(30).seconds) {
            "Device grant lifetime must not exceed 30 minutes"
        }
        require(deviceAccessToken.seconds <= IdentityDuration.hours(1).seconds) {
            "Device access-token lifetime must not exceed one hour"
        }
        require(deviceRefreshToken.seconds <= IdentityDuration.days(90).seconds) {
            "Device refresh-token lifetime must not exceed 90 days"
        }
        require(serviceCredential.seconds <= serviceCredentialMaximum.seconds) {
            "Service credential lifetime must not exceed its configured maximum"
        }
        require(serviceCredentialMaximum.seconds <= IdentityDuration.days(90).seconds) {
            "Service credential maximum lifetime must not exceed 90 days"
        }
        require(serviceCredentialOverlap.seconds <= IdentityDuration.hours(24).seconds) {
            "Service credential overlap must not exceed 24 hours"
        }
    }
}

@Serializable
data class TrustedProxyConfig(
    val mode: TrustedProxyMode = TrustedProxyMode.DIRECT_ONLY,
    val trustedCidrs: Set<String> = emptySet()
) {
    init {
        when (mode) {
            TrustedProxyMode.DIRECT_ONLY -> require(trustedCidrs.isEmpty()) {
                "DIRECT_ONLY must not declare trusted proxy ranges"
            }
            TrustedProxyMode.TRUSTED_CIDRS -> require(trustedCidrs.isNotEmpty()) {
                "TRUSTED_CIDRS requires at least one range"
            }
        }
        trustedCidrs.forEach { requireValidCidr(it) }
    }
}

/** Controls which untrusted request attributes may enter the durable audit stream. */
@Serializable
enum class AuditUserAgentPolicy {
    /** Safest default: do not retain a user-agent-derived value. */
    @kotlinx.serialization.SerialName("omit") OMIT,

    /** Retain only a keyed, domain-separated pseudonym; the raw header is never persisted. */
    @kotlinx.serialization.SerialName("pseudonymize") PSEUDONYMIZE
}

/** Storage-neutral audit privacy and lifecycle policy. */
@Serializable
data class IdentityAuditConfig(
    val retention: IdentityDuration = IdentityDuration.days(90),
    val userAgentPolicy: AuditUserAgentPolicy = AuditUserAgentPolicy.OMIT
) {
    init {
        require(retention.seconds <= IdentityDuration.days(3_650).seconds) {
            "Audit retention must not exceed ten years"
        }
    }
}

/** Explicit first-owner bootstrap lifecycle. Retired deployments must remove the secret reference. */
@Serializable
enum class IdentityBootstrapLifecycle {
    @SerialName("pending") PENDING,
    @SerialName("retired") RETIRED
}

@Serializable
data class IdentityKeyConfig(
    val sessionPepper: SecretReference,
    val previousSessionPeppers: List<SecretReference> = emptyList(),
    val recoveryPepper: SecretReference,
    val previousRecoveryPeppers: List<SecretReference> = emptyList(),
    val deviceTokenPepper: SecretReference,
    val previousDeviceTokenPeppers: List<SecretReference> = emptyList(),
    val serviceCredentialPepper: SecretReference,
    val previousServiceCredentialPeppers: List<SecretReference> = emptyList(),
    val auditPseudonymizationKey: SecretReference,
    val encryptionKey: SecretReference,
    val signingKey: SecretReference
) {
    internal fun validateFor(environment: IdentityEnvironment) {
        val references = listOf(
            sessionPepper,
            *previousSessionPeppers.toTypedArray(),
            recoveryPepper,
            *previousRecoveryPeppers.toTypedArray(),
            deviceTokenPepper,
            *previousDeviceTokenPeppers.toTypedArray(),
            serviceCredentialPepper,
            *previousServiceCredentialPeppers.toTypedArray(),
            auditPseudonymizationKey,
            encryptionKey,
            signingKey
        )
        require(references.all { it.environment == environment }) {
            "Every identity secret must belong to the configured environment"
        }
        require(references.map { Triple(it.provider, it.name, it.version) }.toSet().size == references.size) {
            "Identity peppers, encryption keys, and signing keys must use distinct secret references"
        }
        require(previousSessionPeppers.size <= 5 && previousRecoveryPeppers.size <= 5 &&
            previousDeviceTokenPeppers.size <= 5 &&
            previousServiceCredentialPeppers.size <= 5
        ) { "Identity key rings may retain at most five previous versions" }
    }

    fun sessionPepper(version: String?): SecretReference? =
        (listOf(sessionPepper) + previousSessionPeppers).singleOrNull { it.version == version }

    fun recoveryPepper(version: String?): SecretReference? =
        (listOf(recoveryPepper) + previousRecoveryPeppers).singleOrNull { it.version == version }

    fun deviceTokenPepper(version: String?): SecretReference? =
        (listOf(deviceTokenPepper) + previousDeviceTokenPeppers).singleOrNull { it.version == version }

    fun serviceCredentialPepper(version: String?): SecretReference? =
        (listOf(serviceCredentialPepper) + previousServiceCredentialPeppers).singleOrNull { it.version == version }
}

/**
 * Complete identity-server configuration. Environment-derived storage and cookie namespaces make
 * accidental development/production credential sharing observable and invalid at startup.
 */
@Serializable
data class IdentityConfig(
    val environment: IdentityEnvironment,
    /** Canonical externally visible origin; paths and inferred proxy values are never accepted. */
    val publicBaseUrl: String,
    val relyingParty: RelyingPartyConfig,
    val keys: IdentityKeyConfig,
    val storageNamespace: String = "aether_${environment.wireName}",
    val cookie: SessionCookieConfig = SessionCookieConfig(),
    val lifetimes: IdentityLifetimes = IdentityLifetimes(),
    val registrationPolicy: RegistrationPolicy = RegistrationPolicy.INVITATION_ONLY,
    val trustedProxy: TrustedProxyConfig = TrustedProxyConfig(),
    val audit: IdentityAuditConfig = IdentityAuditConfig(),
    val bootstrapLifecycle: IdentityBootstrapLifecycle = IdentityBootstrapLifecycle.PENDING,
    /** Single-use secret used only to establish the first platform owner. */
    val bootstrapSecret: SecretReference? = null
) {
    init {
        val publicOrigin = parseOrigin(publicBaseUrl)
        require(publicBaseUrl in relyingParty.allowedOrigins) {
            "Public base URL must exactly match one configured WebAuthn origin"
        }
        require(Regex("[a-z][a-z0-9_-]{2,63}").matches(storageNamespace)) { "Invalid identity storage namespace" }
        require(environment.wireName in storageNamespace) {
            "Storage namespace must include the environment name"
        }
        keys.validateFor(environment)
        bootstrapSecret?.let { secret ->
            require(secret.environment == environment) {
                "The bootstrap secret must belong to the configured environment"
            }
            val keyReferences = listOf(
                keys.sessionPepper,
                *keys.previousSessionPeppers.toTypedArray(),
                keys.recoveryPepper,
                *keys.previousRecoveryPeppers.toTypedArray(),
                keys.deviceTokenPepper,
                *keys.previousDeviceTokenPeppers.toTypedArray(),
                keys.serviceCredentialPepper,
                *keys.previousServiceCredentialPeppers.toTypedArray(),
                keys.auditPseudonymizationKey,
                keys.encryptionKey,
                keys.signingKey
            )
            require(keyReferences.none { it.provider == secret.provider && it.name == secret.name && it.version == secret.version }) {
                "The bootstrap secret must not reuse an identity key reference"
            }
        }
        if (bootstrapLifecycle == IdentityBootstrapLifecycle.RETIRED) {
            require(bootstrapSecret == null) {
                "A retired bootstrap lifecycle must not retain a bootstrap secret reference"
            }
        }
        relyingParty.allowedOrigins.forEach { origin ->
            val parsed = parseOrigin(origin)
            if (environment == IdentityEnvironment.PRODUCTION || environment == IdentityEnvironment.STAGING) {
                require(parsed.scheme == "https") { "Production and staging origins must use HTTPS" }
                require(!isLoopbackHost(parsed.host)) { "Production and staging origins must not use loopback hosts" }
            } else if (parsed.scheme == "http") {
                require(isLoopbackHost(parsed.host)) { "Plain HTTP is allowed only for local development origins" }
            }
        }
        if (environment == IdentityEnvironment.PRODUCTION || environment == IdentityEnvironment.STAGING) {
            require(cookie.secure) { "Production and staging session cookies must be Secure" }
            require(!isLoopbackHost(relyingParty.id)) { "Production and staging RP IDs must not be loopback hosts" }
            require(publicOrigin.scheme == "https" && !isLoopbackHost(publicOrigin.host)) {
                "Production and staging public base URLs must use non-loopback HTTPS"
            }
        }
        if (environment == IdentityEnvironment.PRODUCTION) {
            require(registrationPolicy != RegistrationPolicy.OPEN) {
                "Open registration is development-only"
            }
            require(bootstrapLifecycle == IdentityBootstrapLifecycle.RETIRED || bootstrapSecret != null) {
                "Pending production bootstrap requires a single-use bootstrap secret reference"
            }
        } else if (registrationPolicy == RegistrationPolicy.OPEN) {
            require(environment == IdentityEnvironment.DEVELOPMENT) {
                "Open registration is development-only"
            }
        }
        cookie.domain?.let { domain ->
            val normalized = domain.removePrefix(".").lowercase()
            require(relyingParty.id == normalized || relyingParty.id.endsWith(".$normalized")) {
                "Cookie domain must contain the configured RP ID"
            }
        }
    }
}

private data class ParsedOrigin(val scheme: String, val host: String)

private fun parseOrigin(value: String): ParsedOrigin {
    require(value == value.trim() && value.isNotEmpty()) { "Origin must not contain surrounding whitespace" }
    require('*' !in value && '@' !in value) { "Origins must be exact and must not contain wildcards or user info" }
    val separator = value.indexOf("://")
    require(separator > 0) { "Origin must contain a scheme" }
    val scheme = value.substring(0, separator)
    require(scheme == "https" || scheme == "http") { "Origin scheme must be https or local-development http" }
    val authority = value.substring(separator + 3)
    require(authority.isNotEmpty() && authority.none { it == '/' || it == '?' || it == '#' }) {
        "Origin must contain only scheme, host, and optional port"
    }

    val (host, port) = if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        require(end > 1) { "Invalid IPv6 origin host" }
        val parsedHost = authority.substring(1, end)
        val remainder = authority.substring(end + 1)
        val parsedPort = when {
            remainder.isEmpty() -> null
            remainder.startsWith(':') -> remainder.substring(1)
            else -> throw IllegalArgumentException("Invalid IPv6 origin authority")
        }
        parsedHost to parsedPort
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) authority.substring(0, colon) to authority.substring(colon + 1) else authority to null
    }

    require(host == host.lowercase() && host.isNotBlank()) { "Origin host must be lowercase and non-blank" }
    if (port != null) {
        val number = port.toIntOrNull()
        require(number != null && number in 1..65535) { "Invalid origin port" }
    }
    return ParsedOrigin(scheme, host)
}

private fun requireValidRpId(value: String) {
    require(value == value.lowercase() && value == value.trim()) { "RP ID must be lowercase without whitespace" }
    require(value.length in 1..253 && !value.startsWith('.') && !value.endsWith('.')) { "Invalid RP ID" }
    if (isLoopbackHost(value)) return
    require("//" !in value && ':' !in value && '/' !in value && '*' !in value) { "RP ID must be a host, not a URL" }
    val labels = value.split('.')
    require(labels.size >= 2) { "RP ID must be a domain or a supported loopback host" }
    labels.forEach { label ->
        require(label.length in 1..63 && Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(label)) {
            "Invalid RP ID label"
        }
    }
}

private fun isLoopbackHost(value: String): Boolean =
    value == "localhost" || value == "127.0.0.1" || value == "::1"

private fun isValidCookieDomain(value: String): Boolean {
    val normalized = value.removePrefix(".")
    return normalized == normalized.lowercase() && runCatching { requireValidRpId(normalized) }.isSuccess
}

private fun requireValidCidr(value: String) {
    val pieces = value.split('/')
    require(pieces.size == 2) { "Trusted proxy range must use CIDR notation" }
    val address = pieces[0]
    val prefix = pieces[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid CIDR prefix")
    if (':' in address) {
        require(prefix in 0..128) { "IPv6 CIDR prefix must be 0..128" }
        require(address.matches(Regex("[0-9a-fA-F:]+")) && address.count { it == ':' } >= 2) { "Invalid IPv6 CIDR address" }
    } else {
        require(prefix in 0..32) { "IPv4 CIDR prefix must be 0..32" }
        val octets = address.split('.')
        require(octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && (octet.toIntOrNull()?.let { it in 0..255 } == true)
        }) { "Invalid IPv4 CIDR address" }
    }
}
