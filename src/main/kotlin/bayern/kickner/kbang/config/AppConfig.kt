package bayern.kickner.kbang.config

import kotlinx.serialization.Serializable

/**
 * Root configuration, loaded once at startup from the JSON config file (see [loadConfig]).
 *
 * @property listenHost Interface the HTTP server binds to. The default keeps KBang reachable only from the local
 * machine, where the TLS-terminating reverse proxy is expected to run.
 * @property listenPort Port the HTTP server listens on.
 * @property publicUrl URL under which clients reach KBang, e.g. `https://kbang.example.com`. Baked into the client
 * script served at `/kbang.sh`, so the one-liner works without further settings.
 * @property apiKeys Clients allowed to build. At least one.
 * @property jbang JBang executable, a name on the `PATH` or an absolute path.
 * @property environment Extra environment variables for every build, typically `JAVA_HOME` (a GraalVM) and a
 * `PATH` that contains the musl toolchain.
 * @property offline Passes `--offline` to JBang: only dependencies already in the local Maven repository resolve. Off
 * by default, so JBang downloads whatever a script declares with `//DEPS`.
 * @property nativeOptions Options for `native-image`, each passed as `-N=<option>`. The default yields a small,
 * fully static musl binary that runs on any Linux of the same CPU architecture.
 * @property maxUploadKb Largest accepted source file in KiB.
 * @property maxParallelBuilds Builds that run at the same time. Each native build takes all cores and about 2 GB.
 * @property maxQueuedBuilds Builds that may wait for a free slot. Requests beyond that are answered with 503.
 * @property buildTimeoutMinutes A build running longer is killed and answered with 422.
 * @property maxLogKb Only the tail of a build log up to this size is returned to the client.
 * @property workDir Directory for the per-request workspaces. Leftovers from a crash are deleted at startup.
 */
@Serializable
data class AppConfig(
    val listenHost: String = "127.0.0.1",
    val listenPort: Int = 8080,
    val publicUrl: String,
    val apiKeys: List<ApiKeyEntry>,
    val jbang: String = "jbang",
    val environment: Map<String, String> = emptyMap(),
    val offline: Boolean = false,
    val nativeOptions: List<String> = listOf("-Ob", "--static", "--libc=musl"),
    val maxUploadKb: Int = 256,
    val maxParallelBuilds: Int = 1,
    val maxQueuedBuilds: Int = 4,
    val buildTimeoutMinutes: Int = 10,
    val maxLogKb: Int = 64,
    val workDir: String = System.getProperty("java.io.tmpdir") + "/kbang"
) {
    /** [publicUrl] without trailing slashes, ready to have a path appended. */
    val baseUrl: String get() = publicUrl.trimEnd('/')
}

/**
 * A client allowed to build.
 *
 * @property name Shown in log lines, never the key itself. Must be unique.
 * @property sha256 SHA-256 of the client's API key as 64 lowercase hex characters. Only the hash is stored, so the
 * config file does not reveal usable keys. `java -jar kbang.jar key` prints a fresh key with its hash.
 */
@Serializable
data class ApiKeyEntry(val name: String, val sha256: String) {
    override fun toString() = "ApiKeyEntry(name='$name')"
}
