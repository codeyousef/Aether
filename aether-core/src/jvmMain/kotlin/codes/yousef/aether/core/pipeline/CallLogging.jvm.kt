package codes.yousef.aether.core.pipeline

import org.slf4j.LoggerFactory as Slf4jLoggerFactory

/**
 * JVM implementation of Logger using SLF4J.
 */
actual interface Logger {
    actual fun trace(message: String)
    actual fun debug(message: String)
    actual fun info(message: String)
    actual fun warn(message: String)
    actual fun error(message: String, throwable: Throwable?)
}

/**
 * SLF4J adapter implementation.
 */
private class Slf4jLogger(private val slf4jLogger: org.slf4j.Logger) : Logger {
    override fun trace(message: String) {
        slf4jLogger.trace(message)
    }

    override fun debug(message: String) {
        slf4jLogger.debug(message)
    }

    override fun info(message: String) {
        slf4jLogger.info(message)
    }

    override fun warn(message: String) {
        slf4jLogger.warn(message)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            slf4jLogger.error(message, throwable)
        } else {
            slf4jLogger.error(message)
        }
    }
}

/**
 * JVM implementation of LoggerFactory using SLF4J.
 */
actual object LoggerFactory {
    actual fun getLogger(name: String): Logger {
        return Slf4jLogger(Slf4jLoggerFactory.getLogger(name))
    }
}
