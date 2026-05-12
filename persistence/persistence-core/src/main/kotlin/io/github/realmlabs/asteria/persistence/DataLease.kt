package io.github.realmlabs.asteria.persistence

/**
 * Runtime lease for data that may be unloaded while the owning actor is still alive.
 *
 * Unloadable data must call [ensureActive] before exposing mutable operations. This does not make Kotlin references
 * impossible to leak, but leaked references fail fast after the data manager unloads the data.
 */
class DataLease internal constructor(
    private val label: String,
) {
    private var active: Boolean = true
    private var generation: Long = 0

    /**
     * Fails when a caller is using a reference after the owning cache unloaded it.
     */
    fun ensureActive() {
        check(active) { "$label has been unloaded" }
    }

    /**
     * True until the owning cache unloads the leased data.
     */
    fun active(): Boolean = active

    /**
     * Monotonic invalidation counter for consumers that cache derived state from a lease.
     */
    fun generation(): Long = generation

    internal fun invalidate() {
        active = false
        generation += 1
    }
}

/**
 * Marker for data units that can be safely invalidated when unloaded.
 */
interface DataLeaseAware {
    /**
     * Binds the lease that guards externally reachable mutable operations.
     */
    fun bindLease(lease: DataLease)
}

/**
 * Base class for unloadable data units.
 */
abstract class LeaseGuardedMemData : MemData, DataLeaseAware {
    private var lease: DataLease? = null

    override fun bindLease(lease: DataLease) {
        this.lease = lease
    }

    protected fun ensureActive() {
        requireNotNull(lease) { "data lease is not bound" }.ensureActive()
    }
}
