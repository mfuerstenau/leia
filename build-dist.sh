#!/usr/bin/env bash
# Builds leia-server.jar and the leia TUI for Linux and Windows (x86_64), and puts each into a
# self-contained folder under dist/: the executable with leia-server.jar next to it.
#
# Needs: a Java 17+ runtime for the Gradle wrapper (JAVA_HOME or `java` on PATH; Gradle provisions
# the Java 25 toolchain itself), rustup, and cargo-zigbuild with zig for the Windows cross-build.
# No macOS build and no bundled Java (ADR 0004).
set -euo pipefail
cd "$(dirname "$0")"

# A user-local zig, if there is none on PATH.
if ! command -v zig >/dev/null; then
  for dir in "$HOME"/.local/opt/zig-*; do
    [ -x "$dir/zig" ] && PATH="$dir:$PATH"
  done
fi

version=$(sed -n 's/^version=//p' gradle.properties)
targets=(x86_64-unknown-linux-gnu:linux-x86_64 x86_64-pc-windows-gnu:windows-x86_64)

echo "==> leia-server.jar"
./gradlew --quiet :leia-server:shadowJar

for entry in "${targets[@]}"; do
  target=${entry%%:*}
  echo "==> leia for $target"
  rustup target add --toolchain stable "$target" >/dev/null
  if [ "$target" = x86_64-unknown-linux-gnu ]; then
    cargo build --manifest-path tui/Cargo.toml --release --target "$target"
  else
    cargo zigbuild --manifest-path tui/Cargo.toml --release --target "$target"
  fi
done

rm -rf dist
for entry in "${targets[@]}"; do
  target=${entry%%:*}
  dir=dist/leia-$version-${entry##*:}
  mkdir -p "$dir"
  cp leia-server/build/libs/leia-server.jar "$dir/"
  case $target in
    *windows*) cp "tui/target/$target/release/leia.exe" "$dir/" ;;
    *) cp "tui/target/$target/release/leia" "$dir/" ;;
  esac
done

echo "==> Done"
ls -lh dist/*
