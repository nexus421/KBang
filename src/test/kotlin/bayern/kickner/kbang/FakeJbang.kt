package bayern.kickner.kbang

import java.io.File

/**
 * A stand-in for the `jbang` executable, so process and build tests run in milliseconds without GraalVM.
 *
 * It reads `-O <output>` and the source path (last argument) like JBang does. Everything else is controlled by
 * environment variables, which the build passes through from `BuildSettings.environment`:
 *
 * - `FAKE_SELF_PID_FILE`: write the script's own pid there.
 * - `FAKE_CHILD_PID_FILE`: start a background `sleep 1000` and write its pid there (a straggler to be killed).
 * - `FAKE_PRINT_MB`: print that many MiB of `x`, then a line `LOG-END`.
 * - `FAKE_MESSAGE`: print this line.
 * - `FAKE_SLEEP`: sleep that many seconds before finishing. The script waits for a background `sleep`, so a
 *   trapped signal interrupts it at once like it would interrupt a real JVM.
 * - `FAKE_CLEANUP_FILE`: write `cleaned up` there once the background `sleep` of `FAKE_SLEEP` has ended, then exit 1.
 *   This models the native-image driver: it removes its temporary directory when its builder JVM ends, but a
 *   SIGTERM to the driver itself ends it on the spot, without cleaning up.
 * - `FAKE_IGNORE_TERM`: `1` ignores SIGTERM, so only SIGKILL ends the script and its children.
 * - `FAKE_DRIVER_DIR`: behave like the native-image driver. Create `driverRoot-<pid>` in that directory, stay
 *   without children for `FAKE_DRIVER_DELAY` seconds (default 0, busy waiting with shell builtins only), then
 *   start a "builder" whose command line names `@<driverRoot>/vminvocation.args`, wait for it, remove the
 *   driverRoot and exit 1. A signal to the script itself ends it without removing the driverRoot.
 * - `FAKE_TMP_FILE`: create this file name in `$TMPDIR`, like native-image creating its temporary directories.
 * - `FAKE_NATIVE_LOG`: write this text to `$TMPDIR/jbang4711native-image`, where JBang keeps the native-image log.
 * - `FAKE_WRITE_ARTIFACT`: `0` skips writing the output file. Otherwise it contains `artifact from <source name>`.
 * - `FAKE_EXIT`: exit code, default 0.
 *
 * It always prints its arguments and the values of `JBANG_CACHE_DIR_JARS`, `TMPDIR` and `JBANG_JAVA_OPTIONS`.
 */
private val SCRIPT = """
    |#!/usr/bin/env bash
    |out=""
    |prev=""
    |for a in "${'$'}@"
    |do
    |  if [ "${'$'}prev" = "-O" ]
    |  then
    |    out="${'$'}a"
    |  fi
    |  prev="${'$'}a"
    |done
    |src="${'$'}{@: -1}"
    |echo "fake jbang ${'$'}*"
    |echo "cache=${'$'}{JBANG_CACHE_DIR_JARS:-unset}"
    |echo "tmp=${'$'}{TMPDIR:-unset}"
    |echo "jbangopts=${'$'}{JBANG_JAVA_OPTIONS:-unset}"
    |if [ "${'$'}{FAKE_IGNORE_TERM:-0}" = "1" ]
    |then
    |  trap '' TERM
    |fi
    |if [ -n "${'$'}{FAKE_TMP_FILE:-}" ]
    |then
    |  mkdir -p "${'$'}TMPDIR"
    |  echo "temp" > "${'$'}TMPDIR/${'$'}FAKE_TMP_FILE"
    |fi
    |if [ -n "${'$'}{FAKE_NATIVE_LOG:-}" ]
    |then
    |  mkdir -p "${'$'}TMPDIR"
    |  echo "${'$'}FAKE_NATIVE_LOG" > "${'$'}TMPDIR/jbang4711native-image"
    |fi
    |if [ -n "${'$'}{FAKE_SELF_PID_FILE:-}" ]
    |then
    |  echo ${'$'}${'$'} > "${'$'}FAKE_SELF_PID_FILE"
    |fi
    |if [ -n "${'$'}{FAKE_DRIVER_DIR:-}" ]
    |then
    |  root="${'$'}FAKE_DRIVER_DIR/driverRoot-${'$'}${'$'}"
    |  mkdir -p "${'$'}root"
    |  end=${'$'}((SECONDS + ${'$'}{FAKE_DRIVER_DELAY:-0}))
    |  while [ ${'$'}SECONDS -lt ${'$'}end ]
    |  do
    |    :
    |  done
    |  bash -c 'sleep 30 & wait' "@${'$'}root/vminvocation.args" &
    |  wait ${'$'}!
    |  rm -rf "${'$'}root"
    |  exit 1
    |fi
    |if [ -n "${'$'}{FAKE_CHILD_PID_FILE:-}" ]
    |then
    |  sleep 1000 &
    |  echo ${'$'}! > "${'$'}FAKE_CHILD_PID_FILE"
    |fi
    |if [ -n "${'$'}{FAKE_PRINT_MB:-}" ]
    |then
    |  head -c ${'$'}((FAKE_PRINT_MB * 1024 * 1024)) /dev/zero | tr '\0' 'x'
    |  echo
    |  echo "LOG-END"
    |fi
    |if [ -n "${'$'}{FAKE_MESSAGE:-}" ]
    |then
    |  echo "${'$'}FAKE_MESSAGE"
    |fi
    |if [ -n "${'$'}{FAKE_SLEEP:-}" ]
    |then
    |  sleep "${'$'}FAKE_SLEEP" &
    |  wait ${'$'}!
    |  if [ -n "${'$'}{FAKE_CLEANUP_FILE:-}" ]
    |  then
    |    echo "cleaned up" > "${'$'}FAKE_CLEANUP_FILE"
    |    exit 1
    |  fi
    |fi
    |if [ "${'$'}{FAKE_WRITE_ARTIFACT:-1}" = "1" ] && [ -n "${'$'}out" ]
    |then
    |  mkdir -p "${'$'}(dirname "${'$'}out")"
    |  printf 'artifact from %s' "${'$'}(basename "${'$'}src")" > "${'$'}out"
    |fi
    |exit "${'$'}{FAKE_EXIT:-0}"
    |""".trimMargin()

/** Writes the fake `jbang` into [dir] and returns it, executable. */
internal fun fakeJbang(dir: File): File = File(dir, "fake-jbang").apply {
    writeText(SCRIPT)
    setExecutable(true)
}

/** True while a process with [pid] exists and is not a zombie. Linux only, reads `/proc`. */
internal fun isRunning(pid: Long): Boolean {
    val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return false
    val state = stat.substringAfterLast(") ").firstOrNull()
    return state != null && state != 'Z' && state != 'X'
}

/** Polls [condition] every 20 ms for up to [timeoutMs] and returns whether it became true. */
internal fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(20)
    }
    return condition()
}

/** Waits for [file] to exist with content and returns that content as a pid. */
internal fun awaitPid(file: File): Long {
    check(eventually { file.isFile && file.readText().isNotBlank() }) { "pid file ${file.name} never appeared" }
    return file.readText().trim().toLong()
}
