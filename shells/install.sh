#!/bin/bash

# isle-manager-app/install.sh
#
# ⚠ DEV-ONLY installer. Copies the app files directly — no dpkg, and it does NOT
# install the bundled CLI. Fast for iterating on the GUI, but it bypasses the
# package database (mixing it with `dpkg -i` can confuse package state).
#
# For the normal install of BOTH the app and the isle CLI, use the package route:
#   ../../appInstall.sh        (build .deb + dpkg -i)   ← traditional users
#   ../../cliOnlyInstall.sh    (CLI only, npm link)
#
# Usage: ./install.sh          (will prompt for sudo)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/build/deb-staging"

if [ ! -f "$BUILD_DIR/usr/share/isle-manager-app/isle-manager-app.jar" ]; then
    echo "ERROR: No build found. Run ./build-deb.sh first."
    exit 1
fi

echo "==> Installing Isle App..."

sudo cp    "$BUILD_DIR/usr/share/isle-manager-app/isle-manager-app.jar"  /usr/share/isle-manager-app/isle-manager-app.jar  2>/dev/null \
  || { sudo mkdir -p /usr/share/isle-manager-app && sudo cp "$BUILD_DIR/usr/share/isle-manager-app/isle-manager-app.jar" /usr/share/isle-manager-app/isle-manager-app.jar; }

sudo cp    "$BUILD_DIR/usr/bin/isle-manager-app"                 /usr/bin/isle-manager-app
sudo chmod 755                                           /usr/bin/isle-manager-app

sudo cp    "$BUILD_DIR/usr/share/applications/isle-manager-app.desktop" \
           /usr/share/applications/isle-manager-app.desktop

sudo cp    "$BUILD_DIR/usr/share/polkit-1/actions/org.islemesh.permissions.policy" \
           /usr/share/polkit-1/actions/org.islemesh.permissions.policy

echo "==> Installed. Run with: isle-manager-app"
