package bayern.kickner.kbang.build

import java.io.File

/**
 * A ready-to-start JBang invocation.
 *
 * @property args Program and arguments, starting with `setsid`.
 * @property environment Variables added to the inherited environment of the build process.
 */
data class JbangCommand(val args: List<String>, val environment: Map<String, String>)

/**
 * Builds the JBang invocation for one build.
 *
 * The command starts with `setsid`, so JBang, its JVM and the native-image builder run in a fresh process group
 * whose id is the started pid. That group is what gets killed on timeout or cancellation (see [runProcessGroup]).
 *
 * `JBANG_CACHE_DIR_JARS` points JBang's build cache into the workspace. `jbang export` ignores `--build-dir` and
 * caches by file name and content, so the shared cache would hand back a binary built with other native options,
 * let two identical concurrent builds race on one directory and grow with every build. The Kotlin compiler and the
 * Maven repository stay shared. `JBANG_NO_VERSION_CHECK` keeps JBang from contacting the internet for updates.
 *
 * Everything a build writes to temporary files goes to [tmpDir] inside the workspace as well, so it disappears with
 * the workspace: JBang keeps the native-image log in `java.io.tmpdir` and never deletes it (set through
 * `JBANG_JAVA_OPTIONS`, a configured value is kept and extended), the native-image builder JVM creates its `SVM-*`
 * directories there (`-J-Djava.io.tmpdir`, after the configured options because the last one wins) and leaves them
 * behind when it is killed, and gcc and the linker use `TMPDIR`. These variables win over [environment], which must
 * not be able to undo them.
 *
 * @param jbang JBang executable, a name on the `PATH` or an absolute path.
 * @param source The `<name>.kt` file in the workspace.
 * @param output Where JBang writes the artifact (`-O`).
 * @param jarCache Per-build JBang jar cache inside the workspace.
 * @param tmpDir Per-build directory for temporary files inside the workspace.
 * @param offline Adds `--offline`, so only dependencies in the local Maven repository resolve.
 * @param nativeOptions Passed as `-N=<option>` for native builds only.
 * @param environment Configured extra variables, typically `JAVA_HOME` and `PATH`.
 */
fun jbangCommand(
    jbang: String,
    spec: BuildSpec,
    source: File,
    output: File,
    jarCache: File,
    tmpDir: File,
    offline: Boolean,
    nativeOptions: List<String>,
    environment: Map<String, String>
): JbangCommand {
    val args = buildList {
        add("setsid")
        add(jbang)
        add("export")
        add(if (spec.target == BuildTarget.NATIVE) "native" else "fatjar")
        if (offline) add("--offline")
        add("-O")
        add(output.path)
        if (spec.target == BuildTarget.NATIVE) {
            nativeOptions.forEach { add("-N=$it") }
            add("-N=-J-Djava.io.tmpdir=${tmpDir.path}")
        }
        add(source.path)
    }
    val jbangJavaOptions = listOfNotNull(environment["JBANG_JAVA_OPTIONS"]?.takeIf { it.isNotBlank() }, "-Djava.io.tmpdir=${tmpDir.path}")
    val env = environment + mapOf(
        "JBANG_CACHE_DIR_JARS" to jarCache.path,
        "JBANG_NO_VERSION_CHECK" to "true",
        "TMPDIR" to tmpDir.path,
        "JBANG_JAVA_OPTIONS" to jbangJavaOptions.joinToString(" ")
    )
    return JbangCommand(args, env)
}
