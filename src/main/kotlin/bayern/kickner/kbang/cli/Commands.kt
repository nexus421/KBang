package bayern.kickner.kbang.cli

import bayern.kickner.kbang.auth.generateApiKey
import bayern.kickner.kbang.auth.sha256Hex
import java.security.SecureRandom

/**
 * Output of `java -jar kbang.jar key name=<name>`.
 *
 * @property key The fresh API key. Printed alone to stdout, so `KEY=$(java -jar kbang.jar key)` captures just it.
 * @property instructions What to do with it, including the `apiKeys` entry for the config. Printed to stderr.
 */
data class NewKey(val key: String, val instructions: String)

/**
 * A fresh API key for a client and the `apiKeys` entry for the config. The key itself is stored nowhere, only its
 * hash goes into the config, so the instructions never repeat the key.
 *
 * @param name Client name for the config entry and the log lines.
 * @param random Source of randomness, injectable for tests.
 */
fun newKey(name: String, random: SecureRandom = SecureRandom()): NewKey {
    val key = generateApiKey(random)
    val jsonName = name.replace("\\", "\\\\").replace("\"", "\\\"")
    val instructions = """
        |New API key for '$name' on stdout. Give it to the client as KBANG_KEY, it is stored nowhere.
        |Add this entry to "apiKeys" in the config and restart KBang:
        |
        |  { "name": "$jsonName", "sha256": "${sha256Hex(key)}" }
        |""".trimMargin()
    return NewKey(key, instructions)
}
