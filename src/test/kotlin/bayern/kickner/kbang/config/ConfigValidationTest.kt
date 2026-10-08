package bayern.kickner.kbang.config

import bayern.kickner.kbang.LAPTOP_KEY_SHA256
import bayern.kickner.kbang.testApiKeys
import bayern.kickner.kbang.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigValidationTest {

    @Test
    fun `the test config is valid`() {
        assertEquals(emptyList(), validate(testConfig()))
    }

    @Test
    fun `defaults match the spec`() {
        val config = testConfig()

        assertEquals("127.0.0.1", config.listenHost)
        assertEquals(8080, config.listenPort)
        assertEquals("jbang", config.jbang)
        assertEquals(emptyMap(), config.environment)
        assertEquals(false, config.offline)
        assertEquals(listOf("-Ob", "--static", "--libc=musl"), config.nativeOptions)
        assertEquals(256, config.maxUploadKb)
        assertEquals(1, config.maxParallelBuilds)
        assertEquals(4, config.maxQueuedBuilds)
        assertEquals(10, config.buildTimeoutMinutes)
        assertEquals(64, config.maxLogKb)
        assertEquals(System.getProperty("java.io.tmpdir") + "/kbang", config.workDir)
    }

    @Test
    fun `listenPort outside the port range is rejected`() {
        assertSingleIssue(testConfig().copy(listenPort = 0), "listenPort")
        assertSingleIssue(testConfig().copy(listenPort = 70000), "listenPort")
    }

    @Test
    fun `blank listenHost is rejected`() {
        assertSingleIssue(testConfig().copy(listenHost = " "), "listenHost")
    }

    @Test
    fun `publicUrl needs an http or https scheme and a host`() {
        assertSingleIssue(testConfig().copy(publicUrl = "kbang.example.com"), "publicUrl")
        assertSingleIssue(testConfig().copy(publicUrl = "ftp://kbang.example.com"), "publicUrl")
        assertSingleIssue(testConfig().copy(publicUrl = "https://"), "publicUrl")
        assertEquals(emptyList(), validate(testConfig().copy(publicUrl = "http://10.0.0.5:8080/kbang/")))
    }

    @Test
    fun `at least one api key is required`() {
        assertSingleIssue(testConfig().copy(apiKeys = emptyList()), "apiKeys")
    }

    @Test
    fun `sha256 must be 64 lowercase hex characters`() {
        assertSingleIssue(testConfig().copy(apiKeys = listOf(ApiKeyEntry("laptop", "abc"))), "sha256")
        assertSingleIssue(testConfig().copy(apiKeys = listOf(ApiKeyEntry("laptop", LAPTOP_KEY_SHA256.uppercase()))), "sha256")
    }

    @Test
    fun `blank key name is rejected`() {
        assertSingleIssue(testConfig().copy(apiKeys = listOf(ApiKeyEntry(" ", LAPTOP_KEY_SHA256))), "name")
    }

    @Test
    fun `duplicate key names are rejected`() {
        val keys = listOf(ApiKeyEntry("laptop", LAPTOP_KEY_SHA256), ApiKeyEntry("laptop", "0".repeat(64)))
        assertSingleIssue(testConfig().copy(apiKeys = keys), "laptop")
    }

    @Test
    fun `duplicate key hashes are rejected without echoing the hash`() {
        val keys = listOf(ApiKeyEntry("laptop", LAPTOP_KEY_SHA256), ApiKeyEntry("desktop", LAPTOP_KEY_SHA256))

        val issue = assertSingleIssue(testConfig().copy(apiKeys = keys), "desktop")

        assertTrue(issue.contains(LAPTOP_KEY_SHA256).not(), issue)
    }

    @Test
    fun `blank jbang is rejected`() {
        assertSingleIssue(testConfig().copy(jbang = ""), "jbang")
    }

    @Test
    fun `blank native option is rejected`() {
        assertSingleIssue(testConfig().copy(nativeOptions = listOf("-Ob", " ")), "nativeOptions")
    }

    @Test
    fun `native option with a comma is rejected because JBang would split it`() {
        val issues = validate(testConfig().copy(nativeOptions = listOf("--initialize-at-build-time=a.A,b.B")))

        assertEquals(1, issues.size, issues.toString())
        assertTrue(issues.single().contains("nativeOptions") && issues.single().contains("comma"), issues.single())
    }

    @Test
    fun `limits must be positive`() {
        assertSingleIssue(testConfig().copy(maxUploadKb = 0), "maxUploadKb")
        assertSingleIssue(testConfig().copy(maxParallelBuilds = 0), "maxParallelBuilds")
        assertSingleIssue(testConfig().copy(buildTimeoutMinutes = 0), "buildTimeoutMinutes")
        assertSingleIssue(testConfig().copy(maxLogKb = 0), "maxLogKb")
    }

    @Test
    fun `an empty queue is allowed but a negative one is not`() {
        assertEquals(emptyList(), validate(testConfig().copy(maxQueuedBuilds = 0)))
        assertSingleIssue(testConfig().copy(maxQueuedBuilds = -1), "maxQueuedBuilds")
    }

    @Test
    fun `blank workDir is rejected`() {
        assertSingleIssue(testConfig().copy(workDir = ""), "workDir")
    }

    @Test
    fun `workDir with whitespace is rejected because JBang splits its JVM options at spaces`() {
        assertSingleIssue(testConfig().copy(workDir = "/var/lib/kbang/my work"), "workDir")
    }

    @Test
    fun `workDir with a comma is rejected because JBang splits native options there`() {
        assertSingleIssue(testConfig().copy(workDir = "/var/lib/kbang/a,b"), "workDir")
    }

    @Test
    fun `relative workDir is rejected because the build runs inside the workspace`() {
        assertTrue(assertSingleIssue(testConfig().copy(workDir = "work"), "workDir").contains("absolute"))
    }

    @Test
    fun `jbang is a bare name or an absolute path`() {
        assertEquals(emptyList(), validate(testConfig().copy(jbang = "jbang")))
        assertEquals(emptyList(), validate(testConfig().copy(jbang = "/opt/jbang/bin/jbang")))
        assertSingleIssue(testConfig().copy(jbang = "bin/jbang"), "jbang")
    }

    @Test
    fun `maxLogKb has an upper bound`() {
        assertEquals(emptyList(), validate(testConfig().copy(maxLogKb = 10_240)))
        assertSingleIssue(testConfig().copy(maxLogKb = 10_241), "maxLogKb")
    }

    @Test
    fun `publicUrl may be an IPv6 literal or have a port and a path`() {
        listOf("http://[::1]:8080", "https://kbang.example.com:8443/kbang/", "https://user@kbang.example.com").forEach { url ->
            assertEquals(emptyList(), validate(testConfig().copy(publicUrl = url)), url)
        }
    }

    @Test
    fun `publicUrl must not contain characters the client script would run`() {
        listOf("https://kbang.example.com/\$(id)", "https://kbang.example.com/`id`", "https://kbang.example.com/\"x", "https://kbang.example.com/'x").forEach { url ->
            assertSingleIssue(testConfig().copy(publicUrl = url), "publicUrl")
        }
    }

    @Test
    fun `every problem is reported at once`() {
        val config = testConfig().copy(listenPort = 0, apiKeys = emptyList(), maxLogKb = 0)

        assertEquals(3, validate(config).size)
    }

    @Test
    fun `baseUrl drops trailing slashes`() {
        assertEquals("https://k.example.com", testConfig().copy(publicUrl = "https://k.example.com/").baseUrl)
        assertEquals("https://k.example.com/kbang", testConfig().copy(publicUrl = "https://k.example.com/kbang").baseUrl)
    }

    @Test
    fun `toString never shows the key hashes`() {
        assertTrue(testConfig().toString().contains(LAPTOP_KEY_SHA256).not())
        assertTrue(testApiKeys().first().toString().contains(LAPTOP_KEY_SHA256).not())
    }

    private fun assertSingleIssue(config: AppConfig, mentioning: String): String {
        val issues = validate(config)
        assertEquals(1, issues.size, "expected exactly one issue, got $issues")
        assertTrue(issues.single().contains(mentioning), "issue should mention '$mentioning': ${issues.single()}")
        return issues.single()
    }
}
