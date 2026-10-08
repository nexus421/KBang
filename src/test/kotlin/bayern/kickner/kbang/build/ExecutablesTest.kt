package bayern.kickner.kbang.build

import bayern.kickner.kbang.fakeJbang
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecutablesTest {

    private val dir = createTempDirectory("kbang-exec-test").toFile()
    private val fake = fakeJbang(dir)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `absolute path is used as is`() {
        assertEquals(fake, findExecutable(fake.path, path = null))
    }

    @Test
    fun `bare name is searched on the given path`() {
        assertEquals(File(dir, "fake-jbang"), findExecutable("fake-jbang", path = "/nonexistent:${dir.path}"))
    }

    @Test
    fun `missing or not executable gives null`() {
        File(dir, "plain").writeText("x")

        assertNull(findExecutable("fake-jbang", path = "/nonexistent"))
        assertNull(findExecutable("fake-jbang", path = null))
        assertNull(findExecutable(File(dir, "plain").path, path = null))
        assertNull(findExecutable("plain", path = dir.path))
        assertNull(findExecutable(dir.path, path = null))
    }
}
