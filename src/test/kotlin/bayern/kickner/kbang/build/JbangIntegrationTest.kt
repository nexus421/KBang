package bayern.kickner.kbang.build

import bayern.kickner.kbang.eventually
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Real builds with JBang and GraalVM. Slow (a native build takes minutes), so they only run with `KBANG_IT=1`.
 *
 * Needs on the machine: JBang (`KBANG_IT_JBANG`, default `jbang` on the `PATH`), a GraalVM as `JAVA_HOME` with
 * `native-image`, and the musl toolchain on the `PATH` for the static default options. `KBANG_IT_OFFLINE=1` builds
 * with `--offline`, then `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0` must be in the local Maven
 * repository. Without `KBANG_IT=1` every test is skipped.
 */
class JbangIntegrationTest {

    private val enabled = System.getenv("KBANG_IT") == "1"

    /** Names native-image and JBang give to what they leave in the system temp dir when nobody redirects them. */
    private val tempLeftoverPattern = Regex("^(jbang\\d+native-image|SVM-\\d+|driverRoot-\\d+)$")

    private fun systemTempLeftovers(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).list()?.filter { tempLeftoverPattern.matches(it) }?.toSet().orEmpty()

    @BeforeTest
    fun onlyWithKbangIt() {
        assumeTrue(enabled, "real JBang and GraalVM builds only run with KBANG_IT=1")
    }
    private val dir = createTempDirectory("kbang-it").toFile()
    private val workDir = File(dir, "work")
    private val results = File(dir, "results").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val builder = JbangBuilder(
        BuildSettings(
            jbang = System.getenv("KBANG_IT_JBANG") ?: "jbang",
            environment = emptyMap(),
            offline = System.getenv("KBANG_IT_OFFLINE") == "1",
            nativeOptions = listOf("-Ob", "--static", "--libc=musl"),
            timeout = 20.minutes,
            maxLogBytes = 64 * 1024,
            workDir = workDir
        )
    )

    /** Builds [source] and copies a successful artifact out of the workspace before it is deleted. */
    private fun build(target: BuildTarget, name: String, source: String): Pair<BuildResult, File?> {
        var result: BuildResult? = null
        var kept: File? = null
        runBlocking {
            builder.build(BuildSpec(target, name), source.toByteArray()) { delivered ->
                result = delivered
                if (delivered is BuildResult.Success) kept = delivered.artifact.copyTo(File(results, delivered.artifact.name))
            }
        }
        return result!! to kept
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true)
            // A JVM would print "Picked up JAVA_TOOL_OPTIONS" into the output that is compared
            .apply { environment().remove("JAVA_TOOL_OPTIONS") }
            .start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${command.first()} did not finish" }
        return process.inputStream.readBytes().decodeToString()
    }

    private fun java(): String = System.getenv("JAVA_HOME")?.let { "$it/bin/java" } ?: "java"

    private fun leftoverWorkspaces() = workDir.listFiles()?.filter { it.name.startsWith("kbang-") }.orEmpty()

    @Test
    fun `native hello world is a static binary that runs and leaves nothing in the system temp dir`() {
        val tempBefore = systemTempLeftovers()

        val (result, binary) = build(BuildTarget.NATIVE, "hello", "fun main(args: Array<String>) = println(\"Hello \" + args.first())\n")

        assertIs<BuildResult.Success>(result, (result as? BuildResult.Failed)?.log)
        binary!!.setExecutable(true)
        assertEquals("Hello native\n", run(binary.path, "native"))
        assertTrue(run("file", binary.path).contains("statically linked"))
        assertEquals(emptyList(), leftoverWorkspaces())
        assertEquals(emptySet(), systemTempLeftovers() - tempBefore)
    }

    @Test
    fun `jar with a package declaration and a dependency runs with java`() {
        val source = """
            |//DEPS org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0
            |package demo.tools
            |
            |import kotlinx.coroutines.runBlocking
            |
            |fun main() = runBlocking { println("Hello jar") }
            |""".trimMargin()

        val (result, jar) = build(BuildTarget.JAR, "tool", source)

        assertIs<BuildResult.Success>(result, (result as? BuildResult.Failed)?.log)
        assertEquals("Hello jar\n", run(java(), "-jar", jar!!.path))
    }

    @Test
    fun `compile error is a failure naming the source file`() {

        val (result, _) = build(BuildTarget.JAR, "broken", "fun main() = printn(\"x\")\n")

        val failed = assertIs<BuildResult.Failed>(result)
        assertTrue(failed.log.contains("broken.kt") && failed.log.contains("printn"), failed.log)
    }

    @Test
    fun `the shared JBang jar cache gains no entry`() {
        val shared = File(System.getenv("JBANG_CACHE_DIR") ?: (System.getProperty("user.home") + "/.jbang/cache"), "jars")
        val before = shared.list()?.toSet().orEmpty()

        val (result, _) = build(BuildTarget.JAR, "cachecheck", "fun main() = println(\"${System.nanoTime()}\")\n")

        assertIs<BuildResult.Success>(result, (result as? BuildResult.Failed)?.log)
        assertEquals(before, shared.list()?.toSet().orEmpty())
    }

    @Test
    fun `cancelling a real native build leaves no process and no temporary file behind`() {
        val tempBefore = systemTempLeftovers()
        fun buildProcesses() = ProcessHandle.allProcesses()
            .filter { it.info().commandLine().orElse("").contains(workDir.path) }
            .toList()

        runBlocking {
            val job = launch(Dispatchers.Default) {
                builder.build(BuildSpec(BuildTarget.NATIVE, "cancelme"), "fun main() = println(1)\n".toByteArray()) { }
            }
            val nativeImageRunning = eventually(180_000) {
                buildProcesses().any { it.info().commandLine().orElse("").contains("native-image") || it.info().command().orElse("").contains("native-image") }
            }
            assertTrue(nativeImageRunning, "native-image never started")
            job.cancelAndJoin()
        }

        assertTrue(eventually(10_000) { buildProcesses().isEmpty() }, "left behind: ${buildProcesses().map { it.info().commandLine().orElse("?") }}")
        assertEquals(emptyList(), leftoverWorkspaces())
        assertEquals(emptySet(), systemTempLeftovers() - tempBefore)
    }
}
