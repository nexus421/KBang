package bayern.kickner.kbang.cli

import bayern.kickner.kbang.auth.generateApiKey
import bayern.kickner.kbang.auth.sha256Hex
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandsTest {

    /** Always yields the same bytes, so the generated key is known. */
    private class FixedRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) = bytes.fill(7)
    }

    @Test
    fun `new key comes with the matching config entry`() {
        val key = generateApiKey(FixedRandom())

        val newKey = newKey("laptop", FixedRandom())

        assertEquals(key, newKey.key)
        assertTrue(newKey.instructions.contains("""{ "name": "laptop", "sha256": "${sha256Hex(key)}" }"""), newKey.instructions)
    }

    @Test
    fun `instructions never repeat the key itself`() {
        val newKey = newKey("laptop", FixedRandom())

        assertTrue(newKey.instructions.contains(newKey.key).not(), newKey.instructions)
    }

    @Test
    fun `quotes in the name keep the entry valid json`() {
        val newKey = newKey("""my "box"""", FixedRandom())

        assertTrue(newKey.instructions.contains("\"name\": \"my \\\"box\\\"\","), newKey.instructions)
    }
}
