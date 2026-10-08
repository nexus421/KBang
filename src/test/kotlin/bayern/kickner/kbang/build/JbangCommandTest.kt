package bayern.kickner.kbang.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class JbangCommandTest {

    private val source = File("/work/kbang-1/tool.kt")
    private val output = File("/work/kbang-1/out/tool")
    private val jarCache = File("/work/kbang-1/jbang-jars")
    private val tmpDir = File("/work/kbang-1/tmp")

    @Test
    fun `native offline build passes every native option`() {
        val command = jbangCommand(
            "jbang", BuildSpec(BuildTarget.NATIVE, "tool"), source, output, jarCache, tmpDir,
            offline = true, nativeOptions = listOf("-Ob", "--static", "--libc=musl"), environment = emptyMap()
        )

        assertEquals(
            listOf(
                "setsid", "jbang", "export", "native", "--offline", "-O", "/work/kbang-1/out/tool",
                "-N=-Ob", "-N=--static", "-N=--libc=musl", "-N=-J-Djava.io.tmpdir=/work/kbang-1/tmp", "/work/kbang-1/tool.kt"
            ),
            command.args
        )
    }

    @Test
    fun `jar build never gets native options`() {
        val command = jbangCommand(
            "/opt/jbang/bin/jbang", BuildSpec(BuildTarget.JAR, "tool"), source, File("/work/kbang-1/out/tool.jar"), jarCache, tmpDir,
            offline = false, nativeOptions = listOf("-Ob"), environment = emptyMap()
        )

        assertEquals(
            listOf("setsid", "/opt/jbang/bin/jbang", "export", "fatjar", "-O", "/work/kbang-1/out/tool.jar", "/work/kbang-1/tool.kt"),
            command.args
        )
    }

    @Test
    fun `environment adds the per-build jar cache and temp dir and silences the version check`() {
        val command = jbangCommand(
            "jbang", BuildSpec(BuildTarget.NATIVE, "tool"), source, output, jarCache, tmpDir,
            offline = true, nativeOptions = emptyList(), environment = mapOf("JAVA_HOME" to "/opt/graalvm")
        )

        assertEquals(
            mapOf(
                "JAVA_HOME" to "/opt/graalvm",
                "JBANG_CACHE_DIR_JARS" to "/work/kbang-1/jbang-jars",
                "JBANG_NO_VERSION_CHECK" to "true",
                "TMPDIR" to "/work/kbang-1/tmp",
                "JBANG_JAVA_OPTIONS" to "-Djava.io.tmpdir=/work/kbang-1/tmp"
            ),
            command.environment
        )
    }

    @Test
    fun `configured JBang JVM options are kept and the temp dir is appended`() {
        val command = jbangCommand(
            "jbang", BuildSpec(BuildTarget.JAR, "tool"), source, output, jarCache, tmpDir,
            offline = true, nativeOptions = emptyList(), environment = mapOf("JBANG_JAVA_OPTIONS" to "-Xmx1g")
        )

        assertEquals("-Xmx1g -Djava.io.tmpdir=/work/kbang-1/tmp", command.environment["JBANG_JAVA_OPTIONS"])
    }

    @Test
    fun `the native-image builder gets the temp dir after the configured options`() {
        val command = jbangCommand(
            "jbang", BuildSpec(BuildTarget.NATIVE, "tool"), source, output, jarCache, tmpDir,
            offline = false, nativeOptions = listOf("-J-Djava.io.tmpdir=/elsewhere"), environment = emptyMap()
        )

        // For JVM system properties the last one wins
        assertEquals(listOf("-N=-J-Djava.io.tmpdir=/elsewhere", "-N=-J-Djava.io.tmpdir=/work/kbang-1/tmp"), command.args.filter { it.startsWith("-N=") })
    }

    @Test
    fun `configured environment cannot redirect the jar cache or the temp dir`() {
        val command = jbangCommand(
            "jbang", BuildSpec(BuildTarget.NATIVE, "tool"), source, output, jarCache, tmpDir,
            offline = true, nativeOptions = emptyList(),
            environment = mapOf("JBANG_CACHE_DIR_JARS" to "/tmp/shared", "JBANG_NO_VERSION_CHECK" to "false", "TMPDIR" to "/tmp")
        )

        assertEquals("/work/kbang-1/jbang-jars", command.environment["JBANG_CACHE_DIR_JARS"])
        assertEquals("true", command.environment["JBANG_NO_VERSION_CHECK"])
        assertEquals("/work/kbang-1/tmp", command.environment["TMPDIR"])
    }
}
