package io.github.mikai233.asteria.gm.script

import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.job.ScriptJob
import io.github.mikai233.asteria.script.job.ScriptJobId
import io.github.mikai233.asteria.script.job.ScriptJobService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface GmScriptOperations {
    suspend fun submit(
        command: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
    ): ScriptJob

    suspend fun find(jobId: ScriptJobId): ScriptJob?
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
}
