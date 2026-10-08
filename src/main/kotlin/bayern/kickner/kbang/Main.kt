package bayern.kickner.kbang

import bayern.kickner.kbang.auth.ApiKeys
import bayern.kickner.kbang.build.BuildQueue
import bayern.kickner.kbang.build.JbangBuilder
import bayern.kickner.kbang.build.cleanStaleWorkspaces
import bayern.kickner.kbang.build.findExecutable
import bayern.kickner.kbang.build.hostArch
import bayern.kickner.kbang.build.toBuildSettings
import bayern.kickner.kbang.cli.newKey
import bayern.kickner.kbang.config.AppConfig
import bayern.kickner.kbang.config.loadConfig
import bayern.kickner.kbang.routes.buildRoute
import bayern.kickner.kbang.routes.clientScriptRoute
import bayern.kickner.kbang.routes.healthRoute
import bayern.kickner.kbang.service.BuildService
import bayern.kickner.kbang.service.clientScript
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.slf4j.slf4jBridge
import bayern.kickner.klogger.staticLog
import io.ktor.server.application.Application
import io.ktor.server.application.ServerReady
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.routing.routing
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf2
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.system.exitProcess

private const val TAG = "Main"

/**
 * `EX_CONFIG` from sysexits.h, used when the config file is rejected. kbang.service lists it in
 * `RestartPreventExitStatus`: a restart cannot fix a broken config, so systemd must not loop.
 */
private const val EXIT_CONFIG_ERROR = 78

/** The server could not start, typically because the port is taken. A plain failure, systemd may retry. */
private const val EXIT_START_FAILED = 1

/** The one format for every timestamp KBang prints, e.g. `19.09.2026 18:40:12 CEST`. */
internal val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z", Locale.ENGLISH)

/** Version and build time for the log banner, e.g. `1.0.0 (built 07.10.2026 21:40:00 CEST)`. */
internal val appVersion: String =
    "${BuildConfig.VERSION} (built ${Instant.ofEpochMilli(BuildConfig.BUILD_TIME).atZone(ZoneId.systemDefault()).format(timestampFormat)})"

/**
 * Entry point. Loads the config (`config=<path>`, default `config.json` in the working directory), then serves
 * until the process is stopped. A config problem ends the process with [EXIT_CONFIG_ERROR], a failed start with
 * [EXIT_START_FAILED].
 *
 * `key name=<client>` prints a fresh API key (stdout) and its config entry (stderr) instead, no config needed.
 */
fun main(args: Array<String>) {
    KLogger.configure {
        logToConsole()
        minLevel = KLogger.Level.INFO
        // Ktor logs through SLF4J, which Klogger takes over. Only its warnings and errors are worth the journal
        slf4jBridge { minLevel = KLogger.Level.WARN }
    }

    val arguments = ArgsInterpreter(args)
    // ArgsInterpreter only knows key=value pairs and -flags, a bare word is checked directly
    if (args.contains("key")) {
        val newKey = newKey(arguments.getValue("name") ?: "client")
        // Key on stdout, instructions on stderr, so `KEY=$(java -jar kbang.jar key)` captures only the key
        System.err.println(newKey.instructions)
        println(newKey.key)
        return
    }

    val config = when (val result = loadConfig(arguments.getValue("config") ?: "config.json")) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { result.value }
            exitProcess(EXIT_CONFIG_ERROR)
        }
    }

    runCatching { kbangServer(config).start(wait = true) }.onFailure { error ->
        staticLog(KLogger.Level.ERROR, TAG) { "Could not start on ${config.listenHost}:${config.listenPort}: ${error.rootCause().message}" }
        exitProcess(EXIT_START_FAILED)
    }
}

/**
 * Builds the HTTP server: warns about missing tools and logs the banner. Workspaces left by a crash are removed
 * once the port is bound. A second instance that cannot bind, for example one started by hand while the service
 * runs, so never deletes the workspaces of running builds.
 */
fun kbangServer(config: AppConfig): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    val settings = config.toBuildSettings()
    // setsid and kill are started by KBang itself, jbang by setsid with the build environment
    if (findExecutable("setsid", System.getenv("PATH")) == null) {
        staticLog(KLogger.Level.WARN, TAG) { "setsid not found on the PATH, every build will fail. It is part of util-linux." }
    }
    if (findExecutable("kill", System.getenv("PATH")) == null) {
        staticLog(KLogger.Level.WARN, TAG) { "kill not found on the PATH, leftover build processes cannot be stopped. It is part of procps." }
    }
    val buildPath = config.environment["PATH"] ?: System.getenv("PATH")
    if (findExecutable(config.jbang, buildPath) == null) {
        staticLog(KLogger.Level.WARN, TAG) { "JBang executable '${config.jbang}' not found, every build will fail until it is installed" }
    }

    val arch = hostArch()
    staticLog(KLogger.Level.INFO, TAG) {
        "KBang $appVersion listening on ${config.listenHost}:${config.listenPort}, building for $arch, " +
            "${config.apiKeys.size} client(s): ${config.apiKeys.joinToString { it.name }}"
    }

    val service = BuildService(ApiKeys(config.apiKeys), JbangBuilder(settings), BuildQueue(config.maxParallelBuilds, config.maxQueuedBuilds), arch)
    val script = clientScript(config.baseUrl)
    return embeddedServer(CIO, host = config.listenHost, port = config.listenPort) {
        monitor.subscribe(ServerReady) {
            val removed = cleanStaleWorkspaces(settings.workDir)
            if (removed > 0) staticLog(KLogger.Level.WARN, TAG) { "Removed $removed workspace(s) left over from an earlier run" }
        }
        kbang(service, script, config.maxUploadKb * 1024L)
    }
}

/**
 * Ktor module wiring the three endpoints. Kept apart from the server setup so route tests can host it with fakes.
 *
 * @param script The client script, see `clientScript`.
 * @param maxUploadBytes Largest accepted request body. Bigger uploads are answered with 413.
 */
fun Application.kbang(service: BuildService, script: String, maxUploadBytes: Long) {
    // Without this, Ktor keeps a call running after its client disconnected, and with it the build
    install(HttpRequestLifecycle) {
        cancelCallOnClose = true
    }
    install(RequestBodyLimit) {
        bodyLimit { maxUploadBytes }
    }
    routing {
        healthRoute()
        clientScriptRoute(script)
        buildRoute(service)
    }
}

private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()
