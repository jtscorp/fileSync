#!/usr/bin/env bash
# Native (GraalVM) build + run.
#
# The two env vars cannot live in build.gradle: gradlew needs JAVA_HOME to start
# the JVM that reads build.gradle at all, and gluonfx has no javaHome property.
# Override either one by exporting it before calling this script.
set -euo pipefail
cd "$(dirname "$0")"

: "${JAVA_HOME:=$HOME/.jdks/temurin-23.0.2}"                                  # Gradle 8.13 cannot run on JDK 24+
: "${GRAALVM_HOME:=$HOME/.jdks/graalvm-java23-linux-amd64-gluon-23+25.1-dev}" # must be Gluon's, not vanilla CE
export JAVA_HOME GRAALVM_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# gradlew's own error does not say which var is wrong.
for var in JAVA_HOME GRAALVM_HOME; do
    [ -x "${!var}/bin/java" ] || { echo "$var=${!var} -- bu yerda JDK yo'q" >&2; exit 1; }
done

# Single target, so the arch is hardcoded.
BIN=build/gluonfx/x86_64-linux/fileSync

case "${1:-all}" in
    build) ./gradlew nativeBuild ;;
    run)
        [ -x "$BIN" ] || { echo "$BIN yo'q -- avval: $0 build" >&2; exit 1; }
        exec "$BIN"
        ;;
    all)
        ./gradlew nativeBuild
        exec "$BIN"
        ;;
    *)
        echo "foydalanish: $0 [build|run|all]   (default: all)" >&2
        exit 2
        ;;
esac
