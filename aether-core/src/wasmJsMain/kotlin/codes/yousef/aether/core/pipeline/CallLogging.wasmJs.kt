package codes.yousef.aether.core.pipeline

/**
 * WasmJS implementation of Logger using console.log.
 */
actual interface Logger {
    actual fun trace(message: String)
    actual fun debug(message: String)
    actual fun info(message: String)
    actual fun warn(message: String)
    actual fun error(message: String, throwable: Throwable?)
}

/**
 * Console-based logger for WasmJS.
 */
private class ConsoleLogger(private val name: String) : Logger {
    override fun trace(message: String) {
        println("[TRACE] [$name] $message")
    }

    override fun debug(message: String) {
        println("[DEBUG] [$name] $message")
    }

    override fun info(message: String) {
        println("[INFO] [$name] $message")
    }

    override fun warn(message: String) {
        println("[WARN] [$name] $message")
    }

    override fun error(message: String, throwable: Throwable?) {
        println("[ERROR] [$name] $message")
        throwable?.printStackTrace()
    }
}

/**
 * WasmJS implementation of LoggerFactory.
 */
actual object LoggerFactory {
    actual fun getLogger(name: String): Logger {
        return ConsoleLogger(name)
    }
}
