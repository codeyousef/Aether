package codes.yousef.aether.tasks

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.datetime.Clock

/**
 * In-memory task store for testing and development.
 * NOT recommended for production use as tasks are lost on restart.
 */
class InMemoryTaskStore : TaskStore {
    private val tasks = atomic(mapOf<String, TaskRecord>())
    
    override suspend fun save(task: TaskRecord): TaskRecord {
        tasks.update { it + (task.id to task) }
        return task
    }

    override suspend fun getById(id: String): TaskRecord? {
        return tasks.value[id]
    }

    override suspend fun claimNext(queue: String, workerId: String): TaskRecord? {
        val now = Clock.System.now().toEpochMilliseconds()
        
        var claimed: TaskRecord? = null
        tasks.update { current ->
            val task = current.values
                .filter { it.status == TaskStatus.PENDING && it.queue == queue && it.scheduledFor <= now }
                .sortedWith(compareByDescending<TaskRecord> { it.priority.value }.thenBy { it.createdAt })
                .firstOrNull()
            
            if (task != null) {
                claimed = task.copy(
                    status = TaskStatus.PROCESSING,
                    workerId = workerId,
                    startedAt = now
                )
                current + (task.id to claimed!!)
            } else {
                current
            }
        }
        
        return claimed
    }

    override suspend fun update(task: TaskRecord): TaskRecord {
        tasks.update { it + (task.id to task) }
        return task
    }

    override suspend fun getByStatus(status: TaskStatus, limit: Int): List<TaskRecord> {
        return tasks.value.values
            .filter { it.status == status }
            .sortedBy { it.createdAt }
            .take(limit)
    }

    override suspend fun getByQueue(queue: String, limit: Int): List<TaskRecord> {
        return tasks.value.values
            .filter { it.queue == queue }
            .sortedBy { it.createdAt }
            .take(limit)
    }

    override suspend fun deleteOlderThan(timestamp: Long, status: TaskStatus): Int {
        var deleted = 0
        tasks.update { current ->
            val toRemove = current.values
                .filter { it.status == status && (it.completedAt ?: 0) < timestamp }
                .map { it.id }
            deleted = toRemove.size
            current - toRemove.toSet()
        }
        return deleted
    }

    override suspend fun countByStatus(status: TaskStatus): Long {
        return tasks.value.values.count { it.status == status }.toLong()
    }

    override suspend fun releaseStale(olderThan: Long): Int {
        var released = 0
        tasks.update { current ->
            val stale = current.values
                .filter { it.status == TaskStatus.PROCESSING && (it.startedAt ?: 0) < olderThan }
            
            released = stale.size
            
            var updated = current
            for (task in stale) {
                updated = updated + (task.id to task.copy(
                    status = TaskStatus.PENDING,
                    workerId = null,
                    startedAt = null
                ))
            }
            updated
        }
        return released
    }

    /**
     * Clear all tasks. Useful for testing.
     */
    fun clear() {
        tasks.update { emptyMap() }
    }
}
