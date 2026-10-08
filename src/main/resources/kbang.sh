#!/usr/bin/env bash
# KBang client: uploads one Kotlin file to a KBang server and saves what it builds.
#
#   curl -fsSL __KBANG_URL__/kbang.sh | KBANG_KEY=<key> bash -s -- native tool.kt   ->  ./tool
#   curl -fsSL __KBANG_URL__/kbang.sh | KBANG_KEY=<key> bash -s -- jar tool.kt      ->  ./tool.jar
#
# To keep it: curl -fsSL __KBANG_URL__/kbang.sh -o ~/bin/kbang && chmod +x ~/bin/kbang
#
# Usage:       kbang.sh native|jar <file.kt> [output]
# Environment: KBANG_KEY (required), KBANG_URL (default __KBANG_URL__),
#              KBANG_ARCH (default: uname -m, only to build on a different machine than the target)
# Exit codes:  0 done, 1 rejected by the server or build failed, 2 wrong usage or unusable file names
set -euo pipefail

usage() {
    echo "Usage: kbang.sh native|jar <file.kt> [output]" >&2
    echo "Needs KBANG_KEY in the environment. Optional: KBANG_URL (default __KBANG_URL__) and KBANG_ARCH (default: uname -m)." >&2
    exit 2
}

[ "$#" -ge 2 ] && [ "$#" -le 3 ] || usage
MODE=$1
FILE=$2
case "$MODE" in
    native|jar) ;;
    *) usage ;;
esac

if [ -z "${KBANG_KEY:-}" ]; then
    echo "kbang: KBANG_KEY is not set" >&2
    exit 2
fi
if [ ! -f "$FILE" ]; then
    echo "kbang: $FILE is not a file" >&2
    exit 2
fi

# Same rule as the server: a letter first, then letters, digits, _ and -
NAME=$(basename "$FILE" .kt)
if [[ ! "$NAME" =~ ^[A-Za-z][A-Za-z0-9_-]{0,63}$ ]]; then
    echo "kbang: '$NAME' is not a valid name (letters, digits, _ and -, starting with a letter). Rename the file." >&2
    exit 2
fi

URL=${KBANG_URL:-__KBANG_URL__}
URL=${URL%/}
if [ "$MODE" = jar ]; then
    OUT=${3:-$NAME.jar}
else
    OUT=${3:-$NAME}
fi
if [ -d "$OUT" ] || [ "$OUT" -ef "$FILE" ]; then
    echo "kbang: $OUT is a directory or the source itself. Name another output." >&2
    exit 2
fi
PART="$OUT.part"

ARCH=${KBANG_ARCH:-$(uname -m)}

echo "kbang: building $FILE as $MODE on $URL, this can take a few minutes" >&2
# The key reaches curl through stdin, an argument would show up in the process list for every user
if ! STATUS=$(printf 'X-API-Key: %s\n' "$KBANG_KEY" | curl -sS -X POST \
        -H @- \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@$FILE" \
        -o "$PART" \
        -w '%{http_code}' \
        "$URL/build/$MODE?arch=$ARCH&name=$NAME"); then
    rm -f "$PART"
    echo "kbang: the request to $URL failed" >&2
    exit 1
fi

if [ "$STATUS" = 200 ]; then
    mv "$PART" "$OUT"
    if [ "$MODE" = native ]; then
        chmod +x "$OUT"
    fi
    echo "kbang: done, wrote $OUT" >&2
    exit 0
fi

echo "kbang: the server answered $STATUS" >&2
if [ -f "$PART" ]; then
    cat "$PART" >&2
    echo >&2
    rm -f "$PART"
fi
exit 1
