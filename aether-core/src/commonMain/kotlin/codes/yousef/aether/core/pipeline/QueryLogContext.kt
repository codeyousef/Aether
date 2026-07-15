package codes.yousef.aether.core.pipeline

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

data class QueryLogEntry(
    val sql: String,
    val durationMs: Long,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

class QueryLogContext(
    val logs: MutableList<QueryLogEntry> = mutableListOf()
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<QueryLogContext>
}
