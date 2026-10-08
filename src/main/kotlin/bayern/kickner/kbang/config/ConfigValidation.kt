package bayern.kickner.kbang.config

import java.net.URI

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/** Upper bound for `maxLogKb`, a log tail is for reading and must fit into memory as one string. */
private const val MAX_LOG_KB_LIMIT = 10_240

/**
 * Characters `publicUrl` may consist of. It is baked into the client script, where quotes, `$`, backticks or braces
 * would be shell syntax that runs on every client. Square brackets are allowed for IPv6 literals, the script uses the
 * URL only in comments, inside double quotes and in an assignment, where they are not expanded.
 */
private val PUBLIC_URL_CHARACTERS = Regex("^[A-Za-z0-9.:/_~%@+\\[\\]-]+$")

/**
 * Checks [config] for values KBang cannot work with and returns one human-readable line per problem, empty when
 * the config is usable. Every problem is reported, so a broken config needs only one restart to fix. Key hashes
 * never appear in a message, the entries are named instead.
 */
fun validate(config: AppConfig): List<String> {
    val issues = mutableListOf<String>()

    if (config.listenHost.isBlank()) issues += "listenHost must not be blank"
    if ((config.listenPort in 1..65535).not()) issues += "listenPort must be between 1 and 65535"
    if (config.publicUrl.isHttpUrl().not()) issues += "publicUrl must be an http or https URL with a host, e.g. https://kbang.example.com"
    else if (PUBLIC_URL_CHARACTERS.matches(config.publicUrl).not()) {
        issues += "publicUrl may only contain letters, digits and . : / _ ~ % @ + - [ ], it is baked into the client script"
    }

    if (config.apiKeys.isEmpty()) issues += "apiKeys must contain at least one entry"
    config.apiKeys.forEachIndexed { index, entry ->
        if (entry.name.isBlank()) issues += "apiKeys[$index]: name must not be blank"
        if (SHA256_HEX.matches(entry.sha256).not()) issues += "apiKeys[$index] ('${entry.name}'): sha256 must be 64 lowercase hex characters"
    }
    config.apiKeys.groupBy { it.name }.filterValues { it.size > 1 }.keys
        .forEach { name -> issues += "apiKeys: name '$name' is not unique" }
    config.apiKeys.groupBy { it.sha256 }.filterValues { it.size > 1 }.values
        .forEach { entries -> issues += "apiKeys: ${entries.joinToString(" and ") { "'${it.name}'" }} share the same key" }

    if (config.jbang.isBlank()) issues += "jbang must not be blank"
    // A relative path would be resolved against the workspace the build runs in, not against KBang's directory
    else if (config.jbang.contains('/') && config.jbang.startsWith('/').not()) issues += "jbang must be a name on the PATH or an absolute path"
    if (config.nativeOptions.any { it.isBlank() }) issues += "nativeOptions must not contain blank entries"
    // JBang splits every -N value at commas, so such an option would reach native-image as two broken ones
    if (config.nativeOptions.any { it.contains(',') }) {
        issues += "nativeOptions must not contain a comma, JBang splits options there. Repeat the option once per value instead."
    }
    if (config.maxUploadKb < 1) issues += "maxUploadKb must be at least 1"
    if (config.maxParallelBuilds < 1) issues += "maxParallelBuilds must be at least 1"
    if (config.maxQueuedBuilds < 0) issues += "maxQueuedBuilds must not be negative"
    if (config.buildTimeoutMinutes < 1) issues += "buildTimeoutMinutes must be at least 1"
    if ((config.maxLogKb in 1..MAX_LOG_KB_LIMIT).not()) issues += "maxLogKb must be between 1 and $MAX_LOG_KB_LIMIT"
    issues += validateWorkDir(config.workDir)

    return issues
}

/**
 * Every build runs inside its workspace below [workDir] and gets paths into it, so a relative [workDir] would be
 * resolved twice. The build temp dir below it is passed to JBang in `JBANG_JAVA_OPTIONS`, which its launcher splits
 * at spaces, and as a native option, which JBang splits at commas.
 */
private fun validateWorkDir(workDir: String): List<String> = when {
    workDir.isBlank() -> listOf("workDir must not be blank")
    workDir.startsWith('/').not() -> listOf("workDir must be an absolute path")
    workDir.any { it.isWhitespace() || it == ',' } -> listOf("workDir must not contain whitespace or commas")
    else -> emptyList()
}

private fun String.isHttpUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    val httpScheme = uri.scheme == "http" || uri.scheme == "https"
    return httpScheme && uri.host.isNullOrBlank().not()
}
