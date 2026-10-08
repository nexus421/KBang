package bayern.kickner.kbang.build

/** Every spelling of a CPU architecture KBang knows, mapped to the `uname -m` name it uses itself. */
private val ARCH_ALIASES = mapOf(
    "x86_64" to "x86_64", "amd64" to "x86_64", "x64" to "x86_64",
    "aarch64" to "aarch64", "arm64" to "aarch64"
)

/**
 * The canonical name for [raw] (`x86_64` or `aarch64`), or null when the architecture is unknown. Clients send
 * `uname -m`, the JVM reports `os.arch`, and the two spell the same CPU differently (`x86_64` and `amd64`).
 */
fun normalizeArch(raw: String): String? = ARCH_ALIASES[raw.trim().lowercase()]

/**
 * The architecture of this machine, which is the only one KBang can build native binaries for.
 *
 * @param osArch The JVM's `os.arch`, injectable for tests. Kept as reported when KBang does not know it.
 */
fun hostArch(osArch: String = System.getProperty("os.arch")): String = normalizeArch(osArch) ?: osArch.trim().lowercase()
