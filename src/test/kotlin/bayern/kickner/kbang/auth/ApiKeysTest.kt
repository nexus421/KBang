package bayern.kickner.kbang.auth

import bayern.kickner.kbang.CI_KEY
import bayern.kickner.kbang.LAPTOP_KEY
import bayern.kickner.kbang.LAPTOP_KEY_SHA256
import bayern.kickner.kbang.testApiKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeysTest {

    private val keys = ApiKeys(testApiKeys())

    @Test
    fun `sha256Hex matches the standard test vector`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertEquals(LAPTOP_KEY_SHA256, sha256Hex(LAPTOP_KEY))
    }

    @Test
    fun `generated keys are 43 url-safe characters and unique`() {
        val first = generateApiKey()
        val second = generateApiKey()

        assertEquals(43, first.length)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(first), first)
        assertNotEquals(first, second)
    }

    @Test
    fun `the right key names its caller`() {
        assertEquals("laptop", keys.callerFor(LAPTOP_KEY))
        assertEquals("ci", keys.callerFor(CI_KEY))
    }

    @Test
    fun `missing, blank and wrong keys name nobody`() {
        assertNull(keys.callerFor(null))
        assertNull(keys.callerFor(""))
        assertNull(keys.callerFor("   "))
        assertNull(keys.callerFor("some-other-key-0123456789"))
    }

    @Test
    fun `prefix and suffix of a right key name nobody`() {
        assertNull(keys.callerFor(LAPTOP_KEY.dropLast(1)))
        assertNull(keys.callerFor("${LAPTOP_KEY}x"))
    }

    @Test
    fun `the hash itself is not a valid key`() {
        assertNull(keys.callerFor(LAPTOP_KEY_SHA256))
    }
}
