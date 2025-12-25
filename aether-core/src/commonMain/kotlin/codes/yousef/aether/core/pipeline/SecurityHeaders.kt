package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Exchange

/**
 * Configuration for Security Headers middleware.
 */
data class SecurityHeadersConfig(
    val hstsMaxAge: Long = 31536000, // 1 year
    val hstsIncludeSubDomains: Boolean = true,
    val hstsPreload: Boolean = false,
    val frameOptions: FrameOptions = FrameOptions.DENY,
    val contentTypeOptions: Boolean = true, // nosniff
    val xssProtection: Boolean = true, // 1; mode=block
    val referrerPolicy: ReferrerPolicy = ReferrerPolicy.SAME_ORIGIN,
    val contentSecurityPolicy: String? = null
) {
    enum class FrameOptions(val value: String) {
        DENY("DENY"),
        SAMEORIGIN("SAMEORIGIN")
    }

    enum class ReferrerPolicy(val value: String) {
        NO_REFERRER("no-referrer"),
        NO_REFERRER_WHEN_DOWNGRADE("no-referrer-when-downgrade"),
        ORIGIN("origin"),
        ORIGIN_WHEN_CROSS_ORIGIN("origin-when-cross-origin"),
        SAME_ORIGIN("same-origin"),
        STRICT_ORIGIN("strict-origin"),
        STRICT_ORIGIN_WHEN_CROSS_ORIGIN("strict-origin-when-cross-origin"),
        UNSAFE_URL("unsafe-url")
    }
}

/**
 * Middleware that adds standard security headers to responses.
 */
class SecurityHeaders(private val config: SecurityHeadersConfig) {
    
    suspend operator fun invoke(exchange: Exchange, next: suspend () -> Unit) {
        // Add headers before processing (so they are present even if handler fails, 
        // though usually headers are written when response is committed)
        // Since we are in a pipeline, we can add them to the response object now.
        
        // HSTS
        val hstsValue = buildString {
            append("max-age=${config.hstsMaxAge}")
            if (config.hstsIncludeSubDomains) append("; includeSubDomains")
            if (config.hstsPreload) append("; preload")
        }
        exchange.response.setHeader("Strict-Transport-Security", hstsValue)
        
        // X-Frame-Options
        exchange.response.setHeader("X-Frame-Options", config.frameOptions.value)
        
        // X-Content-Type-Options
        if (config.contentTypeOptions) {
            exchange.response.setHeader("X-Content-Type-Options", "nosniff")
        }
        
        // X-XSS-Protection
        if (config.xssProtection) {
            exchange.response.setHeader("X-XSS-Protection", "1; mode=block")
        }
        
        // Referrer-Policy
        exchange.response.setHeader("Referrer-Policy", config.referrerPolicy.value)
        
        // Content-Security-Policy
        if (config.contentSecurityPolicy != null) {
            exchange.response.setHeader("Content-Security-Policy", config.contentSecurityPolicy)
        }
        
        next()
    }
}

/**
 * Installs Security Headers middleware.
 */
fun Pipeline.installSecurityHeaders(configure: SecurityHeadersConfig.() -> Unit = {}) {
    val config = SecurityHeadersConfig().apply(configure)
    use(SecurityHeaders(config)::invoke)
}
