#!/usr/bin/env bash
# Builds the plugin jar the way real Paper needs it: in Paper, Server, Player, World, Block, Entity and friends are
# INTERFACES, but the test stand-ins are classes. A jar compiled straight against the stand-ins calls them with
# invokevirtual and fails on a real server ("IncompatibleClassChangeError: Found interface ..."). So this script puts
# generated interface versions of those types first on the compile path, then checks the result.
# Usage: tools/build-jar.sh <version> <output.jar>
set -euo pipefail
cd "$(dirname "$0")/.."
VER="$1"; JAR="$2"
W="${TMPDIR:-/tmp}/nexuswatchtower-jar"
rm -rf "$W"; mkdir -p "$W/stubs" "$W/shimsrc" "$W/shim" "$W/app"
javac --release 21 -nowarn -d "$W/stubs" $(find stubs -name '*.java') 2>&1 | grep -v "Picked up" || true
python3 tools/make-interface-shim.py "$W/stubs" "$W/shimsrc"
mkdir -p "$W/shimsrc/net/kyori/adventure/text/serializer/plain"
cp tools/shim-extra/PlainTextComponentSerializer.java "$W/shimsrc/net/kyori/adventure/text/serializer/plain/"
javac --release 21 -nowarn -cp "$W/stubs" -d "$W/shim" $(find "$W/shimsrc" -name '*.java') 2>&1 | grep -v "Picked up" || true
javac --release 21 -Xlint:all -Werror -cp "$W/shim:$W/stubs" -d "$W/app" $(find src/main/java -name '*.java') 2>&1 | grep -v "Picked up" || true
# guard: no invokevirtual on a type that is an interface in real Paper
BAD=$(for c in $(find "$W/app" -name '*.class'); do javap -c -p "$c" 2>/dev/null; done \
  | grep -E "invokevirtual.*// Method (org/bukkit/(Server|World|block/Block|entity/(Entity|LivingEntity|HumanEntity|Player|Item)|inventory/Inventory|advancement/Advancement|plugin/(PluginManager|Plugin)|scheduler/(BukkitScheduler|BukkitTask)|command/CommandSender|OfflinePlayer|Keyed)|net/kyori/adventure/text/serializer/plain/PlainTextComponentSerializer)\." || true)
if [ -n "$BAD" ]; then echo "FAIL: class-style calls on interfaces:"; echo "$BAD" | head; exit 1; fi
sed "s/\${project.version}/$VER/" src/main/resources/plugin.yml > "$W/app/plugin.yml"
cp src/main/resources/config.yml "$W/app/"
rm -f "$JAR"; jar cf "$JAR" -C "$W/app" . 2>&1 | grep -v "Picked up" || true
echo "built $JAR"
