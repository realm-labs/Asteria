package io.github.realmlabs.asteria.script.job.spring

import io.github.realmlabs.asteria.script.job.ScriptJobService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import kotlin.time.toKotlinDuration

/**
 * Starts recovery tasks for a Spring-managed [ScriptJobService].
 */
class ScriptJobServiceLifecycle(
    private val service: ScriptJobService,
    private val scope: ScriptJobCoroutineScope,
    private val properties: ScriptJobSpringProperties,
) : SmartInitializingSingleton, DisposableBean {
    private var recoveryLoop: Job? = null

    override fun afterSingletonsInstantiated() {
        if (properties.recoverOnStart) {
            scope.launch {
                service.resumeIncompleteJobs(
                    timeout = properties.recoveryTimeout.toKotlinDuration(),
                    limit = properties.recoveryLimit,
                )
            }
        }
        properties.recoveryScanInterval?.let { interval ->
            recoveryLoop = service.startRecoveryLoop(
                timeout = properties.recoveryTimeout.toKotlinDuration(),
                limit = properties.recoveryLimit,
                interval = interval.toKotlinDuration(),
            )
        }
    }

    override fun destroy() {
        recoveryLoop?.cancel()
        recoveryLoop = null
    }
}
