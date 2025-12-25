package codes.yousef.aether.auth

actual object SystemClock {
    actual fun now(): Long = System.currentTimeMillis()
}
