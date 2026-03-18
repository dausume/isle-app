#!/bin/bash

# isle-app/install.sh
#
# Installs (or re-installs) the Isle App from the build/ directory.
# Copies files directly — no dpkg needed, idempotent, fast for dev iteration.
#
# Usage: ./install.sh          (will prompt for sudo)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/build/deb-staging"

if [ ! -f "$BUILD_DIR/usr/share/isle-app/isle-app.jar" ]; then
    echo "ERROR: No build found. Run ./build-deb.sh first."
    exit 1
fi

echo "==> Installing Isle App..."

sudo cp    "$BUILD_DIR/usr/share/isle-app/isle-app.jar"  /usr/share/isle-app/isle-app.jar  2>/dev/null \
  || { sudo mkdir -p /usr/share/isle-app && sudo cp "$BUILD_DIR/usr/share/isle-app/isle-app.jar" /usr/share/isle-app/isle-app.jar; }

sudo cp    "$BUILD_DIR/usr/bin/isle-app"                 /usr/bin/isle-app
sudo chmod 755                                           /usr/bin/isle-app

sudo cp    "$BUILD_DIR/usr/share/applications/isle-app.desktop" \
           /usr/share/applications/isle-app.desktop

echo "==> Installed. Run with: isle-app"
