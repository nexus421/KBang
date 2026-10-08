package bayern.kickner.kbang.build

import bayern.kickner.kbang.awaitPid
import bayern.kickner.kbang.eventually
import bayern.kickner.kbang.fakeJbang
import bayern.kickner.kbang.isRunning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerTest {

    private val dir = createTempDirectory("kbang-process-test").toFile()
    private val fake = fakeJbang(dir)
    private val log = File(dir, "build.log")
    private val command = listOf("setsid", fake.path, "export", "native", "-O", File(dir, "out/tool").path, File(dir, "tool.kt").path)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `exit code passes through and output lands in the log`() = runBlocking {
        val outcome = runProcessGroup(command, mapOf("FAKE_EXIT" to "3", "FAKE_MESSAGE" to "hello log"), dir, log, 10.seconds)

        assertEquals(ProcessOutcome.Exited(3), outcome)
        assertTrue(log.readText().contains("hello log"), log.readText())
    }

    @Test
    fun `stragglers of a normal run are killed`() = runBlocking {
        val childPidFile = File(dir, "child.pid")

        val outcome = runProcessGroup(command, mapOf("FAKE_CHILD_PID_FILE" to childPidFile.path), dir, log, 10.seconds)

        assertEquals(ProcessOutcome.Exited(0), outcome)
        val child = awaitPid(childPidFile)
        assertTrue(eventually(2_000) { isRunning(child).not() }, "background child $child survived")
    }

    @Test
    fun `timeout kills the whole group`() = runBlocking {
        val selfPidFile = File(dir, "self.pid")
        val childPidFile = File(dir, "child.pid")
        val env = mapOf("FAKE_SLEEP" to "30", "FAKE_SELF_PID_FILE" to selfPidFile.path, "FAKE_CHILD_PID_FILE" to childPidFile.path)

        val started = System.currentTimeMillis()
        val outcome = runProcessGroup(command, env, dir, log, 1.seconds)
        val took = System.currentTimeMillis() - started

        assertEquals(ProcessOutcome.TimedOut, outcome)
        assertTrue(took < 3_000, "took $took ms")
        val self = awaitPid(selfPidFile)
        val child = awaitPid(childPidFile)
        assertTrue(eventually(2_000) { isRunning(self).not() && isRunning(child).not() }, "group survived the timeout")
    }

    @Test
    fun `cancellation kills the whole group`() {
        val selfPidFile = File(dir, "self.pid")
        val childPidFile = File(dir, "child.pid")
        val env = mapOf("FAKE_SLEEP" to "30", "FAKE_SELF_PID_FILE" to selfPidFile.path, "FAKE_CHILD_PID_FILE" to childPidFile.path)

        val (self, child) = runBlocking {
            val job = launch(Dispatchers.Default) { runProcessGroup(command, env, dir, log, 60.seconds) }
            val pids = awaitPid(selfPidFile) to awaitPid(childPidFile)
            job.cancelAndJoin()
            pids
        }

        assertTrue(eventually(2_000) { isRunning(self).not() && isRunning(child).not() }, "group survived the cancellation")
    }

    @Test
    fun `cancellation stops the innermost process first so its parent can clean up`() {
        val selfPidFile = File(dir, "self.pid")
        val cleanupFile = File(dir, "cleanup.txt")
        val env = mapOf("FAKE_SLEEP" to "30", "FAKE_SELF_PID_FILE" to selfPidFile.path, "FAKE_CLEANUP_FILE" to cleanupFile.path)

        val self = runBlocking {
            val job = launch(Dispatchers.Default) { runProcessGroup(command, env, dir, log, 60.seconds) }
            val pid = awaitPid(selfPidFile)
            job.cancelAndJoin()
            pid
        }

        // A SIGTERM to the whole group would end the parent before its child, so it never got to clean up
        assertTrue(cleanupFile.isFile, "the parent was killed before it could clean up")
        assertEquals("cleaned up", cleanupFile.readText().trim())
        assertTrue(isRunning(self).not(), "build survived the cancellation")
    }

    @Test
    fun `a build ignoring SIGTERM is killed after the grace period`() {
        val selfPidFile = File(dir, "self.pid")
        val env = mapOf("FAKE_SLEEP" to "30", "FAKE_SELF_PID_FILE" to selfPidFile.path, "FAKE_IGNORE_TERM" to "1")

        val started = System.currentTimeMillis()
        val outcome = runBlocking { runProcessGroup(command, env, dir, log, 1.seconds, killGracePeriod = 1.seconds) }
        val took = System.currentTimeMillis() - started

        assertEquals(ProcessOutcome.TimedOut, outcome)
        assertTrue(took in 1_900..5_000, "took $took ms")
        assertTrue(isRunning(awaitPid(selfPidFile)).not(), "build survived SIGKILL")
    }

    @Test
    fun `a spared driver without a builder yet gets no signal and cleans up after its builder`() {
        val selfPidFile = File(dir, "self.pid")
        val systemTmp = File(dir, "system-tmp").apply { mkdirs() }
        val env = mapOf("FAKE_DRIVER_DIR" to systemTmp.path, "FAKE_DRIVER_DELAY" to "2", "FAKE_SELF_PID_FILE" to selfPidFile.path)
        // Stands in for the real rule, which recognizes native-image by its executable
        val spare: (ProcessHandle) -> Boolean = { handle -> selfPidFile.isFile && handle.pid() == selfPidFile.readText().trim().toLongOrNull() }

        val self = runBlocking {
            val job = launch(Dispatchers.Default) { runProcessGroup(command, env, dir, log, 60.seconds, spare = spare) }
            val pid = awaitPid(selfPidFile)
            assertTrue(eventually { File(systemTmp, "driverRoot-$pid").isDirectory }, "the fake driver never created its directory")
            // Cancelled while the driver has no child yet, the moment a SIGTERM would hit the driver itself
            job.cancelAndJoin()
            pid
        }

        assertEquals(emptyList(), systemTmp.list()!!.toList(), "the driver did not get to clean up")
        assertTrue(isRunning(self).not(), "driver survived the cancellation")
    }

    @Test
    fun `after a SIGKILL the driver directory named by the builder is removed`() {
        val selfPidFile = File(dir, "self.pid")
        val systemTmp = File(dir, "system-tmp").apply { mkdirs() }
        val env = mapOf("FAKE_DRIVER_DIR" to systemTmp.path, "FAKE_IGNORE_TERM" to "1", "FAKE_SELF_PID_FILE" to selfPidFile.path)

        val outcome = runBlocking { runProcessGroup(command, env, dir, log, 1.seconds, killGracePeriod = 1.seconds) }

        assertEquals(ProcessOutcome.TimedOut, outcome)
        assertTrue(isRunning(awaitPid(selfPidFile)).not(), "driver survived SIGKILL")
        assertEquals(emptyList(), systemTmp.list()!!.toList(), "the driver directory was left behind")
    }

    @Test
    fun `a process is recognized as native-image driver by its executable`() {
        // A copy of bash, because a multi-call binary like uutils coreutils refuses to run under another name
        val bash = listOf("/usr/bin/bash", "/bin/bash").map(::File).first { it.canExecute() }
        val nativeImage = bash.copyTo(File(dir, "bin/native-image")).apply { setExecutable(true) }
        // A loop keeps bash itself running instead of replacing it with the last command
        val loop = "while true\ndo\n  sleep 1\ndone"
        val driver = ProcessBuilder(nativeImage.path, "-c", loop).start()
        val other = ProcessBuilder(bash.path, "-c", loop).start()
        try {
            assertTrue(driver.toHandle().isNativeImageDriver())
            assertTrue(other.toHandle().isNativeImageDriver().not())
        } finally {
            driver.destroyForcibly()
            other.destroyForcibly()
        }
    }

    @Test
    fun `driver directories are taken from builder command lines`() {
        val builder = "/opt/graalvm/bin/java @/tmp/driverRoot-123/vminvocation.args --image-args-file=/tmp/driverRoot-123/native-image.args"

        assertEquals(setOf(File("/tmp/driverRoot-123")), driverTempDirs(listOf(builder, "bash /opt/jbang/bin/jbang export native", "/tmp/driverRoot-x/y")))
    }

    @Test
    fun `a program that cannot be started throws IOException`() {
        assertFailsWith<IOException> {
            runBlocking { runProcessGroup(listOf(File(dir, "missing").path), emptyMap(), dir, log, 5.seconds) }
        }
    }
}
