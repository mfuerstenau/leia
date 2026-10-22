#!/usr/bin/env bash
# Builds leia.jar and packs it, with the scripts leia and leia.cmd that start it, as one zip for
# Linux and Windows (x86_64): dist/leia-<version>.zip, which unpacks to the folder leia-<version>/
# with leia.jar, leia (executable) and leia.cmd (ADR 0082). The jar runs anywhere with Java 25
# (ADR 0034). The zip has no runtimes/: the first `leia --download-runtimes` or `D` on the start
# screen installs it, with leia's own jars from leia.jar and Lucene's from Maven Central
# (ADR 0073, ADR 0080). The same holds for a SNAPSHOT and a release version.
#
# Needs: a Java 17+ runtime for the Gradle wrapper (JAVA_HOME or `java` on PATH; Gradle provisions
# the Java 25 toolchain itself). No macOS build and no bundled Java (ADR 0004).
set -euo pipefail
cd "$(dirname "$0")"

version=$(sed -n 's/^version=//p' gradle.properties)

echo "==> leia-$version.zip"
./gradlew --quiet :leia-tui:leiaZip

rm -rf dist
mkdir -p dist
cp -p "leia-tui/build/dist/leia-$version.zip" dist/

echo "==> Done"
ls -lh dist/*
