package bayern.kickner.kbang.build

import bayern.kickner.kbang.config.AppConfig
import bayern.kickner.klogger.errorLog
import bayern.kickner.klogger.warnLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Every workspace directory starts with this, so leftovers can be recognized after a crash. */
private const val WORKSPACE_PREFIX = "kbang-"

/** How a build ended, as handed to the caller while the workspace still exists. */
sealed interface BuildResult {
    /** The build produced [artifact]. The file is deleted together with the workspace after delivery. */
    data class Success(val artifact: File) : BuildResult

    /** The build failed, usually a compile error. [log] is the tail of the build output. */
    data class Failed(val log: String) : BuildResult

    /** The build ran longer than allowed and was killed. [log] is the tail of the build output. */
    data class TimedOut(val log: String) : BuildResult

    /** The build could not be started at all, a problem of the server, not of the source. */
    data class StartFailed(val reason: String) : BuildResult
}

/** Turns a source file into an artifact. */
fun interface Builder {
    /**
     * Builds [source] as described by [spec] and passes the result to [deliver]. Everything the build created is
     * deleted when this function returns, also when it is cancelled or [deliver] throws. A cancelled build calls
     * [deliver] not at all.
     */
    suspend fun build(spec: BuildSpec, source: ByteArray, deliver: suspend (BuildResult) -> Unit)
}

/**
 * Everything [JbangBuilder] needs from the config.
 *
 * @property jbang JBang executable, a name on the `PATH` of the build environment or an absolute path.
 * @property environment Added to the environment of every build. Its `PATH` also decides where [jbang] is looked up.
 * @property offline Passes `--offline` to JBang, so only dependencies in the local Maven repository resolve.
 * @property nativeOptions Options for `native-image`, each passed as `-N=<option>`.
 * @property timeout A build running longer is killed.
 * @property maxLogBytes Only this many bytes from the end of the build log reach the client.
 * @property workDir Parent directory of the per-build workspaces.
 */
data class BuildSettings(
    val jbang: String,
    val environment: Map<String, String>,
    val offline: Boolean,
    val nativeOptions: List<String>,
    val timeout: Duration,
    val maxLogBytes: Int,
    val workDir: File
)

/** The build-related part of the config. */
fun AppConfig.toBuildSettings() = BuildSettings(
    jbang = jbang,
    environment = environment,
    offline = offline,
    nativeOptions = nativeOptions,
    timeout = buildTimeoutMinutes.minutes,
    maxLogBytes = maxLogKb * 1024,
    workDir = File(workDir)
)

/**
 * Builds with `jbang export`, one fresh workspace per build:
 *
 * ```
 * <workDir>/kbang-<random>/
 *     <name>.kt      the uploaded source
 *     out/<artifact> what JBang exports
 *     jbang-jars/    JBang's build cache for this build only
 *     tmp/           temporary files of JBang, native-image and the linker (see jbangCommand)
 *     build.log      stdout and stderr of the build
 * ```
 *
 * The workspace is deleted after delivery, so nothing of a build stays on disk.
 */
class JbangBuilder(private val settings: BuildSettings) : Builder {

    override suspend fun build(spec: BuildSpec, source: ByteArray, deliver: suspend (BuildResult) -> Unit) {
        val workspace = runCatching {
            settings.workDir.mkdirs()
            Files.createTempDirectory(settings.workDir.toPath(), WORKSPACE_PREFIX).toFile()
        }.getOrElse { return deliver(startFailed("Could not create a workspace in ${settings.workDir}: ${it.message}")) }
        try {
            deliver(run(spec, source, workspace))
        } finally {
            val deleted = workspace.deleteTree()
            if (deleted.not()) warnLog { "Could not delete the workspace $workspace completely, remove it by hand" }
        }
    }

