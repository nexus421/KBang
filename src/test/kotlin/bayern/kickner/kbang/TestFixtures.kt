package bayern.kickner.kbang

import bayern.kickner.kbang.config.ApiKeyEntry
import bayern.kickner.kbang.config.AppConfig

/** API key of the `laptop` test client. */
internal const val LAPTOP_KEY = "laptop-key-0123456789abcdef"

/** SHA-256 of [LAPTOP_KEY], as it appears in the config. */
internal const val LAPTOP_KEY_SHA256 = "81dfcaa098f83acd4272a0454b928cee0b06ad15ee90b3f645a018e5818cb712"

/** API key of the `ci` test client. */
internal const val CI_KEY = "ci-key-0123456789abcdef"

/** SHA-256 of [CI_KEY], as it appears in the config. */
internal const val CI_KEY_SHA256 = "513c4c20fd830adc74a46da63006bb72609234a3a6c150d42325a0cd8a7d7bfd"

/** The two test clients `laptop` and `ci`. */
internal fun testApiKeys() = listOf(ApiKeyEntry("laptop", LAPTOP_KEY_SHA256), ApiKeyEntry("ci", CI_KEY_SHA256))

/** A valid config with every default. Tests derive variations with `copy(...)`. */
internal fun testConfig() = AppConfig(publicUrl = "https://kbang.example.com", apiKeys = testApiKeys())
