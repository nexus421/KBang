package bayern.kickner.kbang.service

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the real client script with real `bash` and `curl` against a stub server that speaks KBang's HTTP contract.
 */
class ClientScriptTest {

    /** One request the stub received. */
    private data class Recorded(val method: String, val path: String, val query: String, val apiKey: String?, val body: String)

    /** Exit code and the two output streams of a client run. */
    private data class Run(val exit: Int, val stdout: String, val stderr: String)

    private val dir = createTempDirectory("kbang-client-test").toFile()
    private val requests = CopyOnWriteArrayList<Recorded>()

    @Volatile private var buildStatus = 200
    @Volatile private var buildBody = "BINARY-CONTENT"

    private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            get("/kbang.sh") { call.respondText(clientScript(baseUrl)) }
            post("/build/{target}") {
                requests += Recorded(
                    call.request.httpMethod.value, call.request.path(), call.request.queryString(),
                    call.request.header("X-API-Key"), call.receiveText()
                )
                call.respondText(buildBody, status = HttpStatusCode.fromValue(buildStatus))
            }
        }
    }.start(wait = false)

    private val baseUrl: String = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"

    private val uname: String = ProcessBuilder("uname", "-m").start().inputStream.readBytes().decodeToString().trim()

    @AfterTest
    fun cleanUp() {
        server.stop(100, 1_000)
        dir.deleteRecursively()
    }

    /** Runs `curl <stub>/kbang.sh | bash -s -- <args>` in [dir], the way users run the one-liner. */
    private fun oneLiner(vararg args: String, key: String? = "test-key"): Run =
        runShell("curl -fsSL '$baseUrl/kbang.sh' | bash -s -- ${args.joinToString(" ")}", key)

    private fun runShell(script: String, key: String?, extraEnv: Map<String, String> = emptyMap()): Run {
        val stdout = File(dir, ".stdout")
        val stderr = File(dir, ".stderr")
        val process = ProcessBuilder("bash", "-c", script)
            .directory(dir)
            .redirectOutput(stdout)
            .redirectError(stderr)
            .apply {
                val env = environment()
                env.remove("KBANG_KEY")
                env.remove("KBANG_URL")
                // The stub is local, no proxy may get in between
                env["NO_PROXY"] = "*"
                env["no_proxy"] = "*"
                if (key != null) env["KBANG_KEY"] = key
                env.putAll(extraEnv)
            }
            .start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "client did not finish" }
        return Run(process.exitValue(), stdout.readText(), stderr.readText())
    }

    private fun source(name: String = "tool.kt") = File(dir, name).apply { writeText("fun main() = println(\"hi\")\n") }

    @Test
    fun `the served script has the server url baked in`() {
        val script = clientScript("https://kbang.example.com")

        assertTrue(script.contains("https://kbang.example.com"))
        assertTrue(script.contains("__KBANG_URL__").not())
        assertTrue(script.startsWith("#!/usr/bin/env bash"))
    }

    @Test
    fun `native build writes an executable named after the source`() {
        source()

        val run = oneLiner("native", "tool.kt")

        assertEquals(0, run.exit, run.stderr)
        val binary = File(dir, "tool")
        assertEquals("BINARY-CONTENT", binary.readText())
        assertTrue(binary.canExecute())
        assertFalse(File(dir, "tool.part").exists())
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/build/native", request.path)
        assertEquals("arch=$uname&name=tool", request.query)
        assertEquals("test-key", request.apiKey)
        assertEquals("fun main() = println(\"hi\")\n", request.body)
    }

    @Test
    fun `KBANG_ARCH overrides the architecture sent`() {
        source()

        val run = runShell("curl -fsSL '$baseUrl/kbang.sh' | bash -s -- native tool.kt", key = "test-key", extraEnv = mapOf("KBANG_ARCH" to "aarch64"))

        assertEquals(0, run.exit, run.stderr)
        assertEquals("arch=aarch64&name=tool", requests.single().query)
    }

    @Test
    fun `the key never shows up in the command line of curl`() {
        source()
        val realCurl = ProcessBuilder("bash", "-c", "command -v curl").start().inputStream.readBytes().decodeToString().trim()
        // A curl in front of the real one that records its arguments, where ps would show them to every user
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "curl").apply {
            writeText("#!/usr/bin/env bash\nprintf '%s\\n' \"${'$'}*\" >> '${dir.path}/curl-args.txt'\nexec '$realCurl' \"${'$'}@\"\n")
            setExecutable(true)
        }

        val run = runShell("curl -fsSL '$baseUrl/kbang.sh' | bash -s -- native tool.kt", key = "secret-key-123", extraEnv = mapOf("PATH" to "${bin.path}:${System.getenv("PATH")}"))

        assertEquals(0, run.exit, run.stderr)
        assertEquals("secret-key-123", requests.single().apiKey)
        val args = File(dir, "curl-args.txt").readText()
        assertTrue(args.contains("/build/native"), args)
        assertTrue(args.contains("secret-key-123").not(), args)
    }

    @Test
    fun `jar build writes a jar that is not executable`() {
        source()

        val run = oneLiner("jar", "tool.kt")

        assertEquals(0, run.exit, run.stderr)
        assertEquals("BINARY-CONTENT", File(dir, "tool.jar").readText())
        assertFalse(File(dir, "tool.jar").canExecute())
        assertEquals("/build/jar", requests.single().path)
    }

    @Test
    fun `third argument names the output`() {
        source()

        val run = oneLiner("native", "tool.kt", "bin-out")

        assertEquals(0, run.exit, run.stderr)
        assertEquals("BINARY-CONTENT", File(dir, "bin-out").readText())
    }

    @Test
    fun `failed build exits 1 with the server text and leaves no file`() {
        source()
        buildStatus = 422
        buildBody = "tool.kt:1:5 error: unresolved reference 'printn'"

        val run = oneLiner("native", "tool.kt")

        assertEquals(1, run.exit)
        assertTrue(run.stderr.contains("unresolved reference 'printn'"), run.stderr)
        assertTrue(run.stderr.contains("422"), run.stderr)
        assertFalse(File(dir, "tool").exists())
        assertFalse(File(dir, "tool.part").exists())
    }

    @Test
    fun `missing key exits 2 without a request`() {
        source()

        val run = oneLiner("native", "tool.kt", key = null)

        assertEquals(2, run.exit)
        assertTrue(run.stderr.contains("KBANG_KEY"), run.stderr)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `wrong usage exits 2 without a request`() {
        source()

        assertEquals(2, oneLiner("exe", "tool.kt").exit)
        assertEquals(2, oneLiner("native").exit)
        assertEquals(2, oneLiner("native", "missing.kt").exit)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `file name that is no valid build name exits 2 without a request`() {
        source("1tool.kt")

        val run = oneLiner("native", "1tool.kt")

        assertEquals(2, run.exit)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `KBANG_URL overrides the baked-in url`() {
        source()
        File(dir, "kbang.sh").writeText(clientScript("http://127.0.0.1:1"))

        val run = runShell("bash kbang.sh native tool.kt", key = "test-key", extraEnv = mapOf("KBANG_URL" to "$baseUrl/"))

        assertEquals(0, run.exit, run.stderr)
        assertEquals("/build/native", requests.single().path)
    }

    @Test
    fun `unreachable server exits 1 and leaves no file`() {
        source()
        File(dir, "kbang.sh").writeText(clientScript("http://127.0.0.1:1"))

        val run = runShell("bash kbang.sh native tool.kt", key = "test-key")

        assertEquals(1, run.exit, run.stderr)
        assertFalse(File(dir, "tool").exists())
        assertFalse(File(dir, "tool.part").exists())
    }

    @Test
    fun `an output that would overwrite the source exits 2 without a request`() {
        source("tool")

        val run = oneLiner("native", "tool")

        assertEquals(2, run.exit)
        assertEquals("fun main() = println(\"hi\")\n", File(dir, "tool").readText())
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `a directory as output exits 2 without a request`() {
        source()
        File(dir, "out").mkdirs()

        val run = oneLiner("native", "tool.kt", "out")

        assertEquals(2, run.exit)
        assertEquals(emptyList(), requests)
    }
}
