#!/usr/bin/env bash
# Smoke test for a running KBang, through the client one-liner exactly like a user runs it.
#
#   KBANG_KEY=<key> deploy/smoke-test.sh https://kbang.example.com
#
# Builds a native binary and a fat JAR (both must run), and checks that a wrong key answers 401, a compile error
# answers 422 with the log, and a foreign architecture answers 400. A native build takes minutes.
#
# Run on the KBang host itself with KBANG_WORKDIR=<workDir of the config> and it also checks that nothing of a build
# survives: no workspace, no build process, no temporary file of JBang or native-image in /tmp, also not after a
# client that disconnects in the middle of a native build. With systemd's PrivateTmp, KBang's /tmp is not the /tmp
# of this shell. As root, point KBANG_TMP at it, KBANG_TMP=$(echo /tmp/systemd-private-*-kbang.service-*/tmp), or
# skip that check with KBANG_TMP=-.
#
# Exit code 0 when every check passed, 1 at the first failure.
set -euo pipefail

URL=${1:?Usage: KBANG_KEY=<key> smoke-test.sh <url>}
URL=${URL%/}
: "${KBANG_KEY:?KBANG_KEY must be set}"
WORKDIR=${KBANG_WORKDIR:-}
SYSTEM_TMP=${KBANG_TMP:-/tmp}
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
cd "$DIR"

pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1" >&2; exit 1; }
kbang() { curl -fsSL "$URL/kbang.sh" | bash -s -- "$@"; }
temp_leftovers() {
    [ "$SYSTEM_TMP" = - ] && return 0
    find "$SYSTEM_TMP" -maxdepth 1 \( -name 'jbang*native-image' -o -name 'SVM-*' -o -name 'driverRoot-*' \) | sort
}

curl -fsS "$URL/health" > /dev/null || fail "$URL/health does not answer"
pass "server is up"
TEMP_BEFORE=$(temp_leftovers)

printf 'fun main(args: Array<String>) = println("Hello " + (args.firstOrNull() ?: "smoke"))\n' > hello.kt
printf 'fun main() = printn("x")\n' > broken.kt

kbang native hello.kt 2> native.err || fail "native build: $(cat native.err)"
[ -x hello ] || fail "hello is not executable"
[ "$(./hello World)" = "Hello World" ] || fail "hello prints '$(./hello World)'"
pass "native build runs ($(du -h hello | cut -f1), $(file -b hello | cut -d, -f1-2))"

kbang jar hello.kt 2> jar.err || fail "jar build: $(cat jar.err)"
[ "$("$JAVA" -jar hello.jar Jar)" = "Hello Jar" ] || fail "hello.jar does not run"
pass "fat JAR runs with java -jar"

if curl -fsSL "$URL/kbang.sh" | KBANG_KEY=wrong-key-0123456789 bash -s -- jar hello.kt 2> wrongkey.err; then fail "a wrong key was accepted"; fi
grep -q 401 wrongkey.err || fail "a wrong key did not answer 401: $(cat wrongkey.err)"
pass "wrong key is rejected with 401"

if kbang jar broken.kt 2> broken.err; then fail "broken.kt was built"; fi
grep -q 422 broken.err && grep -q broken.kt broken.err && grep -q printn broken.err || fail "compile error not reported: $(cat broken.err)"
pass "compile error is a 422 naming broken.kt"

STATUS=$(printf 'X-API-Key: %s\n' "$KBANG_KEY" | curl -sS -o arch.txt -w '%{http_code}' -H @- --data-binary @hello.kt "$URL/build/native?arch=riscv64&name=hello")
[ "$STATUS" = 400 ] || fail "a foreign architecture answered $STATUS"
pass "foreign architecture is rejected with 400"

[ -n "$WORKDIR" ] || { echo "ALL CHECKS PASSED (set KBANG_WORKDIR on the KBang host to check for leftovers too)"; exit 0; }

build_processes() { pgrep -f "$WORKDIR/kbang-" || true; }

# A client that goes away in the middle of a native build: KBang must stop the build and remove everything
printf 'fun main() = println("never")\n' > gone.kt
printf 'X-API-Key: %s\n' "$KBANG_KEY" | curl -sS -H @- --data-binary @gone.kt -o /dev/null "$URL/build/native?name=gone" &
CLIENT=$!
for _ in $(seq 1 120); do [ -n "$(build_processes)" ] && break; sleep 1; done
[ -n "$(build_processes)" ] || fail "the native build for gone.kt never started"
sleep 20
kill "$CLIENT"
wait "$CLIENT" 2> /dev/null || true
for _ in $(seq 1 30); do [ -z "$(build_processes)" ] && break; sleep 1; done
[ -z "$(build_processes)" ] || fail "build processes survived the disconnect: $(pgrep -fa "$WORKDIR/kbang-")"
pass "a disconnecting client stops its build"

LEFT=$(find "$WORKDIR" -mindepth 1 -maxdepth 1 -name 'kbang-*' 2> /dev/null)
[ -z "$LEFT" ] || fail "workspaces left behind: $LEFT"
NEW_TEMP=$(comm -13 <(echo "$TEMP_BEFORE") <(temp_leftovers) | sed '/^$/d')
[ -z "$NEW_TEMP" ] || fail "temporary files left in $SYSTEM_TMP: $NEW_TEMP"
pass "no workspace, no build process and no temporary file left behind"

echo "ALL CHECKS PASSED"
