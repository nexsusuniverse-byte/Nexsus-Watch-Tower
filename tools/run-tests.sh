#!/usr/bin/env bash
# Compiles the stubs, the plugin and its test program strictly (-Xlint:all -Werror), then runs the tests.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${TMPDIR:-/tmp}/nexuswatchtower-build"
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 21 -nowarn -d "$OUT/stubs" $(find stubs -name '*.java')
javac --release 21 -Xlint:all -Werror -cp "$OUT/stubs" -d "$OUT/app" $(find src/main/java src/test/java -name '*.java')
java -cp "$OUT/app:$OUT/stubs" com.nexuscraft.nexuswatchtower.WatchTest
java -cp "$OUT/app:$OUT/stubs" com.nexuscraft.nexuswatchtower.AppTest
