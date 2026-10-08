package bayern.kickner.kbang.service

/** Stand-in in `kbang.sh` for the server's URL. */
private const val URL_PLACEHOLDER = "__KBANG_URL__"

/** Anchor for loading `kbang.sh` from the resources of this JAR. */
private object ClientScriptResource

/**
 * The Bash client script served at `/kbang.sh`, with [baseUrl] baked in as its default server. Users run it as
 * `curl -fsSL <baseUrl>/kbang.sh | KBANG_KEY=<key> bash -s -- native tool.kt` or keep a copy.
 */
fun clientScript(baseUrl: String): String {
    val template = ClientScriptResource::class.java.getResource("/kbang.sh")?.readText()
        ?: error("kbang.sh is missing from the resources")
    return template.replace(URL_PLACEHOLDER, baseUrl)
}
