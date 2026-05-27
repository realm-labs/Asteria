package io.github.realmlabs.asteria.persistence

import kotlin.coroutines.cancellation.CancellationException

internal class BatchFailure {
    private var first: Throwable? = null

    fun record(error: Throwable) {
        if (error is CancellationException || error is Error) {
            throw error
        }
        val current = first
        if (current == null) {
            first = error
        } else {
            current.addSuppressed(error)
        }
    }

    fun throwIfAny() {
        first?.let { throw it }
    }
}
