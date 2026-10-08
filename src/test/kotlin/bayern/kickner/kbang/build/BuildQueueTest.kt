package bayern.kickner.kbang.build

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildQueueTest {

    @Test
    fun `one slot never runs two blocks at once`() = runBlocking {
        val queue = BuildQueue(maxParallel = 1, maxQueued = 4)
        val running = AtomicInteger()
        val maxSeen = AtomicInteger()

        (1..5).map {
            launch(Dispatchers.Default) {
                queue.withSlot {
                    val now = running.incrementAndGet()
                    maxSeen.accumulateAndGet(now, ::max)
                    delay(30)
                    running.decrementAndGet()
                }
            }
        }.joinAll()

        assertEquals(1, maxSeen.get())
    }

    @Test
    fun `two slots run two blocks at once`() = runBlocking {
        val queue = BuildQueue(maxParallel = 2, maxQueued = 0)
        val bothInside = CompletableDeferred<Unit>()
        val inside = AtomicInteger()

        listOf(1, 2).map {
            launch(Dispatchers.Default) {
                queue.withSlot {
                    if (inside.incrementAndGet() == 2) bothInside.complete(Unit)
                    bothInside.await()
                }
            }
        }.joinAll()

        assertEquals(2, inside.get())
    }

    @Test
    fun `a full queue refuses at once`() = runBlocking {
        val queue = BuildQueue(maxParallel = 1, maxQueued = 0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) {
            queue.withSlot {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()

        assertNull(queue.withSlot { "second" })

        release.complete(Unit)
        first.join()
        assertEquals("third", queue.withSlot { "third" })
    }

    @Test
    fun `waiting calls are admitted up to the queue size`() = runBlocking {
        val queue = BuildQueue(maxParallel = 1, maxQueued = 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) {
            queue.withSlot {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        var secondRan = false
        val second = launch(Dispatchers.Default) { queue.withSlot { secondRan = true } }
        delay(50)

        assertNull(queue.withSlot { "third" })

        release.complete(Unit)
        joinAll(first, second)
        assertEquals(true, secondRan)
    }

    @Test
    fun `a cancelled waiter gives its place back`() = runBlocking {
        val queue = BuildQueue(maxParallel = 1, maxQueued = 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) {
            queue.withSlot {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        val waiter = launch(Dispatchers.Default) { queue.withSlot { } }
        delay(50)

        waiter.cancelAndJoin()

        val third = launch(Dispatchers.Default) { assertEquals("third", queue.withSlot { "third" }) }
        delay(50)
        release.complete(Unit)
        joinAll(first, third)
    }

    @Test
    fun `the slot is released when the block throws`() = runBlocking {
        val queue = BuildQueue(maxParallel = 1, maxQueued = 0)

        runCatching { queue.withSlot { error("boom") } }

        assertEquals("next", queue.withSlot { "next" })
    }
}
