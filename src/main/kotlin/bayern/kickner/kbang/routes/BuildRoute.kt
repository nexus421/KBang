package bayern.kickner.kbang.routes

import bayern.kickner.kbang.service.BuildCall
import bayern.kickner.kbang.service.BuildReply
import bayern.kickner.kbang.service.BuildService
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post

/** Header that carries the API key. Never a query parameter, URLs end up in proxy logs. */
private const val API_KEY_HEADER = "X-API-Key"

/**
 * `POST /build/{target}`: builds the request body (one Kotlin file) and answers with the artifact or a plain-text
 * error. The whole flow lives in [BuildService], this route only translates between HTTP and it.
 *
 * The build runs while the client waits. When the client goes away, Ktor cancels this call (the
 * `HttpRequestLifecycle` plugin installed in `kbang`), which kills the build and deletes its files.
 */
fun Route.buildRoute(service: BuildService) {
    post("/build/{target}") {
        service.handle(KtorBuildCall(call)) { reply ->
            when (reply) {
                is BuildReply.Artifact -> {
                    val disposition = ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, reply.fileName)
                    call.response.header(HttpHeaders.ContentDisposition, disposition.toString())
                    call.respondFile(reply.file)
                }

                is BuildReply.Text -> call.respondText(reply.message, ContentType.Text.Plain, HttpStatusCode.fromValue(reply.status))
            }
        }
    }
}

/** A Ktor call seen as a [BuildCall]. */
private class KtorBuildCall(private val call: RoutingCall) : BuildCall {
    override val apiKey: String? = call.request.header(API_KEY_HEADER)
    override val target: String? = call.pathParameters["target"]
    override val name: String? = call.request.queryParameters["name"]
    override val arch: String? = call.request.queryParameters["arch"]

    /** Behind the reverse proxy the connection peer is always the proxy. Its forwarded header names the real client. */
    override val clientAddress: String by lazy { call.request.header("X-Forwarded-For") ?: call.request.origin.remoteAddress }

    /**
     * Reads the body. `RequestBodyLimit` enforces the upload limit while the body streams in, and for a chunked
     * body its failure can arrive wrapped. Ktor only answers the bare [PayloadTooLargeException] with 413, so it
     * is unwrapped here. A plain try/catch, because it rethrows in every case, a cancellation included.
     */
    override suspend fun receiveSource(): ByteArray = try {
        call.receive<ByteArray>()
    } catch (e: Throwable) {
        throw generateSequence(e) { it.cause }.filterIsInstance<PayloadTooLargeException>().firstOrNull() ?: e
    }
}
