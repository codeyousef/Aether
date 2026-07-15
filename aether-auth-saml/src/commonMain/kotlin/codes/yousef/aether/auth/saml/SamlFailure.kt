package codes.yousef.aether.auth.saml

internal class SamlAbort(val code: SamlErrorCode) : RuntimeException()

internal fun samlAbort(code: SamlErrorCode): Nothing = throw SamlAbort(code)
