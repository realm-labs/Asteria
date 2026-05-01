package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.script.ScriptExecutionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ScriptJobStore {
    suspend fun create(job: ScriptJob)

    suspend fun markRunning(id: ScriptJobId)

    suspend fun appendResults(id: ScriptJobId, results: List<ScriptExecutionResult>)

    suspend fun markFinished(id: ScriptJobId, status: ScriptJobStatus)

    suspend fun find(id: ScriptJobId): ScriptJob?
}

class InMemoryScriptJobStore : ScriptJobStore {
    private val mutex = Mutex()
    private val jobs: MutableMap<ScriptJobId, ScriptJob> = linkedMapOf()

    override suspend fun create(job: ScriptJob) {
        mutex.withLock {
            check(job.id !in jobs) { "script job ${job.id} already exists" }
            jobs[job.id] = job
        }
    }

    override suspend fun markRunning(id: ScriptJobId) {
        update(id) { copy(status = ScriptJobStatus.Running, updatedAtMillis = System.currentTimeMillis()) }
    }

    override suspend fun appendResults(id: ScriptJobId, results: List<ScriptExecutionResult>) {
        if (results.isEmpty()) {
            return
        }
        update(id) {
            copy(
                results = this.results + results,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun markFinished(id: ScriptJobId, status: ScriptJobStatus) {
        require(status == ScriptJobStatus.Completed || status == ScriptJobStatus.PartialFailed || status == ScriptJobStatus.Failed) {
            "script job finish status must be terminal"
        }
        update(id) { copy(status = status, updatedAtMillis = System.currentTimeMillis()) }
    }

    override suspend fun find(id: ScriptJobId): ScriptJob? {
        return mutex.withLock { jobs[id] }
    }

    private suspend fun update(id: ScriptJobId, block: ScriptJob.() -> ScriptJob) {
        mutex.withLock {
            val job = requireNotNull(jobs[id]) { "script job $id not found" }
            jobs[id] = job.block()
        }
    }
}
