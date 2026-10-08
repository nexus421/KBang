# KBang

[![Tests](https://github.com/nexus421/KBang/actions/workflows/test.yml/badge.svg)](https://github.com/nexus421/KBang/actions/workflows/test.yml)
![Kotlin](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKBang%2Fmaster%2Fbuild.gradle.kts&search=kotlin%5C%28%22jvm%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Kotlin&logo=kotlin&logoColor=white&color=7F52FF)
![Ktor](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKBang%2Fmaster%2Fbuild.gradle.kts&search=id%5C%28%22io%5C.ktor%5C.plugin%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Ktor&logo=ktor&logoColor=white&color=087CFA)
![JDK](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKBang%2Fmaster%2Fbuild.gradle.kts&search=JavaLanguageVersion%5C.of%5C%28%28%5Cd%2B%29%5C%29&replace=%241%20%28Corretto%29&label=JDK&logo=openjdk&logoColor=white&color=ED8B00)

**K**otlin + **Bang**, like [JBang](https://www.jbang.dev): upload one Kotlin/JVM file, get back a static native binary or a fat JAR. KBang exists so
that small scripts and tools can be written in plain Kotlin instead of Bash or Python, while the servers that run
them need neither a JVM nor a compiler. A static binary from KBang runs on any Linux machine of the same CPU
architecture, it does not even need a libc.

KISS by design: no UI, no database, no job store. A build is synchronous: the client uploads, waits, receives the
file, and KBang deletes everything the build created. [JBang](https://www.jbang.dev) and GraalVM do the actual
work. A `curl | bash` one-liner makes the client side free of any local requirement. TLS termination is left to a
reverse proxy, KBang itself listens on plain HTTP, by default only on `127.0.0.1`.

## Quick start

Requirements: JDK 25 to build KBang (Amazon Corretto is the pinned toolchain, Gradle downloads it if missing). The
machine that runs KBang needs GraalVM, JBang and the musl toolchain, see Deployment.

```bash
cp config.example.json config.json       # then add an API key (see Command line) and adjust the paths
./gradlew run                            # development: reads ./config.json
./gradlew buildFatJar                    # production: build/libs/kbang.jar
java -jar build/libs/kbang.jar config=/path/to/config.json
```

On any client machine with `bash` and `curl`:

```bash
curl -fsSL https://kbang.example.com/kbang.sh | KBANG_KEY=<key> bash -s -- native tool.kt   # writes ./tool
curl -fsSL https://kbang.example.com/kbang.sh | KBANG_KEY=<key> bash -s -- jar tool.kt      # writes ./tool.jar
```

A native build takes a few minutes, a JAR a few seconds. To keep the client instead of piping it every time:

```bash
curl -fsSL https://kbang.example.com/kbang.sh -o ~/bin/kbang && chmod +x ~/bin/kbang
KBANG_KEY=<key> kbang native tool.kt [output]
```

Tests: `./gradlew test`. GitHub Actions runs them on every push (`.github/workflows/test.yml`). The real JBang and
GraalVM builds only run with `KBANG_IT=1 ./gradlew test`, see Behaviour.

## Command line

| Argument        | Default       | Description                                                                                   |
|-----------------|---------------|-----------------------------------------------------------------------------------------------|
| `config=<path>` | `config.json` | Config file to load (see Configuration).                                                      |
| `key`           |               | Prints a fresh API key (stdout) and its `apiKeys` entry (stderr), then exits. Needs no config. |
| `name=<name>`   | `client`      | With `key`: the client name for the printed entry.                                            |

```bash
java -jar build/libs/kbang.jar key name=laptop          # key on stdout, config entry on stderr
KEY=$(java -jar build/libs/kbang.jar key name=laptop)   # captures just the key, the entry still shows
```

The key goes to the client as `KBANG_KEY`. Only its SHA-256 goes into the config, so the config file does not reveal
usable keys. For an existing key: `printf %s "$KEY" | sha256sum`. Exit codes:

| Code  | Meaning                                                                                       |
|-------|-----------------------------------------------------------------------------------------------|
| `0`   | `key` printed.                                                                                |
| `1`   | The server could not start, e.g. the port is taken. systemd retries, see Deployment.          |
| `78`  | The config file is missing or invalid. The log lists every problem.                           |
| `143` | The JVM's exit code after SIGTERM (`systemctl stop`). A clean stop, not a failure.            |

The client script `kbang.sh` exits with `0` (file written), `1` (rejected by the server, build failed, server not
reachable) or `2` (wrong usage, `KBANG_KEY` missing, file name not usable as a name, output is a directory or the source itself). It needs `curl` 7.55 or newer
(2017), the key goes to curl through stdin so it never shows up in the process list.

## Configuration

One JSON file, read once at startup. Unknown keys are errors, every validation problem is reported at once, and neither key
hashes nor file content appear in error messages. `config.example.json` is versioned, `config.json` is gitignored.

| Field                 | Type           | Default                              | Description                                                                                         |
|-----------------------|----------------|--------------------------------------|-----------------------------------------------------------------------------------------------------|
| `listenHost`          | String         | `127.0.0.1`                          | Interface to bind. Keep the default behind a reverse proxy.                                         |
| `listenPort`          | Int            | `8080`                               | Port to listen on.                                                                                  |
| `publicUrl`           | String         | required                             | URL clients reach KBang under, baked into `/kbang.sh`. `http` or `https` with a host, only letters, digits and `. : / _ ~ % @ + - [ ]`. |
| `apiKeys`             | List           | required, at least one               | `{ "name": "...", "sha256": "..." }` per client. Names and keys must be unique.                     |
| `jbang`               | String         | `jbang`                              | JBang executable, a name on the build `PATH` or an absolute path (not a relative one).              |
| `environment`         | Map            | empty                                | Added to every build's environment. Typically `JAVA_HOME` (a GraalVM) and a `PATH` with musl.       |
| `offline`             | Boolean        | `false`                              | `jbang --offline`: only dependencies already in the local Maven repository resolve (see Behaviour). |
| `nativeOptions`       | List of String | `["-Ob", "--static", "--libc=musl"]` | Passed to `native-image` as `-N=<option>`. The default gives a static binary, `-Ob` builds faster. No commas: JBang splits there, repeat an option per value instead. |
| `maxUploadKb`         | Int            | `256`                                | Largest accepted source file. Bigger uploads get 413.                                               |
| `maxParallelBuilds`   | Int            | `1`                                  | Builds running at once. A native build takes every core and about 2 GB of memory.                   |
| `maxQueuedBuilds`     | Int            | `4`                                  | Builds that may wait for a free slot. Beyond that, 503.                                             |
| `buildTimeoutMinutes` | Int            | `10`                                 | A longer build is killed and answered with 422.                                                     |
| `maxLogKb`            | Int            | `64`                                 | Only the tail of a build log up to this size reaches the client. At most `10240`.                   |
| `workDir`             | String         | `<java.io.tmpdir>/kbang`              | Parent of the per-build workspaces. Leftovers from a crash are deleted at startup. An absolute path without whitespace or commas. |

## Endpoints

### `POST /build/native` and `POST /build/jar`

The request body is the raw source file. Header `X-API-Key: <key>` (never as a query parameter, URLs end up in
proxy logs). Query parameters:

| Parameter | Default  | Description                                                                                                         |
|-----------|----------|---------------------------------------------------------------------------------------------------------------------|
| `name`    | `script` | Name of the source file (`<name>.kt`) and the artifact. `^[A-Za-z][A-Za-z0-9_-]{0,63}$`. The client sends the file name. |
| `arch`    | host     | Target architecture, normally the client's `uname -m`. Must be the server's own, see Behaviour. Ignored for `jar`.  |

Success is `200` with the artifact as `application/octet-stream` (`application/java-archive` for a JAR) and a
`Content-Disposition` attachment named `<name>` or `<name>.jar`. Everything else is `text/plain`:

| Status | Meaning                                                                                       |
|--------|-----------------------------------------------------------------------------------------------|
| `400`  | Empty body, invalid `name`, unknown architecture or one this server cannot build for.         |
| `401`  | Missing or unknown API key. Checked before the body is read.                                  |
| `404`  | Unknown target, anything but `native` and `jar`.                                              |
| `413`  | Body larger than `maxUploadKb`, also for chunked uploads.                                     |
| `422`  | The build failed (compile error) or timed out. The body is the tail of the build log, for a failed native-image run followed by native-image's own log, where its error is. |
| `500`  | The build could not be started, e.g. JBang is missing. The server log has the details.        |
| `503`  | The build queue is full. Try again later.                                                     |

```bash
curl -X POST "http://127.0.0.1:8080/build/native?arch=$(uname -m)&name=tool" \
  -H "X-API-Key: <key>" --data-binary @tool.kt -o tool && chmod +x tool
```

### `GET /kbang.sh`

The Bash client with `publicUrl` baked in. Unauthenticated, it holds no secret. `KBANG_URL` overrides the server,
`KBANG_ARCH` the architecture (see Behaviour).

### `GET /health`

Unauthenticated liveness check. Answers `ok` as long as the process runs.

## Behaviour

- **One build per request, nothing kept.** Each build gets a fresh workspace below `workDir` with the source as
  `<name>.kt`, its output, its own JBang build cache (`JBANG_CACHE_DIR_JARS`) and its own temp dir. `jbang export`
  ignores `--build-dir`, and its shared cache is keyed by file name and content only, so it would hand back a binary
  built with other `nativeOptions` and grow with every build. The Kotlin compiler and the Maven repository stay
  shared. The temp dir takes what JBang, native-image and the linker would otherwise leave in `/tmp` (JBang never
  deletes its native-image log, a killed native-image leaves its `SVM-*` directory). The workspace is deleted as
  soon as the reply is sent, also on failure.
- **Client gone, build gone.** Every build runs as its own process group (`setsid`). When the client disconnects,
  Ktor cancels the call (the `HttpRequestLifecycle` plugin with `cancelCallOnClose`) and KBang stops the build from
  the inside out: SIGTERM to the innermost processes (the native-image builder JVM, gcc while linking), so their
  parents end in order, then SIGKILL to the whole group after 5 s at the latest. The native-image driver itself
  never gets a signal, it removes its `/tmp/driverRoot-*` directory only when it ends by itself. Should it be
  killed, KBang removes that directory. The group is stopped after every build anyway, so not even a background
  process of a build survives. This works for HTTP/1.1 clients like `curl`. A client that sends `Connection: close`
  is only stopped by `buildTimeoutMinutes`.
- **Queue.** At most `maxParallelBuilds` builds run, up to `maxQueuedBuilds` wait, everyone else gets 503 at once.
  A slot is held until the artifact has been sent, so a client that downloads slowly holds it a little longer.
- **Architecture.** GraalVM cannot cross-compile, a server builds Linux binaries for its own CPU only. `arch` is
  normalized (`x86_64`, `amd64`, `x64` and `aarch64`, `arm64`) and must match, so nobody gets a binary for the
  wrong CPU by accident. The client sends its own `uname -m`. To build on a laptop for a server of another
  architecture than the laptop's, set `KBANG_ARCH` to the server's (and the target must be Linux). A JAR runs
  everywhere.
- **Dependencies.** Scripts may use `//DEPS` lines (JBang), which JBang downloads from Maven Central on first use
  and keeps in the local Maven repository. The same goes for the Kotlin compiler (JBang's default version, or the
  one a script asks for with `//KOTLIN <version>`), so the first build after the setup takes longer. Every client
  with a key is trusted, so KBang does not restrict dependencies. With `offline` set, only what is already in the
  local Maven repository resolves, including the Kotlin compiler. A library that relies on reflection may need
  native-image configuration to work natively.
- **Certificates of a native binary.** A native image carries the CA certificates of the GraalVM it was built
  with. A binary that talks to services with an internal CA needs `-Djavax.net.ssl.trustStore=<cacerts>` at run time.
- **Logging.** Klogger on the console (the systemd journal), Ktor's own output through Klogger's SLF4J bridge at
  WARN. Every build is logged with client name, artifact, outcome and duration. A rejected API key is logged with
  the client address (`X-Forwarded-For` when the proxy sets it). API keys are never logged.
- **Tests.** `./gradlew test` runs everything without GraalVM in seconds, with fake `jbang` scripts for the process
  handling. `KBANG_IT=1 ./gradlew test` adds real builds (`KBANG_IT_JBANG`, `KBANG_IT_OFFLINE=1` optional), which
  need JBang, GraalVM as `JAVA_HOME` and musl on the `PATH`.

## Deployment

A Debian or Ubuntu machine, x86_64, ideally a dedicated container (for example an Incus LXC), because a build runs
whatever the uploaded file and its dependencies do at compile time. The API keys only let trusted clients in.

```bash
# Tools: setsid comes with util-linux, file is only for checking results
apt install curl unzip util-linux procps file gcc make zlib1g-dev musl-tools

# GraalVM CE 25 (also runs KBang itself) and JBang
curl -fsSLo /tmp/graalvm.tar.gz https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-25.0.2/graalvm-community-jdk-25.0.2_linux-x64_bin.tar.gz
mkdir -p /opt/graalvm && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1
curl -fsSLo /tmp/jbang.zip https://github.com/jbangdev/jbang/releases/latest/download/jbang.zip
unzip -q /tmp/jbang.zip -d /opt

# musl toolchain for static binaries: native-image expects x86_64-linux-musl-gcc and a zlib built against musl
mkdir -p /opt/musl/bin && ln -sf /usr/bin/musl-gcc /opt/musl/bin/x86_64-linux-musl-gcc
curl -fsSLo /tmp/zlib.tar.gz https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz
mkdir -p /tmp/zlib && tar -xzf /tmp/zlib.tar.gz -C /tmp/zlib --strip-components=1
(cd /tmp/zlib && CC=musl-gcc ./configure --static --prefix=/tmp/zlib-musl && make && make install)
cp /tmp/zlib-musl/lib/libz.a /usr/lib/x86_64-linux-musl/
cp /tmp/zlib-musl/include/zlib.h /tmp/zlib-musl/include/zconf.h /usr/include/x86_64-linux-musl/

# Service user, its home holds ~/.jbang, ~/.m2 and the workspaces
useradd --system --create-home --home-dir /var/lib/kbang --shell /usr/sbin/nologin kbang

# KBang itself
mkdir -p /opt/kbang /etc/kbang
cp build/libs/kbang.jar /opt/kbang/
cp config.example.json /etc/kbang/config.json      # then fill it in
chown root:kbang /etc/kbang/config.json && chmod 640 /etc/kbang/config.json
# One API key per client: the key goes to the client, the printed entry into apiKeys (KBang needs Java 25)
/opt/graalvm/bin/java -jar /opt/kbang/kbang.jar key name=laptop

cp kbang.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now kbang
journalctl -u kbang -f
```

Then check the deployment from any client with the real one-liner (native and JAR build, 401, 422, 400). On the
KBang host itself, `KBANG_WORKDIR` adds a client that disconnects mid-build and a check that nothing was left behind
(see the script's header for `KBANG_TMP` under `PrivateTmp`):

```bash
KBANG_KEY=<key> deploy/smoke-test.sh https://kbang.example.com
```

`config.example.json` already points `jbang`, `JAVA_HOME` and `PATH` at these locations and `workDir` at
`/var/lib/kbang/work`. Put the reverse proxy in front of `127.0.0.1:8080`. Two settings of the proxy matter:

- **Timeout.** A native build keeps the request open for minutes without sending a byte, so the proxy must not cut
  the upstream request earlier. Raise its read or response timeout to more than `buildTimeoutMinutes`.
- **Keep-alive to KBang.** KBang notices a disconnected client only on a keep-alive (HTTP/1.1) connection, so the
  proxy must talk HTTP/1.1 with keep-alive to KBang and close that connection when its client goes away. Caddy and
  Go based proxies such as Zoraxy should do both by default (Go's reverse proxy keeps upstream connections alive
  and cancels the upstream request when its client goes away). nginx does not: add `proxy_http_version 1.1;` and
  `proxy_set_header Connection "";`. Otherwise a build whose client is gone keeps running until
  `buildTimeoutMinutes` and holds its slot. The smoke test's disconnect check (with `KBANG_WORKDIR`, through the
  proxy URL) verifies the whole chain.

`kbang.service` runs KBang as the `kbang` user with a read-only system, restarts it on failure, does not loop on a
broken config (exit code 78) and kills every running build when the service stops.

**ARM (aarch64):** use the `linux-aarch64` GraalVM archive. Static musl binaries are documented for x86_64 and were
not supported on aarch64 for a long time. If `--libc=musl` fails there, set `nativeOptions` to
`["-Ob", "--static-nolibc"]` (static except glibc) and build on the oldest glibc your targets have. Not tested yet.

### Docker

The `Dockerfile` is the same setup as above in one image (x86_64 only): GraalVM, JBang, the musl toolchain and
`kbang.jar`, at the paths `config.example.json` expects. Nothing but Docker is needed on the host.

```bash
docker build -t kbang .

# One API key per client: the key goes to the client, the printed entry into apiKeys of the config
docker run --rm kbang key name=laptop

# config.json: a copy of config.example.json with "listenHost": "0.0.0.0" (the container's own interface,
# the port is published to the host's loopback below), your publicUrl and the apiKeys entry
docker run -d --name kbang --init --restart on-failure:5 \
  --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
  -v kbang-data:/var/lib/kbang -v "$PWD/config.json:/etc/kbang/config.json:ro" \
  -p 127.0.0.1:8080:8080 kbang
```

- **Volume.** `/var/lib/kbang` holds the JBang cache, the local Maven repository and the workspaces. Without the
  volume every new container downloads the Kotlin compiler and all dependencies again.
- **`--init`.** KBang stops builds through their process groups and needs an init process that reaps the orphans.
- **`--read-only` and `--tmpfs /tmp`.** The same as `ProtectSystem=strict` and `PrivateTmp` in `kbang.service`.
  The native-image driver keeps its `/tmp/driverRoot-*` there, which also disappears with the container.
- **Memory.** native-image needs a few GB. A `--memory` limit that is too low ends a build with an out-of-memory
  kill, which shows up as a failed build.
- **Smoke test.** The script is part of the image, so the check including the leftovers runs in the container:
  `docker exec -u kbang -e KBANG_KEY=<key> -e JAVA_HOME=/opt/graalvm -e KBANG_WORKDIR=/var/lib/kbang/work kbang bash /opt/kbang/smoke-test.sh <publicUrl>`.
  GitHub Actions does this on every change that can affect the image (`.github/workflows/docker.yml`).
- **Proxy and ARM.** The notes above apply unchanged. For ARM the `Dockerfile` needs the `linux-aarch64` GraalVM
  archive and the musl paths of that architecture, which is not tested.

## Not in scope (deliberately)

Asynchronous jobs, a result cache, several source files per build, cross-compilation for another architecture, a
web UI, per-key rate limits.
