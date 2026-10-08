package bayern.kickner.kbang.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArchTest {

    @Test
    fun `x86 aliases normalize to x86_64`() {
        listOf("x86_64", "amd64", "x64", "AMD64", " amd64 ").forEach { assertEquals("x86_64", normalizeArch(it), it) }
    }

    @Test
    fun `arm aliases normalize to aarch64`() {
        listOf("aarch64", "arm64", "ARM64").forEach { assertEquals("aarch64", normalizeArch(it), it) }
    }

    @Test
    fun `unknown architectures are null`() {
        assertNull(normalizeArch("riscv64"))
        assertNull(normalizeArch(""))
    }

    @Test
    fun `host arch is normalized when known and kept otherwise`() {
        assertEquals("x86_64", hostArch("amd64"))
        assertEquals("aarch64", hostArch("aarch64"))
        assertEquals("ppc64le", hostArch("ppc64le"))
    }
}
