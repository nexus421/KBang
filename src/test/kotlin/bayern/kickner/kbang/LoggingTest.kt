package bayern.kickner.kbang

import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class LoggingTest {

    /**
     * Klogger registers itself as SLF4J provider through `META-INF/services`. The tag `v0.3.0` of its repository
     * misses that file (added one commit later), so this pins that the published artifact has it. Without it SLF4J
     * falls back to a no-op logger and every warning of Ktor is silently lost. Fix then: a newer Klogger, or
     * `runtimeOnly("org.slf4j:slf4j-simple")` like KNot.
     */
    @Test
    fun `Ktor's SLF4J output goes to Klogger`() {
        val factory = LoggerFactory.getILoggerFactory()::class.java.name

        assertTrue(factory.startsWith("bayern.kickner.klogger"), "SLF4J logs through $factory instead of Klogger")
    }
}
