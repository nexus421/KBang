package bayern.kickner.kbang.build

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a build may take to unwind after its innermost processes got SIGTERM, before the rest is killed. In
 * tests a native build unwinds in about two seconds, the native-image driver removing its temporary directory on
 * the way. Short enough not to hold a build slot for long.
 */
private val DEFAULT_KILL_GRACE_PERIOD = 5.seconds

/** How a process run ended. */
sealed interface ProcessOutcome {
    /** The process exited by itself with [code]. */
    data class Exited(val code: Int) : ProcessOutcome

    /** The process ran longer than allowed and was killed. */
    data object TimedOut : ProcessOutcome
}

/**
 * Runs [command] and waits for it, at most [timeout].
 *
 * [command] is expected to start with `setsid` (see [jbangCommand]), so the started process leads a fresh process
 * group. When the run ends in any way (exit, timeout or cancellation of the calling coroutine), that whole group is
 * stopped, so neither the native-image builder nor a background straggler survives the build: first SIGTERM to
 * the innermost processes, so the others can end in order and remove their temporary files, then SIGKILL for
 * whatever is still running after [killGracePeriod] (see [stopProcessGroup]).
 * stdin is `/dev/null`, so nothing can wait for input, and stdout plus stderr are appended to [log], so no pipe can
 * fill up.
 *
 * @param environment Added to the environment inherited from KBang.
 * @param workingDir Working directory of the process.
 * @param killGracePeriod Time between SIGTERM and SIGKILL when the group is stopped.
 * @param spare Processes that never get a SIGTERM while the group unwinds, see [stopProcessGroup]. By default the
 * native-image driver.
 * @throws java.io.IOException when the program cannot be started at all.
 */
suspend fun runProcessGroup(
    command: List<String>,
    environment: Map<String, String>,
    workingDir: File,
    log: File,
    timeout: Duration,
    killGracePeriod: Duration = DEFAULT_KILL_GRACE_PERIOD,
    spare: (ProcessHandle) -> Boolean = { it.isNativeImageDriver() }
): ProcessOutcome {
    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        .apply { environment().putAll(environment) }
        .start()

    try {
        val exitCode = withTimeoutOrNull(timeout) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
        return if (exitCode == null) ProcessOutcome.TimedOut else ProcessOutcome.Exited(exitCode)
    } finally {
        // Also after a cancellation, and off the caller's thread, the grace period blocks
        withContext(NonCancellable + Dispatchers.IO) { stopProcessGroup(process, killGracePeriod, spare) }
    }
}

/**
 * Stops the process group led by [process], innermost processes first, so the others can end in order:
 *
 * 1. Until the tree is gone or [gracePeriod] has passed: SIGTERM to every leaf of the process tree (a process
 *    without children), or to [process] itself while it has none, except to [spare] processes. The tree is looked
 *    at again every 50 ms, so a process started meanwhile gets its SIGTERM too. In a native build the leaf is the
 *    native-image builder JVM (or gcc while linking), and its parents end on their own once it is gone.
 * 2. SIGKILL to the whole group and to every process ever seen in the tree, then waits for the leader.
 * 3. Removes the temporary directories of native-image drivers that were named by a builder of this tree and still
 *    exist, see [driverTempDirs].
 *
 * Every process ever seen is remembered because an ended parent leaves its children reparented, after which they
 * are no longer reachable through [Process.descendants]. The group SIGKILL catches anything started unseen. When
 * the group is already gone, `kill` merely fails, which is fine.
 */
private fun stopProcessGroup(process: Process, gracePeriod: Duration, spare: (ProcessHandle) -> Boolean) {
    val seen = LinkedHashMap<Long, ProcessHandle>()
    val commandLines = mutableSetOf<String>()
    val deadline = System.nanoTime() + gracePeriod.inWholeNanoseconds
    while (true) {
        val tree = process.descendants().toList()
        tree.forEach { handle ->
            seen.putIfAbsent(handle.pid(), handle)
            handle.info().commandLine().ifPresent { commandLines += it }
        }
        val running = process.isAlive || seen.values.any { it.isAlive }
        if (running.not() || System.nanoTime() >= deadline) break

        val parents = tree.mapNotNull { it.parent().orElse(null)?.pid() }.toSet()
        val leaves = tree.filter { (it.pid() in parents).not() }.ifEmpty { listOfNotNull(process.toHandle().takeIf { process.isAlive }) }
        leaves.filterNot(spare).forEach { it.destroy() }
        Thread.sleep(50)
    }

    signalGroup(process, "KILL")
    seen.values.forEach { it.destroyForcibly() }
    process.destroyForcibly()
    process.waitFor()
    driverTempDirs(commandLines).filter { it.isDirectory }.forEach { it.deleteTree() }
}

private fun signalGroup(process: Process, signal: String) {
    runCatching {
        ProcessBuilder("kill", "-$signal", "--", "-${process.pid()}")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()
    }
}

/**
 * True for a GraalVM native-image driver, the `native-image` executable that starts the builder JVM.
 *
 * The driver is a native executable that keeps a `driverRoot-*` directory in `/tmp` (hard-coded, `TMPDIR` and
 * `java.io.tmpdir` do not move it) and removes it only when it ends by itself: a signal ends it at once, without
 * running its shutdown hooks. So it must not get a SIGTERM, it ends on its own once its builder is gone.
 */
fun ProcessHandle.isNativeImageDriver(): Boolean = info().command().map { it.endsWith("/native-image") }.orElse(false)

/** A `driverRoot-<digits>` directory as it appears in a builder's command line, e.g. `@/tmp/driverRoot-1/vminvocation.args`. */
private val DRIVER_TEMP_DIR = Regex("""(/[^\s@=]*/driverRoot-\d+)/""")

/**
 * The native-image driver directories named in [commandLines]. The builder JVM gets its arguments from files in
 * that directory, so its command line names it. Only a driver that was killed (after the grace period) leaves it
 * behind, these are what [stopProcessGroup] removes afterwards.
 */
fun driverTempDirs(commandLines: Collection<String>): Set<File> =
    commandLines.flatMap { line -> DRIVER_TEMP_DIR.findAll(line).map { File(it.groupValues[1]) } }.toSet()
