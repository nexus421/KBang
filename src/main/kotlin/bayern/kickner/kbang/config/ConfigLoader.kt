package bayern.kickner.kbang.config

import kotlinx.serialization.json.Json
import kotnexlib.ResultOf2
import java.io.File

/**
 * Loads and validates the configuration file at [path].
 *
 * Unknown keys are errors, so a typo cannot silently fall back to a default. Every validation issue is collected
 * into one message (see [validate]).
 *
 * @return the config, or a human-readable failure message that never contains the file content.
 */
fun loadConfig(path: String): ResultOf2<AppConfig, String> {
    val file = File(path)
    if (file.exists().not()) return ResultOf2.Failure("Config file not found: ${file.absolutePath}")

    val text = runCatching { file.readText() }
        .getOrElse { return ResultOf2.Failure("Config file ${file.absolutePath} could not be read: ${it.message}") }
    val config = runCatching { Json.decodeFromString<AppConfig>(text) }
        .getOrElse { return ResultOf2.Failure("Config file ${file.absolutePath} could not be parsed: ${sanitize(it.message)}") }

    val issues = validate(config)
    if (issues.isNotEmpty()) return ResultOf2.Failure("Config file ${file.absolutePath} is invalid:\n" + issues.joinToString("\n") { "- $it" })

    return ResultOf2.Success(config)
}

/**
 * Keeps only the first line of a kotlinx.serialization message: the following lines are either a developer hint
 * or the offending document ("JSON input: ..."), which would leak the file content.
 */
private fun sanitize(message: String?): String =
    message?.substringBefore("JSON input")?.lineSequence()?.first()?.trim()?.ifEmpty { null } ?: "unknown error"
