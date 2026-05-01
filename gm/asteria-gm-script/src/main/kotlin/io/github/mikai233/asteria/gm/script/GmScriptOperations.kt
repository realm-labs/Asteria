package io.github.mikai233.asteria.gm.script

import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.job.ScriptJob
import io.github.mikai233.asteria.script.job.ScriptJobCancellation
import io.github.mikai233.asteria.script.job.ScriptJobId
import io.github.mikai233.asteria.script.job.ScriptJobItem
import io.github.mikai233.asteria.script.job.ScriptJobItemId
import io.github.mikai233.asteria.script.job.ScriptJobItemPage
import io.github.mikai233.asteria.script.job.ScriptJobItemQuery
import io.github.mikai233.asteria.script.job.ScriptJobPage
import io.github.mikai233.asteria.script.job.ScriptJobQuery
import io.github.mikai233.asteria.script.job.ScriptJobService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface GmScriptOperations {
    suspend fun submit(
        command: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
    ): ScriptJob

    suspend fun find(jobId: ScriptJobId): ScriptJob?

    suspend fun listJobs(query: ScriptJobQuery = ScriptJobQuery()): ScriptJobPage

    suspend fun listItems(
        jobId: ScriptJobId,
        query: ScriptJobItemQuery = ScriptJobItemQuery(),
    ): ScriptJobItemPage

    suspend fun findItem(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
    ): ScriptJobItem?

    suspend fun retryItem(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        timeout: Duration = 3.seconds,
    ): ScriptJobItem

    suspend fun cancelJob(
        jobId: ScriptJobId,
        cancellation: ScriptJobCancellation = ScriptJobCancellation(),
    ): ScriptJob?

    suspend fun cancelItem(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        cancellation: ScriptJobCancellation = ScriptJobCancellation(),
    ): ScriptJobItem?
}

class ScriptJobGmScriptOperations(
    private val jobs: ScriptJobService,
) : GmScriptOperations {
    override suspend fun submit(
        command: ScriptExecutionCommand,
        timeout: Duration,
    ): ScriptJob {
        return jobs.submit(command, timeout)
    }

    override suspend fun find(jobId: ScriptJobId): ScriptJob? {
        return jobs.find(jobId)
    }

    override suspend fun listJobs(query: ScriptJobQuery): ScriptJobPage {
        return jobs.listJobs(query)
    }

    override suspend fun listItems(jobId: ScriptJobId, query: ScriptJobItemQuery): ScriptJobItemPage {
        return jobs.listItems(jobId, query)
    }

    override suspend fun findItem(jobId: ScriptJobId, itemId: ScriptJobItemId): ScriptJobItem? {
        return jobs.findItem(jobId, itemId)
    }

    override suspend fun retryItem(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        timeout: Duration,
    ): ScriptJobItem {
        return jobs.retryItem(jobId, itemId, timeout)
    }

    override suspend fun cancelJob(jobId: ScriptJobId, cancellation: ScriptJobCancellation): ScriptJob? {
        return jobs.cancelJob(jobId, cancellation)
    }

    override suspend fun cancelItem(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        cancellation: ScriptJobCancellation,
    ): ScriptJobItem? {
        return jobs.cancelItem(jobId, itemId, cancellation)
    }
}
