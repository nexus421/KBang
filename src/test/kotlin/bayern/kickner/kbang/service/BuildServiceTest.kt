package bayern.kickner.kbang.service

import bayern.kickner.kbang.LAPTOP_KEY
import bayern.kickner.kbang.auth.ApiKeys
import bayern.kickner.kbang.build.BuildQueue
import bayern.kickner.kbang.build.BuildResult
import bayern.kickner.kbang.build.BuildSpec
import bayern.kickner.kbang.build.BuildTarget
import bayern.kickner.kbang.build.Builder
import bayern.kickner.kbang.testApiKeys
import bayern.kickner.klogger.KLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuildServiceTest {

    private companion object {
        /** Every message logged through Klogger while these tests run. */
        val logged = CopyOnWriteArrayList<String>()

        init {
            KLogger.configure { logToCustom { _, _, message -> logged += message } }
        }
    }

    private val dir = createTempDirectory("kbang-service-test").toFile()
    private val artifact = File(dir, "artifact").apply { writeText("BINARY") }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A call as the route would build it, counting how often the body is read. */
    private class FakeCall(
        override val apiKey: String? = LAPTOP_KEY,
        override val target: String? = "native",
        override val name: String? = "tool",
        override val arch: String? = "x86_64",
        override val clientAddress: String = "203.0.113.7",
        private val source: () -> ByteArray = { "fun main() {}".toByteArray() }
    ) : BuildCall {
        var reads = 0
        override suspend fun receiveSource(): ByteArray {
            reads++
            return source()
        }
    }

    /** Delivers [result] for every build and records the specs it was asked for. */
    private class FakeBuilder(private val result: BuildResult) : Builder {
        val specs = mutableListOf<BuildSpec>()
        override suspend fun build(spec: BuildSpec, source: ByteArray, deliver: suspend (BuildResult) -> Unit) {
            specs += spec
            deliver(result)
        }
    }

    private fun service(builder: Builder, queue: BuildQueue = BuildQueue(1, 4)) =
        BuildService(ApiKeys(testApiKeys()), builder, queue, hostArch = "x86_64")

    private fun handle(service: BuildService, call: BuildCall): List<BuildReply> {
        val replies = mutableListOf<BuildReply>()
        runBlocking { service.handle(call) { replies += it } }
        return replies
    }

    private fun text(replies: List<BuildReply>) = assertIs<BuildReply.Text>(replies.single())

    @Test
    fun `a connection closed right after the reply is not logged as a cancelled build`() {
        val call = FakeCall(name = "afterreply")

        // CIO cancels the call when the client closes the connection, which curl does as soon as it has every byte
        runCatching {
            runBlocking { service(FakeBuilder(BuildResult.Success(artifact))).handle(call) { throw CancellationException("client closed") } }
        }

        assertTrue(logged.none { it.contains("'afterreply'") && it.contains("cancelled") }, logged.toString())
    }

    @Test
    fun `a rejected key is logged with the client address but never the key`() {
        val call = FakeCall(apiKey = "wrong-key-0123456789")

        handle(service(FakeBuilder(BuildResult.Success(artifact))), call)

        val rejection = logged.lastOrNull { it.contains("Rejected build request") }
        assertTrue(rejection != null && rejection.contains("203.0.113.7"), logged.toString())
        assertTrue(logged.none { it.contains("wrong-key-0123456789") }, logged.toString())
    }

    @Test
    fun `wrong or missing key is 401 without reading the body`() {
        val builder = FakeBuilder(BuildResult.Success(artifact))
        listOf(null, "", "wrong-key-0123456789").forEach { key ->
            val call = FakeCall(apiKey = key)

            val reply = text(handle(service(builder), call))

            assertEquals(401, reply.status, "key '$key'")
            assertEquals(0, call.reads)
        }
        assertEquals(emptyList(), builder.specs)
    }

    @Test
    fun `rejected request is answered without reading the body`() {
        val call = FakeCall(target = "exe")

        val reply = text(handle(service(FakeBuilder(BuildResult.Success(artifact))), call))

        assertEquals(404, reply.status)
        assertEquals(0, call.reads)
    }

    @Test
    fun `foreign arch is 400`() {
        assertEquals(400, text(handle(service(FakeBuilder(BuildResult.Success(artifact))), FakeCall(arch = "arm64"))).status)
    }

    @Test
    fun `empty or blank body is 400`() {
        listOf("", "  \n").forEach { body ->
            val builder = FakeBuilder(BuildResult.Success(artifact))

            val reply = text(handle(service(builder), FakeCall(source = { body.toByteArray() })))

            assertEquals(400, reply.status)
            assertEquals(emptyList(), builder.specs)
        }
    }

    @Test
    fun `success replies the artifact under its client-facing name`() {
        val builder = FakeBuilder(BuildResult.Success(artifact))

        val native = assertIs<BuildReply.Artifact>(handle(service(builder), FakeCall()).single())
        val jar = assertIs<BuildReply.Artifact>(handle(service(builder), FakeCall(target = "jar")).single())

        assertEquals(BuildReply.Artifact(artifact, "tool"), native)
        assertEquals(BuildReply.Artifact(artifact, "tool.jar"), jar)
        assertEquals(listOf(BuildSpec(BuildTarget.NATIVE, "tool"), BuildSpec(BuildTarget.JAR, "tool")), builder.specs)
    }

    @Test
    fun `failed and timed out builds are 422 with the log`() {
        val failed = text(handle(service(FakeBuilder(BuildResult.Failed("tool.kt:1:1 error: x"))), FakeCall()))
        val timedOut = text(handle(service(FakeBuilder(BuildResult.TimedOut("Build aborted after 10m"))), FakeCall()))

        assertEquals(BuildReply.Text(422, "tool.kt:1:1 error: x"), failed)
        assertEquals(BuildReply.Text(422, "Build aborted after 10m"), timedOut)
    }

    @Test
    fun `start failure is 500 without internal details`() {
        val reply = text(handle(service(FakeBuilder(BuildResult.StartFailed("JBang executable '/opt/secret/jbang' not found"))), FakeCall()))

        assertEquals(500, reply.status)
        assertTrue(reply.message.contains("/opt/secret").not(), reply.message)
    }

    @Test
    fun `full queue is 503`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocking = Builder { _, _, deliver ->
            started.complete(Unit)
            release.await()
            deliver(BuildResult.Success(artifact))
        }
        val service = service(blocking, BuildQueue(1, 0))
        val first = launch(Dispatchers.Default) { service.handle(FakeCall()) { } }
        started.await()

        val replies = mutableListOf<BuildReply>()
        service.handle(FakeCall()) { replies += it }

        assertEquals(503, assertIs<BuildReply.Text>(replies.single()).status)
        release.complete(Unit)
        first.join()
    }

    @Test
    fun `a failing body read propagates and nothing is built`() {
        val builder = FakeBuilder(BuildResult.Success(artifact))
        val call = FakeCall(source = { throw IOException("body too large") })

        assertFailsWith<IOException> { handle(service(builder), call) }
        assertEquals(emptyList(), builder.specs)
    }
}
