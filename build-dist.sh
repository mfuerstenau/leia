#!/usr/bin/env bash
# Builds leia.jar and packs it, with the scripts leia and leia.cmd that start it, as one zip for
# Linux and Windows (x86_64): dist/leia-<version>.zip, which unpacks to the folder leia-<version>/
# with leia.jar, leia (executable), leia.cmd, the user README.md, LICENSE and NOTICE (ADR 0082,
# ADR 0083). The jar runs anywhere with Java 25 (ADR 0034). The zip has no runtimes/: the first
# `leia --download-runtimes` or `D` on the start screen installs it, with leia's own jars from
# leia.jar and Lucene's from Maven Central, into the user's data folder, not beside leia.jar
# (ADR 0073, ADR 0080, ADR 0098). The same holds for a SNAPSHOT and a release version.
#
# Needs: a Java 17+ runtime for the Gradle wrapper (JAVA_HOME or `java` on PATH; Gradle provisions
# the Java 25 toolchain itself). No macOS build and no bundled Java (ADR 0004).
set -euo pipefail
cd "$(dirname "$0")"

version=$(sed -n 's/^version=//p' gradle.properties)

echo "==> leia-$version.zip"
./gradlew --quiet :leia-app:leiaZip

rm -rf dist
mkdir -p dist
cp -p "leia-app/build/dist/leia-$version.zip" dist/

echo "==> Done"
ls -lh dist/*
