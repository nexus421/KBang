package bayern.kickner.kbang.auth

import bayern.kickner.kbang.config.ApiKeyEntry
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 256 bits of entropy. The key is the only authentication KBang has, so there is no reason to be stingy. */
private const val API_KEY_BYTES = 32

/**
 * A fresh API key: [API_KEY_BYTES] random bytes as unpadded URL-safe Base64 (43 characters).
 *
 * @param random Source of randomness, injectable for tests.
 */
fun generateApiKey(random: SecureRandom = SecureRandom()): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(API_KEY_BYTES).also(random::nextBytes))

/** SHA-256 of the UTF-8 bytes of [text] as 64 lowercase hex characters, the form the config stores keys in. */
fun sha256Hex(text: String): String = sha256(text).toHexString()

private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

/**
 * The configured clients, looked up by the key they present.
 *
 * Only the hashes are known. A presented key is hashed and compared against every entry in constant time, so
 * neither the config file nor response timing reveals anything about valid keys.
 *
 * @param entries The `apiKeys` of the config, already validated (unique, 64 lowercase hex characters).
 */
class ApiKeys(entries: List<ApiKeyEntry>) {

    private val hashes: List<Pair<String, ByteArray>> = entries.map { it.name to it.sha256.hexToByteArray() }

    /** The name of the client that owns [presented], or null for a missing, blank or unknown key. */
    fun callerFor(presented: String?): String? {
        if (presented.isNullOrBlank()) return null
        val hash = sha256(presented)
        // No early exit, every entry is compared so the time taken does not depend on which entry matches
        return hashes.fold(null as String?) { found, (name, expected) -> if (MessageDigest.isEqual(expected, hash)) name else found }
    }
}
