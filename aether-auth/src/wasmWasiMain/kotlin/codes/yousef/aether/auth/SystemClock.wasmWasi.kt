package codes.yousef.aether.auth

actual object SystemClock {
    actual fun now(): Long {
        // TODO: Implement WASI clock
        return 0L
    }
}
