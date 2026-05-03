package io.github.realmlabs.asteria.script.job.spring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.DisposableBean
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine scope owned by the script job Spring starter.
 */
class ScriptJobCoroutineScope : CoroutineScope, DisposableBean {
    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext = Dispatchers.Default + job

    override fun destroy() {
        cancel()
    }
}
