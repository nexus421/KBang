package bayern.kickner.kbang.routes

import bayern.kickner.kbang.LAPTOP_KEY
import bayern.kickner.kbang.auth.ApiKeys
import bayern.kickner.kbang.build.BuildQueue
import bayern.kickner.kbang.build.BuildResult
import bayern.kickner.kbang.build.BuildSpec
import bayern.kickner.kbang.build.Builder
import bayern.kickner.kbang.kbang
import bayern.kickner.kbang.service.BuildService
import bayern.kickner.kbang.service.clientScript
import bayern.kickner.kbang.testApiKeys
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.Socket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RoutesTest {

    private val dir = createTempDirectory("kbang-routes-test").toFile()
    private val artifact = File(dir, "artifact").apply { writeText("BINARY") }
    private val script = clientScript("https://kbang.example.com")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Records every source it gets and delivers whatever [result] says. */
    private class RecordingBuilder(private val result: (BuildSpec) -> BuildResult) : Builder {
        val sources = CopyOnWriteArrayList<String>()
        override suspend fun build(spec: BuildSpec, source: ByteArray, deliver: suspend (BuildResult) -> Unit) {
            sources += source.decodeToString()
            deliver(result(spec))
        }
    }

    private fun service(builder: Builder) = BuildService(ApiKeys(testApiKeys()), builder, BuildQueue(1, 4), hostArch = "x86_64")

    private fun ApplicationTestBuilder.kbangApp(builder: Builder, maxUploadBytes: Long = 1024) {
        application { kbang(service(builder), script, maxUploadBytes) }
    }

    private suspend fun ApplicationTestBuilder.build(path: String, body: String = "fun main() {}", key: String? = LAPTOP_KEY): HttpResponse =
        client.post(path) {
            if (key != null) header("X-API-Key", key)
            setBody(body)
        }

    private fun assertAttachment(fileName: String, response: HttpResponse) {
        val disposition = ContentDisposition.parse(response.headers[HttpHeaders.ContentDisposition].orEmpty())
        assertEquals("attachment", disposition.disposition)
        assertEquals(fileName, disposition.parameter(ContentDisposition.Parameters.FileName))
    }

    @Test
    fun `health answers without a key`() = testApplication {
        kbangApp(RecordingBuilder { BuildResult.Success(artifact) })

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `client script is served without a key and carries the public url`() = testApplication {
        kbangApp(RecordingBuilder { BuildResult.Success(artifact) })

        val response = client.get("/kbang.sh")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(script, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("https://kbang.example.com"))
    }

    @Test
    fun `build without a key is 401 and builds nothing`() = testApplication {
        val builder = RecordingBuilder { BuildResult.Success(artifact) }
        kbangApp(builder)

        val response = build("/build/native", key = null)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(emptyList(), builder.sources)
    }

    @Test
    fun `unknown target is 404`() = testApplication {
        kbangApp(RecordingBuilder { BuildResult.Success(artifact) })

        assertEquals(HttpStatusCode.NotFound, build("/build/exe").status)
    }

    @Test
    fun `native build returns the artifact as attachment`() = testApplication {
        val builder = RecordingBuilder { BuildResult.Success(artifact) }
        kbangApp(builder)

        val response = build("/build/native?arch=x86_64&name=tool")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("BINARY", response.bodyAsText())
        assertAttachment("tool", response)
        assertEquals(ContentType.Application.OctetStream, response.contentType())
        assertEquals(listOf("fun main() {}"), builder.sources)
    }

    @Test
    fun `jar build names the attachment with jar`() = testApplication {
        // Like JbangBuilder, which writes the JAR as out/<name>.jar, so the content type follows the extension
        val jar = File(dir, "tool.jar").apply { writeText("JAR") }
        kbangApp(RecordingBuilder { BuildResult.Success(jar) })

        val response = build("/build/jar?name=tool")

        assertEquals(HttpStatusCode.OK, response.status)
        assertAttachment("tool.jar", response)
        assertEquals(ContentType.parse("application/java-archive"), response.contentType())
    }

    @Test
    fun `failed build is 422 with the log`() = testApplication {
        kbangApp(RecordingBuilder { BuildResult.Failed("tool.kt:1:5 error: unresolved reference") })

        val response = build("/build/native?name=tool")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("tool.kt:1:5 error: unresolved reference", response.bodyAsText())
    }

    @Test
    fun `foreign arch is 400`() = testApplication {
        kbangApp(RecordingBuilder { BuildResult.Success(artifact) })

        assertEquals(HttpStatusCode.BadRequest, build("/build/native?arch=arm64").status)
    }

    @Test
    fun `body above the limit is 413 and builds nothing`() = testApplication {
        val builder = RecordingBuilder { BuildResult.Success(artifact) }
        kbangApp(builder, maxUploadBytes = 1024)

        val response = build("/build/native", body = "x".repeat(2048))

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(emptyList(), builder.sources)
    }

    @Test
    fun `chunked body above the limit is 413 and builds nothing`() = testApplication {
        val builder = RecordingBuilder { BuildResult.Success(artifact) }
        kbangApp(builder, maxUploadBytes = 1024)

        val response = client.post("/build/native") {
            header("X-API-Key", LAPTOP_KEY)
            // No content length, so the body goes out chunked and only the stream itself can be limited
            setBody(object : OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    repeat(4) { channel.writeFully(ByteArray(1024) { 'x'.code.toByte() }) }
                }
            })
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(emptyList(), builder.sources)
    }

    @Test
    fun `a large artifact arrives complete although it is deleted right after the reply on a real CIO server`() {
        val content = ByteArray(24 * 1024 * 1024).also { Random(42).nextBytes(it) }
        // Like JbangBuilder: the artifact exists while deliver runs and is deleted as soon as it returns
        val builder = Builder { spec, _, deliver ->
            val file = File(dir, spec.artifactName).apply { writeBytes(content) }
            try {
                deliver(BuildResult.Success(file))
            } finally {
                file.delete()
            }
        }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { kbang(service(builder), script, 1024) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val (status, body) = HttpClient(ClientCIO).use { client ->
                runBlocking {
                    val response = client.post("http://127.0.0.1:$port/build/native?name=big") {
                        header("X-API-Key", LAPTOP_KEY)
                        setBody("fun main() {}")
                    }
                    response.status to response.bodyAsBytes()
                }
            }

            assertEquals(HttpStatusCode.OK, status)
            assertTrue(content.contentEquals(body), "received ${body.size} of ${content.size} bytes, or different ones")
            assertEquals(false, File(dir, "big").exists())
        } finally {
            server.stop(100, 1_000)
        }
    }

    @Test
    fun `client disconnect cancels the running build on a real CIO server`() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val builder = Builder { _, _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { kbang(service(builder), script, 1024) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val body = "fun main() {}"
            Socket("127.0.0.1", port).use { socket ->
                val request = "POST /build/native?name=tool HTTP/1.1\r\nHost: 127.0.0.1\r\nX-API-Key: $LAPTOP_KEY\r\n" +
                    "Content-Length: ${body.length}\r\n\r\n$body"
                socket.getOutputStream().apply {
                    write(request.toByteArray())
                    flush()
                }
                runBlocking { withTimeout(5.seconds) { started.await() } }
            }

            runBlocking { withTimeout(5.seconds) { cancelled.await() } }
        } finally {
            server.stop(100, 1_000)
        }
    }
}
