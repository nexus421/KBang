package bayern.kickner.kbang.config

import bayern.kickner.kbang.LAPTOP_KEY_SHA256
import kotnexlib.ResultOf2
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigLoaderTest {

    private val dir = createTempDirectory("kbang-config-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun write(json: String): String = File(dir, "config.json").apply { writeText(json) }.absolutePath

    private val minimal = """
        {
          "publicUrl": "https://kbang.example.com",
          "apiKeys": [ { "name": "laptop", "sha256": "$LAPTOP_KEY_SHA256" } ]
        }
    """.trimIndent()

    @Test
    fun `minimal file gets every default`() {
        val result = loadConfig(write(minimal))

        val config = assertIs<ResultOf2.Success<AppConfig>>(result).value
        assertEquals(AppConfig(publicUrl = "https://kbang.example.com", apiKeys = listOf(ApiKeyEntry("laptop", LAPTOP_KEY_SHA256))), config)
    }

    @Test
    fun `every field can be set`() {
        val json = """
            {
              "listenHost": "0.0.0.0",
              "listenPort": 9090,
              "publicUrl": "https://kbang.example.com/",
              "apiKeys": [ { "name": "laptop", "sha256": "$LAPTOP_KEY_SHA256" } ],
              "jbang": "/opt/jbang/bin/jbang",
              "environment": { "JAVA_HOME": "/opt/graalvm" },
              "offline": true,
              "nativeOptions": [ "-Ob" ],
              "maxUploadKb": 64,
              "maxParallelBuilds": 2,
              "maxQueuedBuilds": 0,
              "buildTimeoutMinutes": 5,
              "maxLogKb": 16,
              "workDir": "/var/lib/kbang/work"
            }
        """.trimIndent()

        val config = assertIs<ResultOf2.Success<AppConfig>>(loadConfig(write(json))).value

        assertEquals(9090, config.listenPort)
        assertEquals(mapOf("JAVA_HOME" to "/opt/graalvm"), config.environment)
        assertEquals(true, config.offline)
        assertEquals("/var/lib/kbang/work", config.workDir)
        assertEquals("https://kbang.example.com", config.baseUrl)
    }

    @Test
    fun `missing file is reported with its path`() {
        val result = loadConfig(File(dir, "nope.json").absolutePath)

        val message = assertIs<ResultOf2.Failure<String>>(result).value
        assertTrue(message.contains("not found") && message.contains("nope.json"), message)
    }

    @Test
    fun `broken json is a parse error`() {
        val message = assertIs<ResultOf2.Failure<String>>(loadConfig(write("{ not json"))).value

        assertTrue(message.contains("could not be parsed"), message)
    }

    @Test
    fun `unknown keys are errors and name the key`() {
        val json = minimal.replace("\"publicUrl\"", "\"lisenPort\": 1, \"publicUrl\"")

        val message = assertIs<ResultOf2.Failure<String>>(loadConfig(write(json))).value

        assertTrue(message.contains("lisenPort"), message)
    }

    @Test
    fun `parse errors never echo the file content`() {
        val json = minimal.replace("\"apiKeys\"", "\"listenPort\": \"eighty\", \"apiKeys\"")

        val message = assertIs<ResultOf2.Failure<String>>(loadConfig(write(json))).value

        assertTrue(message.contains(LAPTOP_KEY_SHA256).not(), message)
    }

    @Test
    fun `validation problems are all listed`() {
        val json = minimal.replace("\"apiKeys\"", "\"listenPort\": 0, \"maxLogKb\": 0, \"apiKeys\"")

        val message = assertIs<ResultOf2.Failure<String>>(loadConfig(write(json))).value

        assertTrue(message.contains("listenPort") && message.contains("maxLogKb"), message)
        assertTrue(message.contains(LAPTOP_KEY_SHA256).not(), message)
    }
}
