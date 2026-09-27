#!/usr/bin/env bash
# Compile and run the Local API gateway test suite on a desktop JVM.
#
# The gateway only needs org.json plus two tiny Android stubs (Context and
# SharedPreferences), so it can be exercised without an emulator or a device.
set -euo pipefail
cd "$(dirname "$0")"

# Java uses ';' as the classpath separator on Windows and ':' elsewhere.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac

JSON_JAR="${JSON_JAR:-}"
if [ -z "$JSON_JAR" ]; then
  for candidate in \
    "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20180813"/*/json-20180813.jar \
    /usr/share/java/json.jar ; do
    if [ -f "$candidate" ]; then JSON_JAR="$candidate"; break; fi
  done
fi
if [ -z "$JSON_JAR" ] || [ ! -f "$JSON_JAR" ]; then
  echo "org.json jar not found. Set JSON_JAR=/path/to/json.jar" >&2
  exit 1
fi

OUT="build/test-classes"
rm -rf "$OUT"
mkdir -p "$OUT"

find src tests -name '*.java' > build/sources.txt
javac -encoding UTF-8 -nowarn -cp "$JSON_JAR" -d "$OUT" @build/sources.txt

# Under Git Bash, java is a native Windows binary: hand it a Windows path and
# stop MSYS from rewriting the drive letter inside the classpath.
if command -v cygpath >/dev/null 2>&1; then
  JSON_JAR="$(cygpath -w "$JSON_JAR")"
  MSYS2_ARG_CONV_EXCL='*' java -cp "$OUT$SEP$JSON_JAR" com.dsmod.probe.LocalApiTest
else
  java -cp "$OUT$SEP$JSON_JAR" com.dsmod.probe.LocalApiTest
fi