    private suspend fun run(spec: BuildSpec, source: ByteArray, workspace: File): BuildResult {
        val searchPath = settings.environment["PATH"] ?: System.getenv("PATH")
        if (findExecutable(settings.jbang, searchPath) == null) return startFailed("JBang executable '${settings.jbang}' not found")

        val sourceFile = File(workspace, "${spec.name}.kt")
        // A full disk is a problem of the server, not of the source
        runCatching { sourceFile.writeBytes(source) }.onFailure { return startFailed("Could not write the source to $sourceFile: ${it.message}") }
        val output = File(workspace, "out/${spec.artifactName}").apply { parentFile.mkdirs() }
        val log = File(workspace, "build.log")
        val tmpDir = File(workspace, "tmp").apply { mkdirs() }
        val command = jbangCommand(
            settings.jbang, spec, sourceFile, output, File(workspace, "jbang-jars"), tmpDir,
            settings.offline, settings.nativeOptions, settings.environment
        )

        // Plain try/catch on purpose: runCatching would also catch the CancellationException of a client that went
        // away and turn it into a reply on a cancelled call
        val outcome = try {
            runProcessGroup(command.args, command.environment, workspace, log, settings.timeout)
        } catch (e: IOException) {
            return startFailed("Could not start the build: ${e.message}")
        }

        return when (outcome) {
            is ProcessOutcome.Exited -> when {
                outcome.code != 0 -> BuildResult.Failed(logTail(log, tmpDir))
                output.isFile.not() -> BuildResult.Failed(logTail(log, tmpDir) + "\nThe build exited without error but produced no artifact.\n")
                else -> BuildResult.Success(output)
            }

            ProcessOutcome.TimedOut -> BuildResult.TimedOut(logTail(log, tmpDir) + "\nBuild aborted after ${settings.timeout} (buildTimeoutMinutes).\n")
        }
    }

    /**
     * The tail of the build output for the client. JBang only names the file it wrote the native-image output to,
     * and that file is gone together with the workspace once the client reads the name. So the native-image log is
     * appended after JBang's own output: the tail is where the error is, and for a failed native-image run the
     * error is in its log.
     */
    private fun logTail(log: File, tmpDir: File): String {
        val nativeImageLogs = tmpDir.listFiles { file -> file.isFile && file.name.startsWith("jbang") && file.name.endsWith("native-image") && file.length() > 0 }
            .orEmpty().sortedBy { it.name }
        if (nativeImageLogs.isEmpty()) return readLogTail(log, settings.maxLogBytes)

        val combined = File(log.parentFile, "combined.log")
        combined.outputStream().use { out ->
            if (log.isFile) log.inputStream().use { it.copyTo(out) }
            nativeImageLogs.forEach { nativeLog ->
                out.write("\n[native-image log]\n".toByteArray())
                nativeLog.inputStream().use { it.copyTo(out) }
            }
        }
        return readLogTail(combined, settings.maxLogBytes)
    }

    private fun startFailed(reason: String): BuildResult {
        errorLog(reason, printStackTrace = false)
        return BuildResult.StartFailed(reason)
    }
}

/**
 * The last [maxBytes] bytes of [log] as text, marked when something was cut off. Build logs of native-image runs
 * are long, and the end is where the error is.
 */
fun readLogTail(log: File, maxBytes: Int): String {
    if (log.isFile.not()) return ""
    val length = log.length()
    if (length <= maxBytes) return log.readText()

    val tail = ByteArray(maxBytes)
    RandomAccessFile(log, "r").use { file ->
        file.seek(length - maxBytes)
        file.readFully(tail)
    }
    return "[log truncated, showing the last ${maxBytes / 1024} KiB]\n" + tail.decodeToString()
}

/**
 * Deletes workspace directories (`kbang-*`) left in [workDir] by a crash and returns how many. Called once at
 * startup, before any build runs.
 */
fun cleanStaleWorkspaces(workDir: File): Int {
    val stale = workDir.listFiles { file -> file.isDirectory && file.name.startsWith(WORKSPACE_PREFIX) }.orEmpty()
    return stale.count { it.deleteTree() }
}

/**
 * Deletes this file or directory tree and returns whether everything is gone. A symbolic link is removed, never
 * followed. `File.deleteRecursively` would follow a link to a directory and empty its target, as root possibly
 * `/opt/graalvm` or the Maven repository.
 */
@OptIn(ExperimentalPathApi::class)
internal fun File.deleteTree(): Boolean = runCatching { toPath().deleteRecursively() }.isSuccess
