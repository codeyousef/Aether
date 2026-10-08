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
    val profile: DiagnosticsProfile = DiagnosticsProfile.DEVELOPMENT
) : AbstractCoroutineContextElement(Key) {
    private val capturedLogs = mutableListOf<QueryLogEntry>()
    val logs: List<QueryLogEntry>
        get() = capturedLogs

    fun record(entry: QueryLogEntry) {
        if (profile == DiagnosticsProfile.DEVELOPMENT) capturedLogs.add(entry)
    }

    companion object Key : CoroutineContext.Key<QueryLogContext>
}
