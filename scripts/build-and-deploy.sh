#!/usr/bin/env bash
#
# Pulls the latest HouseGraph source, builds the shadow jar, and copies it
# into a destination folder, overwriting whatever jar was there before.
#
# Usage:
#   extras/build-and-deploy.sh [DEST_DIR]
#
# DEST_DIR defaults to ~/HouseGraph. The script must be run from inside the
# HouseGraph source checkout (or set SOURCE_DIR below).

set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${1:-$HOME/HouseGraph}"

echo "==> Pulling latest source in $SOURCE_DIR"
git -C "$SOURCE_DIR" pull --ff-only

echo "==> Building :app:shadowJar"
(cd "$SOURCE_DIR" && ./gradlew :app:shadowJar)

JAR_SRC=$(ls "$SOURCE_DIR"/app/build/libs/app-*.jar 2>/dev/null | head -n1)
[[ -n "$JAR_SRC" ]] || { echo "error: no jar found under $SOURCE_DIR/app/build/libs/" >&2; exit 1; }

mkdir -p "$DEST_DIR"
rm -f "$DEST_DIR"/app-*.jar "$DEST_DIR/housegraph.jar"
cp "$JAR_SRC" "$DEST_DIR/housegraph.jar"

echo "==> Deployed $(basename "$JAR_SRC") -> $DEST_DIR/housegraph.jar"
