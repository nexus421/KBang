package bayern.kickner.kbang.build

import bayern.kickner.kbang.awaitPid
import bayern.kickner.kbang.eventually
import bayern.kickner.kbang.fakeJbang
import bayern.kickner.kbang.isRunning
import bayern.kickner.kbang.testConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BuildRunnerTest {

    private val dir = createTempDirectory("kbang-runner-test").toFile()
    private val workDir = File(dir, "work")
    private val fake = fakeJbang(dir)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun builder(fakeEnv: Map<String, String> = emptyMap(), timeout: Duration = 30.seconds, maxLogBytes: Int = 64 * 1024, jbang: String = fake.path) =
        JbangBuilder(BuildSettings(jbang, fakeEnv, offline = true, nativeOptions = listOf("-Ob"), timeout = timeout, maxLogBytes = maxLogBytes, workDir = workDir))

    /** Runs a build and returns what was delivered plus whether the artifact file existed during delivery. */
    private fun build(builder: JbangBuilder, spec: BuildSpec = BuildSpec(BuildTarget.NATIVE, "tool")): Pair<BuildResult, String?> {
        var delivered: BuildResult? = null
        var artifactContent: String? = null
        runBlocking {
            builder.build(spec, "fun main() = println(1)".toByteArray()) { result ->
                delivered = result
                if (result is BuildResult.Success) artifactContent = result.artifact.readText()
            }
        }
        return delivered!! to artifactContent
    }

    private fun workspaces() = workDir.listFiles()?.filter { it.name.startsWith("kbang-") }.orEmpty()

    @Test
    fun `success delivers the artifact built from the named source and removes the workspace`() {
        val (result, content) = build(builder())

        val success = assertIs<BuildResult.Success>(result)
        assertEquals("tool", success.artifact.name)
        assertEquals("artifact from tool.kt", content)
        assertEquals(emptyList(), workspaces())
    }

    @Test
    fun `jar artifact gets the jar name`() {
        val (result, _) = build(builder(), BuildSpec(BuildTarget.JAR, "tool"))

        assertEquals("tool.jar", assertIs<BuildResult.Success>(result).artifact.name)
    }

    @Test
    fun `non-zero exit is a failure carrying the log`() {
        val (result, _) = build(builder(mapOf("FAKE_EXIT" to "1", "FAKE_MESSAGE" to "tool.kt:1:5 error: unresolved reference")))

        val failed = assertIs<BuildResult.Failed>(result)
        assertTrue(failed.log.contains("tool.kt:1:5 error: unresolved reference"), failed.log)
        assertEquals(emptyList(), workspaces())
    }

    @Test
    fun `exit 0 without artifact is a failure`() {
        val (result, _) = build(builder(mapOf("FAKE_WRITE_ARTIFACT" to "0")))

        val failed = assertIs<BuildResult.Failed>(result)
        assertTrue(failed.log.contains("no artifact"), failed.log)
    }

    @Test
    fun `timeout is reported with the log and the limit`() {
        val (result, _) = build(builder(mapOf("FAKE_SLEEP" to "30", "FAKE_MESSAGE" to "still compiling"), timeout = 1.seconds))

        val timedOut = assertIs<BuildResult.TimedOut>(result)
        assertTrue(timedOut.log.contains("still compiling"), timedOut.log)
        assertTrue(timedOut.log.trimEnd().endsWith("Build aborted after 1s (buildTimeoutMinutes)."), timedOut.log)
        assertEquals(emptyList(), workspaces())
    }

    @Test
    fun `cancellation kills the build and removes the workspace`() {
        val childPidFile = File(dir, "child.pid")
        val builder = builder(mapOf("FAKE_SLEEP" to "30", "FAKE_CHILD_PID_FILE" to childPidFile.path))
        var delivered = false

        val child = runBlocking {
            val job = launch(Dispatchers.Default) {
                builder.build(BuildSpec(BuildTarget.NATIVE, "tool"), ByteArray(1)) { delivered = true }
            }
            val pid = awaitPid(childPidFile)
            job.cancelAndJoin()
            pid
        }

        assertTrue(eventually(2_000) { isRunning(child).not() }, "build child $child survived the cancellation")
        assertEquals(false, delivered)
        assertEquals(emptyList(), workspaces())
    }

    @Test
    fun `jbang gets a jar cache inside the workspace`() {
        val (result, _) = build(builder(mapOf("FAKE_EXIT" to "1")))

        val log = assertIs<BuildResult.Failed>(result).log
        val cache = Regex("cache=(\\S+)").find(log)?.groupValues?.get(1)
        assertTrue(cache != null && cache.startsWith(workDir.path + "/kbang-") && cache.endsWith("/jbang-jars"), log)
    }

    @Test
    fun `jbang and native-image get a temp dir inside the workspace`() {
        val (result, _) = build(builder(mapOf("FAKE_EXIT" to "1")))

        val log = assertIs<BuildResult.Failed>(result).log
        val tmp = Regex("tmp=(\\S+)").find(log)?.groupValues?.get(1)
        assertTrue(tmp != null && tmp.startsWith(workDir.path + "/kbang-") && tmp.endsWith("/tmp"), log)
        assertTrue(log.contains("jbangopts=-Djava.io.tmpdir=$tmp"), log)
        assertTrue(log.contains("-N=-J-Djava.io.tmpdir=$tmp"), log)
    }

    @Test
    fun `temporary files of a build disappear with the workspace`() {
        val (result, _) = build(builder(mapOf("FAKE_TMP_FILE" to "SVM-123")))

        assertIs<BuildResult.Success>(result)
        assertEquals(emptyList(), workspaces())
    }

    @Test
    fun `a failed native build carries the native-image log after the JBang output`() {
        val (result, _) = build(builder(mapOf("FAKE_EXIT" to "1", "FAKE_MESSAGE" to "[jbang] [ERROR] Error during native-image", "FAKE_NATIVE_LOG" to "Error: Detected a started Thread in the image heap")))

        val log = assertIs<BuildResult.Failed>(result).log
        val jbangLine = log.indexOf("[jbang] [ERROR] Error during native-image")
        val nativeLine = log.indexOf("Error: Detected a started Thread in the image heap")
        assertTrue(jbangLine >= 0 && nativeLine > jbangLine, log)
    }

    @Test
    fun `a huge log comes back as its tail`() {
        val (result, _) = build(builder(mapOf("FAKE_EXIT" to "1", "FAKE_PRINT_MB" to "3"), maxLogBytes = 4096))

        val log = assertIs<BuildResult.Failed>(result).log
        assertTrue(log.length < 4096 + 200, "log has ${log.length} characters")
        assertTrue(log.startsWith("[log truncated"), log.take(80))
        assertTrue(log.trimEnd().endsWith("LOG-END"), log.takeLast(80))
    }

    @Test
    fun `missing jbang is a start failure`() {
        val (result, _) = build(builder(jbang = File(dir, "no-such-jbang").path))

        val failed = assertIs<BuildResult.StartFailed>(result)
        assertTrue(failed.reason.contains("no-such-jbang"), failed.reason)
        assertEquals(emptyList(), workspaces())
    }

    @Test
    fun `a workDir that cannot hold workspaces is a start failure`() {
        val notADirectory = File(dir, "file").apply { writeText("x") }
        val builder = JbangBuilder(BuildSettings(fake.path, emptyMap(), true, listOf("-Ob"), 30.seconds, 64 * 1024, File(notADirectory, "work")))

        val (result, _) = build(builder)

        assertIs<BuildResult.StartFailed>(result)
    }

    @Test
    fun `workspace is removed also when delivering throws`() {
        assertFailsWith<IllegalStateException> {
            runBlocking { builder().build(BuildSpec(BuildTarget.NATIVE, "tool"), ByteArray(1)) { error("client gone") } }
        }
        assertEquals(emptyList(), workspaces())
    }

    @Test
    fun `settings come from the config`() {
        val config = testConfig().copy(jbang = "/opt/jbang/bin/jbang", offline = true, buildTimeoutMinutes = 7, maxLogKb = 3, workDir = "/srv/kbang")

        val settings = config.toBuildSettings()

        assertEquals(BuildSettings("/opt/jbang/bin/jbang", emptyMap(), true, listOf("-Ob", "--static", "--libc=musl"), 7.minutes, 3 * 1024, File("/srv/kbang")), settings)
    }

    @Test
    fun `stale workspaces are removed and nothing else`() {
        workDir.mkdirs()
        File(workDir, "kbang-123/out").mkdirs()
        File(workDir, "kbang-123/out/tool").writeText("x")
        File(workDir, "kbang-456").mkdirs()
        File(workDir, "keep-me").mkdirs()
        File(workDir, "kbang-not-a-dir").writeText("x")

        assertEquals(2, cleanStaleWorkspaces(workDir))
        assertEquals(listOf("kbang-not-a-dir", "keep-me"), workDir.list()!!.sorted())
        assertEquals(0, cleanStaleWorkspaces(File(dir, "missing")))
    }

    @Test
    fun `stale workspaces are removed without following symlinks`() {
        val outside = File(dir, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("x")
        File(workDir, "kbang-123").mkdirs()
        Files.createSymbolicLink(File(workDir, "kbang-123/link").toPath(), outside.toPath())

        assertEquals(1, cleanStaleWorkspaces(workDir))
        assertEquals(listOf("keep.txt"), outside.list()!!.toList())
    }

    @Test
    fun `a symlink a build leaves in its workspace is removed without touching its target`() {
        val outside = File(dir, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("x")
        val jbang = File(dir, "linking-jbang").apply {
            writeText("#!/usr/bin/env bash\nln -s '${outside.path}' link\nexit 1\n")
            setExecutable(true)
        }

        val (result, _) = build(builder(jbang = jbang.path))

        assertIs<BuildResult.Failed>(result)
        assertEquals(emptyList(), workspaces())
        assertEquals(listOf("keep.txt"), outside.list()!!.toList())
    }
}
