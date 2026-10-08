package bayern.kickner.kbang

import bayern.kickner.kbang.auth.sha256Hex
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val apiKeyPattern = Regex("[A-Za-z0-9_-]{43}")

private class Run(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs KBang's `main` in a separate JVM, the only way to observe exit codes and the stdout/stderr split.
 * `-Xlog:disable` keeps JVM warnings of the host (e.g. about cgroups) out of the output.
 */
private fun kbang(vararg args: String, workingDirectory: File): Run {
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val stdout = File(workingDirectory, "stdout.txt")
    val stderr = File(workingDirectory, "stderr.txt")
    val process = ProcessBuilder(java, "-Xlog:disable", "-cp", System.getProperty("java.class.path"), "bayern.kickner.kbang.MainKt", *args)
        .directory(workingDirectory)
        .redirectOutput(stdout)
        .redirectError(stderr)
        // A JVM would print "Picked up JAVA_TOOL_OPTIONS" to stderr
        .apply { environment().remove("JAVA_TOOL_OPTIONS") }
        .start()
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "KBang did not exit within 60 s")
    return Run(process.exitValue(), stdout.readText(), stderr.readText())
}

class MainTest {

    private val dir = createTempDirectory("kbang-main-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun writeConfig(json: String) = File(dir, "config.json").apply { writeText(json) }

    @Test
    fun `key prints only a fresh api key to stdout and the config entry to stderr`() {
        val run = kbang("key", "name=laptop", workingDirectory = dir)

        assertEquals(0, run.exitCode, run.stderr)
        val key = run.stdout.trim()
        assertTrue(apiKeyPattern.matches(key), "stdout was: ${run.stdout}")
        assertContains(run.stderr, """{ "name": "laptop", "sha256": "${sha256Hex(key)}" }""")
    }

    @Test
    fun `a missing config exits with 78 so systemd does not restart`() {
        val run = kbang(workingDirectory = dir)

        assertEquals(78, run.exitCode, run.stderr)
        assertContains(run.stderr, "Config file not found")
    }

    @Test
    fun `an invalid config exits with 78 and names the problem`() {
        writeConfig("""{"publicUrl": "kbang.example.com", "apiKeys": [{"name": "laptop", "sha256": "$LAPTOP_KEY_SHA256"}]}""")

        val run = kbang(workingDirectory = dir)

        assertEquals(78, run.exitCode, run.stderr)
        assertContains(run.stderr, "publicUrl")
    }

    @Test
    fun `a failed start exits with 1 so systemd restarts`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            val workDir = File(dir, "work").path
            writeConfig(
                """{"listenPort": ${taken.localPort}, "publicUrl": "https://kbang.example.com", "workDir": "$workDir",
                   "apiKeys": [{"name": "laptop", "sha256": "$LAPTOP_KEY_SHA256"}]}"""
            )

            val run = kbang(workingDirectory = dir)

            assertEquals(1, run.exitCode, run.stderr)
            assertContains(run.stderr, "Could not start on 127.0.0.1:${taken.localPort}")
        }
    }

    @Test
    fun `a start that cannot bind leaves the workspaces of a running instance alone`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            val workDir = File(dir, "work")
            val live = File(workDir, "kbang-live").apply { mkdirs() }
            writeConfig(
                """{"listenPort": ${taken.localPort}, "publicUrl": "https://kbang.example.com", "workDir": "${workDir.path}",
                   "apiKeys": [{"name": "laptop", "sha256": "$LAPTOP_KEY_SHA256"}]}"""
            )

            val run = kbang(workingDirectory = dir)

            assertEquals(1, run.exitCode, run.stderr)
            assertTrue(live.isDirectory, "the workspace of the running instance was deleted")
        }
    }

    @Test
    fun `a server that is up removes workspaces left by a crash`() {
        val workDir = File(dir, "work")
        val stale = File(workDir, "kbang-crashed").apply { mkdirs() }
        val server = kbangServer(testConfig().copy(listenPort = 0, workDir = workDir.path)).start(wait = false)
        try {
            assertTrue(stale.exists().not(), "the stale workspace is still there")
        } finally {
            server.stop(100, 1_000)
        }
    }
}
