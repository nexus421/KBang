package bayern.kickner.kbang.build

import kotnexlib.ResultOf2

/** Name of the source file and the artifact when the client sends none. */
private const val DEFAULT_NAME = "script"

/** A letter first, so the name is also a usable file and Kotlin file-class name. Nothing that could leave a directory. */
private val NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")

/**
 * What a build produces.
 *
 * @property path The last segment of the build URL, `/build/<path>`.
 */
enum class BuildTarget(val path: String) {
    /** A native executable built by GraalVM `native-image` (`jbang export native`). */
    NATIVE("native"),

    /** A fat JAR with every dependency inside (`jbang export fatjar`). */
    JAR("jar");

    companion object {
        /** The target whose [path] is exactly [path], or null. */
        fun fromPath(path: String?): BuildTarget? = entries.firstOrNull { it.path == path }
    }
}

/**
 * A validated build request.
 *
 * @property name Base name of the source file (`<name>.kt`) and of the artifact.
 */
data class BuildSpec(val target: BuildTarget, val name: String) {
    /** File name of the artifact as the client receives it: `<name>` for a binary, `<name>.jar` for a JAR. */
    val artifactName: String get() = if (target == BuildTarget.JAR) "$name.jar" else name
}

/**
 * Why a request is refused before anything is built.
 *
 * @property status HTTP status code.
 * @property message Plain-text explanation for the client.
 */
data class Rejection(val status: Int, val message: String)

/**
 * Validates the parts of a build request that come from the URL.
 *
 * @param target Last path segment, `native` or `jar`.
 * @param name Optional `name` query parameter. Missing means `script`.
 * @param arch Optional `arch` query parameter, usually the client's `uname -m`. Missing or blank means the host's
 * architecture. Ignored for JAR builds, which run everywhere.
 * @param hostArch Normalized architecture of this machine, see [hostArch].
 */
fun parseBuildRequest(target: String?, name: String?, arch: String?, hostArch: String): ResultOf2<BuildSpec, Rejection> {
    val buildTarget = BuildTarget.fromPath(target)
        ?: return ResultOf2.Failure(Rejection(404, "Unknown build target '$target'. Use native or jar."))

    val buildName = name ?: DEFAULT_NAME
    if (NAME_PATTERN.matches(buildName).not()) {
        return ResultOf2.Failure(Rejection(400, "Invalid name. Use 1 to 64 letters, digits, '_' or '-', starting with a letter."))
    }

    val checkArch = buildTarget == BuildTarget.NATIVE && arch.isNullOrBlank().not()
    if (checkArch) {
        val requested = normalizeArch(arch.orEmpty())
            ?: return ResultOf2.Failure(Rejection(400, "Unknown architecture '$arch'."))
        if (requested != hostArch) {
            return ResultOf2.Failure(Rejection(400, "This server builds native binaries for $hostArch only, not for $requested."))
        }
    }

    return ResultOf2.Success(BuildSpec(buildTarget, buildName))
}
