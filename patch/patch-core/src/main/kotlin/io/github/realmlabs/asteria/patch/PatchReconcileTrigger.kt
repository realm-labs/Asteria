package io.github.realmlabs.asteria.patch

import kotlinx.coroutines.flow.Flow

/**
 * Emits signals when a node should reconcile its in-memory patch state with durable desired state.
 *
 * Signals are hints. Implementations may coalesce, duplicate, or resync after backend reconnects, so consumers should
 * call [PatchApplicationService.reconcileEnabledPatches] instead of applying a single patch from event payloads.
 */
fun interface PatchReconcileTrigger {
    fun signals(environment: PatchEnvironment): Flow<Unit>
}
