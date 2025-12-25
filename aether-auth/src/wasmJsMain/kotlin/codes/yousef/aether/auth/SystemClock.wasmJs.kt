package codes.yousef.aether.auth

private fun jsNow(): Double = js("Date.now()")

actual object SystemClock {
    actual fun now(): Long = jsNow().toLong()
}
