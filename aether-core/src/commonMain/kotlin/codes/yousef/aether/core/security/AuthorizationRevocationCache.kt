package codes.yousef.aether.core.security

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Small process-local cache for authoritative authorization results.
 *
 * Entries live for at most 15 seconds. Callers pass the currently observed authorization epoch on
 * every lookup; an epoch transition removes the old value immediately instead of waiting for TTL.
 */
class BoundedAuthorizationRevocationCache<Key : Any, Value : Any>(
    private val maximumEntries: Int,
    private val ttlMillis: Long,
    private val timeSource: AuthorizationTimeSource = AuthorizationTimeSource {
        kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
) {
    private data class Entry<Value>(
        val value: Value,
        val epoch: Long,
        val expiresAt: Long,
        val sequence: Long
    )

    private val lock = reentrantLock()
    private val entries = mutableMapOf<Key, Entry<Value>>()
    private var sequence = 0L

    init {
        require(maximumEntries > 0) { "Authorization cache size must be positive" }
        require(ttlMillis in 1..MAX_AUTHORIZATION_CACHE_TTL_MILLIS) {
            "Authorization cache TTL must be 1..$MAX_AUTHORIZATION_CACHE_TTL_MILLIS milliseconds"
        }
    }

    fun get(key: Key, observedEpoch: Long): Value? = lock.withLock {
        require(observedEpoch >= 0) { "Authorization epoch must be non-negative" }
        val entry = entries[key] ?: return@withLock null
        val now = timeSource.nowEpochMilliseconds()
        if (entry.epoch != observedEpoch || now >= entry.expiresAt) {
            entries.remove(key)
            return@withLock null
        }
        entry.value
    }

    fun put(key: Key, epoch: Long, value: Value) = lock.withLock {
        require(epoch >= 0) { "Authorization epoch must be non-negative" }
        val now = timeSource.nowEpochMilliseconds()
        removeExpired(now)
        if (key !in entries && entries.size >= maximumEntries) {
            val victim = entries.minByOrNull { it.value.sequence }?.key
                ?: error("Authorization cache capacity invariant violated")
            entries.remove(victim)
        }
        sequence = if (sequence == Long.MAX_VALUE) 0 else sequence + 1
        entries[key] = Entry(value, epoch, now + ttlMillis, sequence)
    }

    /** Removes a cached value immediately when an authoritative epoch transition is observed. */
    fun invalidateIfEpochChanged(key: Key, observedEpoch: Long) = lock.withLock {
        require(observedEpoch >= 0) { "Authorization epoch must be non-negative" }
        if (entries[key]?.epoch != observedEpoch) entries.remove(key)
    }

    fun invalidate(key: Key): Unit = lock.withLock {
        entries.remove(key)
        Unit
    }

    fun clear() = lock.withLock {
        entries.clear()
    }

    val size: Int get() = lock.withLock { entries.size }

    private fun removeExpired(now: Long) {
        entries.entries.removeAll { now >= it.value.expiresAt }
    }

    companion object {
        const val MAX_AUTHORIZATION_CACHE_TTL_MILLIS: Long = 15_000
    }
}
