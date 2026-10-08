package bayern.kickner.kbang.build

import kotnexlib.ResultOf2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuildRequestTest {

    private fun parse(target: String? = "native", name: String? = null, arch: String? = null, host: String = "x86_64") =
        parseBuildRequest(target, name, arch, host)

    private fun spec(result: ResultOf2<BuildSpec, Rejection>) = assertIs<ResultOf2.Success<BuildSpec>>(result).value

    private fun rejection(result: ResultOf2<BuildSpec, Rejection>) = assertIs<ResultOf2.Failure<Rejection>>(result).value

    @Test
    fun `default name is script for both targets`() {
        assertEquals(BuildSpec(BuildTarget.NATIVE, "script"), spec(parse("native")))
        assertEquals("script", spec(parse("native")).artifactName)
        assertEquals(BuildSpec(BuildTarget.JAR, "script"), spec(parse("jar")))
        assertEquals("script.jar", spec(parse("jar")).artifactName)
    }

    @Test
    fun `unknown target is 404`() {
        assertEquals(404, rejection(parse("exe")).status)
        assertEquals(404, rejection(parse(null)).status)
        assertEquals(404, rejection(parse("NATIVE")).status)
    }

    @Test
    fun `invalid names are 400`() {
        listOf("1abc", "a b", "a.b", "a".repeat(65), "", "../etc", "-x").forEach { name ->
            assertEquals(400, rejection(parse(name = name)).status, name)
        }
    }

    @Test
    fun `valid names are kept`() {
        assertEquals("my-tool_2", spec(parse(name = "my-tool_2")).name)
        assertEquals("a".repeat(64), spec(parse(name = "a".repeat(64))).name)
        assertEquals("tool.jar", spec(parse("jar", name = "tool")).artifactName)
    }

    @Test
    fun `alias of the host arch is accepted`() {
        assertEquals("tool", spec(parse(name = "tool", arch = "amd64")).name)
        assertEquals("tool", spec(parse(name = "tool", arch = "arm64", host = "aarch64")).name)
    }

    @Test
    fun `foreign arch is 400 naming both`() {
        val rejection = rejection(parse(arch = "arm64"))

        assertEquals(400, rejection.status)
        assertTrue(rejection.message.contains("x86_64") && rejection.message.contains("aarch64"), rejection.message)
    }

    @Test
    fun `unknown arch is 400`() {
        val rejection = rejection(parse(arch = "sparc"))

        assertEquals(400, rejection.status)
        assertTrue(rejection.message.contains("sparc"), rejection.message)
    }

    @Test
    fun `missing or blank arch means the host arch`() {
        assertEquals("script", spec(parse(arch = null)).name)
        assertEquals("script", spec(parse(arch = "")).name)
    }

    @Test
    fun `jar ignores the arch`() {
        assertEquals(BuildSpec(BuildTarget.JAR, "script"), spec(parse("jar", arch = "arm64")))
        assertEquals(BuildSpec(BuildTarget.JAR, "script"), spec(parse("jar", arch = "sparc")))
    }

    @Test
    fun `target path names round trip`() {
        BuildTarget.entries.forEach { assertEquals(it, BuildTarget.fromPath(it.path)) }
    }
}
