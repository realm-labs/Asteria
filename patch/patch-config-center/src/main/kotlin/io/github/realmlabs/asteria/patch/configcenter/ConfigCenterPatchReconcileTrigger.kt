package io.github.realmlabs.asteria.patch.configcenter

import io.github.realmlabs.asteria.config.center.ConfigEvent
import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.config.center.ConfigWatchMode
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.PatchEnvironment
import io.github.realmlabs.asteria.patch.PatchReconcileTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Watches config-center patch desired state for one node environment and emits reconcile signals.
 */
class ConfigCenterPatchReconcileTrigger(
    private val store: ConfigStore,
    rootPath: String = "/asteria/runtime-patches",
    private val watchRetryDelay: Duration = 5.seconds,
    private val metrics: Metrics = NoopMetrics,
) : PatchReconcileTrigger {
    private val paths = ConfigCenterPatchPaths(rootPath)
    private val logger = LoggerFactory.getLogger(ConfigCenterPatchReconcileTrigger::class.java)

    override fun signals(environment: PatchEnvironment): Flow<Unit> {
        val path = paths.patchesPath(environment.appName, environment.version)
        return flow {
            val tags = MetricTags.of("backend" to "config-center", "mode" to ConfigWatchMode.Children.name)
            while (currentCoroutineContext().isActive) {
                metrics.counter("asteria.patch.config_center.watch.total", tags).increment()
                val watch = try {
                    store.watch(path, ConfigWatchMode.Children)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    metrics.counter("asteria.patch.config_center.watch.failed.total", tags).increment()
                    logger.warn("config-center patch watch create failed path={}", path.value, error)
                    delayRetry()
                    continue
                }

                try {
                    emit(Unit)
                    watch.events.collect { event ->
                        if (event is ConfigEvent.Upserted || event is ConfigEvent.Deleted || event is ConfigEvent.Resynced) {
                            metrics.counter("asteria.patch.config_center.watch.event.total", tags).increment()
                            emit(Unit)
                        }
                    }
                    logger.warn("config-center patch watch completed path={}; rebuilding", path.value)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    metrics.counter("asteria.patch.config_center.watch.failed.total", tags).increment()
                    logger.warn("config-center patch watch failed path={}; rebuilding", path.value, error)
                } finally {
                    watch.close()
                }
                delayRetry()
            }
        }
    }

    private suspend fun delayRetry() {
        if (watchRetryDelay > Duration.ZERO) {
            delay(watchRetryDelay)
        }
    }
}
