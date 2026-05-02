package io.github.mikai233.asteria.config

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reason to attempt a config reload.
 *
 * Triggers usually come from config-center watch events, local file watchers, or operator commands. The signal is
 * metadata only; [ConfigService] still reloads a complete snapshot and validates it before publishing.
 */
data class ConfigReloadSignal(
    val reason: String,
    val source: String? = null,
) {
    init {
        require(reason.isNotBlank()) { "config reload signal reason must not be blank" }
        require(source == null || source.isNotBlank()) { "config reload signal source must not be blank" }
    }
}

/**
 * Produces reload signals for [ConfigHotReloadService].
 */
fun interface ConfigReloadTrigger {
    fun events(): Flow<ConfigReloadSignal>
}

/**
 * Event emitted when hot reload fails.
 *
 * The current snapshot remains unchanged on failure.
 */
data class ConfigReloadFailed(
    val signal: ConfigReloadSignal?,
    val error: Throwable,
    val occurredAt: Instant = Instant.now(),
)

/**
 * Listener for failed hot reload attempts.
 */
fun interface ConfigReloadFailureListener {
    suspend fun failed(event: ConfigReloadFailed)
}

/**
 * Hot reload behavior used by [ConfigHotReloadService].
 */
data class ConfigHotReloadOptions(
    val trigger: ConfigReloadTrigger,
    val debounce: Duration = 2.seconds,
    val retryDelay: Duration = 5.seconds,
    val failureListeners: List<ConfigReloadFailureListener> = emptyList(),
) {
    init {
        require(!debounce.isNegative()) { "config hot reload debounce must not be negative" }
        require(!retryDelay.isNegative()) { "config hot reload retry delay must not be negative" }
    }
}

/**
 * Background service that turns external change signals into safe full-snapshot reloads.
 *
 * Reload attempts are serialized by [ConfigService]. A failed reload or validator failure is reported to
 * [ConfigReloadFailureListener] and does not replace the previously published snapshot.
 */
class ConfigHotReloadService(
    private val service: ConfigService,
    private val options: ConfigHotReloadOptions,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var job: Job? = null

    fun start(): Job {
        val current = job
        if (current != null && current.isActive) {
            return current
        }
        return scope.launch {
            runLoop()
        }.also {
            job = it
        }
    }

    suspend fun stop() {
        val current = job ?: return
        job = null
        current.cancelAndJoin()
    }

    @OptIn(FlowPreview::class)
    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val events = if (options.debounce == Duration.ZERO) {
                    options.trigger.events()
                } else {
                    options.trigger.events().debounce(options.debounce)
                }
                events.collect { signal ->
                    reload(signal)
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                notifyFailure(ConfigReloadFailed(signal = null, error = error))
                if (options.retryDelay > Duration.ZERO) {
                    delay(options.retryDelay)
                }
            }
        }
    }

    private suspend fun reload(signal: ConfigReloadSignal) {
        try {
            service.reload()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            notifyFailure(ConfigReloadFailed(signal = signal, error = error))
        }
    }

    private suspend fun notifyFailure(event: ConfigReloadFailed) {
        for (listener in options.failureListeners) {
            listener.failed(event)
        }
    }
}
