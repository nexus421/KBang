# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

KBang is a small Ktor service: upload one Kotlin file, get back a static native binary or a fat JAR, built
synchronously with JBang and GraalVM. [README.md](README.md) documents behaviour, configuration, endpoints and
deployment, [docs/superpowers/specs/2026-10-07-kbang-design.md](docs/superpowers/specs/2026-10-07-kbang-design.md)
the design decisions and why they were made.

It follows the conventions of [DemoAiProject](https://github.com/nexus421/DemoAiProject) (read its README before
touching library-related code) and is structured like [KNot](https://github.com/nexus421/KNot).

## Commands

Use the Gradle wrapper (`./gradlew`), not a system-installed Gradle.

```bash
./gradlew test                 # everything except real builds, seconds
KBANG_IT=1 ./gradlew test       # plus real JBang and GraalVM builds, minutes (needs JBang, GraalVM, musl)
./gradlew buildFatJar          # build/libs/kbang.jar
./gradlew run                  # reads ./config.json
```

## Structure

Everything except `routes/` and `Main.kt` is free of Ktor:

- `config`: `AppConfig` (serializable, defaults), `validate`, `loadConfig`.
- `auth`: key generation, SHA-256, constant-time lookup of the caller.
- `build`: request validation, the `setsid jbang export ...` command, the process-group runner, the
  per-build workspace (`JbangBuilder`), the build queue.
- `service`: `BuildService`, the whole request flow behind the `BuildCall` interface, and the client script.
- `routes`: Ktor adapters. `Main.kt`: wiring, `HttpRequestLifecycle` (cancel on disconnect), `RequestBodyLimit`.

The client script lives in `src/main/resources/kbang.sh` with `__KBANG_URL__` as placeholder.

## Invariants worth protecting

- Nothing of a build survives the request: the process group is stopped after every run and the workspace is
  deleted in a `finally`. `ProcessRunnerTest` and `BuildRunnerTest` pin this with real processes,
  `JbangIntegrationTest` with real native-image runs (also for `/tmp`).
- Every temporary file of a build goes to `<workspace>/tmp` (`TMPDIR`, `JBANG_JAVA_OPTIONS`,
  `-N=-J-Djava.io.tmpdir`), see `JbangCommand.kt`. The only exception is the native-image driver's
  `/tmp/driverRoot-*`, which is hard-coded in GraalVM.
- A build is stopped from the inside out (SIGTERM to the leaves of the process tree, SIGKILL to the group after the
  grace period) and the native-image driver never gets a signal, because it only removes its `driverRoot-*` when it
  ends by itself. A SIGTERM to the whole group looks simpler but leaves that directory behind. See
  `stopProcessGroup` in `ProcessRunner.kt`.
- `HttpRequestLifecycle { cancelCallOnClose = true }` must stay installed. Ktor does not cancel a call on
  disconnect without it. `RoutesTest` pins it with a real CIO server and a raw socket.
- `JBANG_CACHE_DIR_JARS` must point into the workspace (see `JbangCommand.kt` for why).
- Auth happens before the body is read.

## deploy/

`smoke-test.sh` checks a running KBang through the real client one-liner, and on the KBang host also that nothing
is left behind.

Keep README.md and KDoc in sync with the code in the same change.

## Text rules

Code, KDoc, comments, log messages and docs are in English. No em dashes, no en dashes and no semicolons in any
text (KDoc, comments, strings, README, tests). Rephrase with two sentences, a colon, a comma or parentheses.
Every public declaration has KDoc.
