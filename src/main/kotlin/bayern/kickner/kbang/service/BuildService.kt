package bayern.kickner.kbang.service

import bayern.kickner.kbang.auth.ApiKeys
import bayern.kickner.kbang.build.BuildQueue
import bayern.kickner.kbang.build.BuildResult
import bayern.kickner.kbang.build.BuildSpec
import bayern.kickner.kbang.build.Builder
import bayern.kickner.kbang.build.parseBuildRequest
import bayern.kickner.klogger.infoLog
import bayern.kickner.klogger.warnLog
import kotlinx.coroutines.CancellationException
import kotnexlib.ResultOf2
import java.io.File
import java.util.Locale

/**
 * One build request as the HTTP layer sees it. Everything but the body is available at once, the body is read
 * only when [receiveSource] is called, so unauthenticated or malformed requests never get that far.
 *
 * @property apiKey Value of the `X-API-Key` header, if any.
 * @property target Last path segment of `/build/<target>`.
 * @property name The `name` query parameter, if any.
 * @property arch The `arch` query parameter, if any.
 * @property clientAddress Who sent the request, for log lines. Behind a reverse proxy the proxy's forwarded header.
 */
interface BuildCall {
    val apiKey: String?
    val target: String?
    val name: String?
    val arch: String?
    val clientAddress: String

    /** Reads the request body, the source file. Throws when the body exceeds the upload limit. */
    suspend fun receiveSource(): ByteArray
}

/** What the HTTP layer sends back. */
sealed interface BuildReply {
    /**
     * The built artifact. [file] only exists while the reply is being sent, it is deleted right afterwards.
     *
     * @property fileName Name for the client, sent as the attachment file name.
     */
    data class Artifact(val file: File, val fileName: String) : BuildReply

    /** A plain-text answer with an HTTP [status], everything that is not an artifact. */
    data class Text(val status: Int, val message: String) : BuildReply
}

/**
 * The whole flow of a build request, free of any HTTP framework: authenticate, validate, read the body, wait for a
 * build slot, build, reply. Every request gets exactly one reply, except when it is cancelled (the client went
 * away), in which case the build is killed and nothing is replied.
 *
 * @param hostArch Normalized architecture of this machine.
 * @param clock Current time in milliseconds, injectable for tests.
 */
class BuildService(
    private val apiKeys: ApiKeys,
    private val builder: Builder,
    private val queue: BuildQueue,
    private val hostArch: String,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Handles [call] and passes the answer to [reply]. Exceptions from reading the body propagate unchanged. */
    suspend fun handle(call: BuildCall, reply: suspend (BuildReply) -> Unit) {
        val caller = apiKeys.callerFor(call.apiKey)
        if (caller == null) {
            warnLog { "Rejected build request from ${call.clientAddress}: missing or unknown API key" }
            return reply(BuildReply.Text(401, "Missing or invalid API key."))
        }

        val spec = when (val parsed = parseBuildRequest(call.target, call.name, call.arch, hostArch)) {
            is ResultOf2.Success -> parsed.value
            is ResultOf2.Failure -> return reply(BuildReply.Text(parsed.value.status, parsed.value.message))
        }

        val source = call.receiveSource()
        if (source.decodeToString().isBlank()) return reply(BuildReply.Text(400, "The request body must be the Kotlin source file."))

        val started = clock()
        var outcomeLogged = false
        // Plain try/catch on purpose: only to log a cancellation (the client went away) and rethrow it unchanged
        val ran = try {
            queue.withSlot {
                builder.build(spec, source) { result ->
                    logOutcome(caller, spec, result, clock() - started)
                    outcomeLogged = true
                    reply(result.toReply(spec))
                }
            }
        } catch (e: CancellationException) {
            // The client is gone, the builder has already killed the build and removed its files. A cancellation during
            // or right after the reply is no news: a client closes the connection as soon as it has every byte.
            if (outcomeLogged.not()) {
                infoLog { "Build of '${spec.artifactName}' for '$caller' cancelled after ${seconds(clock() - started)}, the client went away" }
            }
            throw e
        }

        if (ran == null) {
            warnLog { "Build of '${spec.artifactName}' for '$caller' refused, the build queue is full" }
            reply(BuildReply.Text(503, "The build queue is full. Try again later."))
        }
    }

    private fun BuildResult.toReply(spec: BuildSpec): BuildReply = when (this) {
        is BuildResult.Success -> BuildReply.Artifact(artifact, spec.artifactName)
        is BuildResult.Failed -> BuildReply.Text(422, log)
        is BuildResult.TimedOut -> BuildReply.Text(422, log)
        // The reason names server paths, the server log has it
        is BuildResult.StartFailed -> BuildReply.Text(500, "The build could not be started. See the server log.")
    }

    private fun logOutcome(caller: String, spec: BuildSpec, result: BuildResult, millis: Long) {
        val outcome = when (result) {
            is BuildResult.Success -> "succeeded (${result.artifact.length() / 1024} KiB)"
            is BuildResult.Failed -> "failed"
            is BuildResult.TimedOut -> "timed out"
            is BuildResult.StartFailed -> "could not be started"
        }
        infoLog { "Build of '${spec.artifactName}' for '$caller' $outcome after ${seconds(millis)}" }
    }

    private fun seconds(millis: Long) = String.format(Locale.ENGLISH, "%.1f s", millis / 1000.0)
}
