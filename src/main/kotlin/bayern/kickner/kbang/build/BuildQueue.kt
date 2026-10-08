package bayern.kickner.kbang.build

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Limits how many builds run at once and how many may wait for a free slot.
 *
 * A native build takes every core and about 2 GB of memory, so running several at once mostly makes all of them
 * slower. Waiting is bounded too: a client that would wait behind a long line gets an immediate answer instead.
 *
 * @param maxParallel Builds that run at the same time, at least 1.
 * @param maxQueued Builds that may wait for a slot, 0 or more.
 */
class BuildQueue(maxParallel: Int, maxQueued: Int) {

    private val capacity = maxParallel + maxQueued
    private val admitted = AtomicInteger(0)
    private val slots = Semaphore(maxParallel)

    /**
     * Runs [block] in a build slot, waiting for one if necessary. Returns null at once, without running [block],
     * when all slots are taken and the queue is full. A caller cancelled while waiting gives its place back.
     */
    suspend fun <T> withSlot(block: suspend () -> T): T? {
        val full = admitted.incrementAndGet() > capacity
        if (full) {
            admitted.decrementAndGet()
            return null
        }
        try {
            return slots.withPermit { block() }
        } finally {
            admitted.decrementAndGet()
        }
    }
}
