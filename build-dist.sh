#!/usr/bin/env bash
# Builds leia.jar and puts it, with the scripts leia and leia.cmd that start it, into a
# self-contained folder for Linux and one for Windows (x86_64) under dist/. The folders hold the
# same files; the jar runs anywhere with Java 25 (ADR 0034).
#
# Needs: a Java 17+ runtime for the Gradle wrapper (JAVA_HOME or `java` on PATH; Gradle provisions
# the Java 25 toolchain itself). No macOS folder and no bundled Java (ADR 0004).
set -euo pipefail
cd "$(dirname "$0")"

version=$(sed -n 's/^version=//p' gradle.properties)

echo "==> leia.jar"
./gradlew --quiet :leia-tui:leiaFolder

rm -rf dist
for os in linux windows; do
  dir=dist/leia-$version-$os-x86_64
  mkdir -p "$dir"
  cp -p leia-tui/build/leia/* "$dir/"
done

echo "==> Done"
ls -lh dist/*
