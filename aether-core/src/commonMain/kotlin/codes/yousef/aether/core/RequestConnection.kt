package codes.yousef.aether.core

/**
 * Connection metadata observed directly by the HTTP server.
 *
 * These values must not be populated from `Forwarded` or `X-Forwarded-*`
 * headers. Applications that are deployed behind a proxy can resolve those
 * headers explicitly with [TrustedProxyResolver].
 */
data class RequestConnection(
    val scheme: String? = null,
    val host: String? = null,
    val peerAddress: String? = null
) {
    /** The direct request origin, when both scheme and host are available. */
    val origin: String?
        get() = if (scheme != null && host != null) "$scheme://$host" else null
}

/**
 * Connection metadata after applying an explicit trusted-proxy policy.
 */
data class ResolvedRequestConnection(
    val scheme: String?,
    val host: String?,
    val clientAddress: String?,
    val usedForwardedHeaders: Boolean
) {
    val origin: String?
        get() = if (scheme != null && host != null) "$scheme://$host" else null
}

/**
 * Resolves `X-Forwarded-*` metadata only when the immediate network peer is
 * allowlisted as a trusted proxy.
 *
 * A forwarded-for chain is evaluated from right to left. Trusted proxy hops
 * are skipped and the first untrusted hop is treated as the client. For proto
 * and host, the right-most value is used so a client-controlled value prepended
 * to a header cannot override the value written by the trusted ingress.
 */
class TrustedProxyResolver(
    trustedPeerAddresses: Set<String>,
    private val forwardedForHeader: String = "X-Forwarded-For",
    private val forwardedProtoHeader: String = "X-Forwarded-Proto",
    private val forwardedHostHeader: String = "X-Forwarded-Host"
) {
    private val trustedPeers = trustedPeerAddresses.map { value ->
        requireNotNull(IpNetwork.parse(value)) { "Trusted proxy entries must be IP addresses or CIDR ranges" }
    }

    fun resolve(exchange: Exchange): ResolvedRequestConnection = resolve(exchange.request)

    fun resolve(request: Request): ResolvedRequestConnection {
        val direct = request.connection
        val peerAddress = normalizeAddress(direct.peerAddress)
        if (peerAddress == null || !isTrusted(peerAddress)) {
            return ResolvedRequestConnection(
                scheme = direct.scheme,
                host = direct.host,
                clientAddress = peerAddress ?: direct.peerAddress,
                usedForwardedHeaders = false
            )
        }

        val forwardedScheme = lastForwardedValue(request.headers, forwardedProtoHeader)
            ?.lowercase()
            ?.takeIf { it == "http" || it == "https" }
        val forwardedHost = lastForwardedValue(request.headers, forwardedHostHeader)
            ?.takeIf(::isValidHost)
        val forwardedClient = resolveForwardedClient(request.headers, peerAddress)

        return ResolvedRequestConnection(
            scheme = forwardedScheme ?: direct.scheme,
            host = forwardedHost ?: direct.host,
            clientAddress = forwardedClient ?: peerAddress,
            usedForwardedHeaders = forwardedScheme != null ||
                forwardedHost != null ||
                forwardedClient != null
        )
    }

    private fun resolveForwardedClient(headers: Headers, directPeer: String): String? {
        val chain = forwardedValues(headers, forwardedForHeader)
            .mapNotNull(::normalizeAddress)
            .toMutableList()
        if (chain.isEmpty()) return null

        chain += directPeer
        for (index in chain.lastIndex downTo 0) {
            val address = chain[index]
            if (!isTrusted(address)) return address
        }
        return chain.firstOrNull()
    }

    private fun lastForwardedValue(headers: Headers, name: String): String? =
        forwardedValues(headers, name).lastOrNull()

    private fun forwardedValues(headers: Headers, name: String): List<String> =
        headers.getAll(name)
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun normalizeAddress(value: String?): String? {
        var address = value?.trim()?.removeSurrounding("\"") ?: return null
        if (address.isEmpty() || address.equals("unknown", ignoreCase = true)) return null

        if (address.startsWith("[")) {
            val closingBracket = address.indexOf(']')
            if (closingBracket <= 1) return null
            val remainder = address.substring(closingBracket + 1)
            if (remainder.isNotEmpty() &&
                (!remainder.startsWith(':') || remainder.drop(1).toIntOrNull() !in 1..65535)
            ) return null
            address = address.substring(1, closingBracket)
        } else if (address.count { it == ':' } == 1) {
            val possiblePort = address.substringAfterLast(':')
            if (possiblePort.all(Char::isDigit)) {
                address = address.substringBeforeLast(':')
            }
        }

        if (address.isEmpty() || address.any { it.isWhitespace() || it.isISOControl() }) return null
        return address.lowercase().takeIf { parseIpAddress(it) != null }
    }

    private fun isTrusted(address: String): Boolean {
        val bytes = parseIpAddress(address) ?: return false
        return trustedPeers.any { it.matches(bytes) }
    }

    private fun isValidHost(value: String): Boolean =
        value.isNotEmpty() && value.none {
            it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' ||
                it == '?' || it == '#' || it == '@'
        }
}

private data class IpNetwork(private val bytes: ByteArray, private val prefixBits: Int) {
    fun matches(candidate: ByteArray): Boolean {
        if (candidate.size != bytes.size) return false
        val fullBytes = prefixBits / 8
        for (index in 0 until fullBytes) {
            if (candidate[index] != bytes[index]) return false
        }
        val remainingBits = prefixBits % 8
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (candidate[fullBytes].toInt() and mask) == (bytes[fullBytes].toInt() and mask)
    }

    companion object {
        fun parse(value: String): IpNetwork? {
            val pieces = value.trim().split('/')
            if (pieces.size !in 1..2) return null
            val address = parseIpAddress(pieces[0]) ?: return null
            val maximum = address.size * 8
            val prefix = if (pieces.size == 1) maximum else pieces[1].toIntOrNull() ?: return null
            if (prefix !in 0..maximum) return null
            return IpNetwork(address, prefix)
        }
    }
}

private fun parseIpAddress(value: String): ByteArray? =
    if (':' in value) parseIpv6(value) else parseIpv4(value)

private fun parseIpv4(value: String): ByteArray? {
    val pieces = value.split('.')
    if (pieces.size != 4) return null
    return ByteArray(4) { index ->
        val piece = pieces[index]
        if (piece.isEmpty() || piece.length > 3 || !piece.all(Char::isDigit) ||
            (piece.length > 1 && piece.startsWith('0'))
        ) return null
        val number = piece.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        number.toByte()
    }
}

private fun parseIpv6(value: String): ByteArray? {
    if (value.isEmpty() || '%' in value || value.count { it == ':' } < 2) return null
    if (value.count { it == ':' } > 7 && "::" !in value) return null
    if (value.indexOf("::") != value.lastIndexOf("::")) return null

    val compressed = "::" in value
    val halves = if (compressed) value.split("::", limit = 2) else listOf(value)
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { group ->
            if (group.isEmpty() || group.length > 4 || !group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return null
            }
            group.toIntOrNull(16) ?: return null
        }
    }

    val left = groups(halves[0]) ?: return null
    val right = if (compressed) groups(halves[1]) ?: return null else emptyList()
    val missing = 8 - left.size - right.size
    if ((!compressed && missing != 0) || (compressed && missing < 1)) return null
    val all = left + List(missing) { 0 } + right
    if (all.size != 8) return null
    return ByteArray(16).also { bytes ->
        all.forEachIndexed { index, group ->
            bytes[index * 2] = (group ushr 8).toByte()
            bytes[index * 2 + 1] = group.toByte()
        }
    }
}
