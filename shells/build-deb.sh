#!/bin/bash

# isle-app/build-deb.sh
#
# Compiles the JavaFX app and assembles a .deb package in build/.
# Usage: ./build-deb.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

BUILD_DIR="$PROJECT_DIR/build"
DEB_STAGE="$BUILD_DIR/deb-staging"
VERSION="0.1.0"

# Clean previous build
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# ── Compile ──
echo "==> Building Isle App JAR..."
mvn clean package -q

# ── Stage the .deb structure ──
echo "==> Staging .deb package..."
mkdir -p "$DEB_STAGE/DEBIAN"
mkdir -p "$DEB_STAGE/usr/bin"
mkdir -p "$DEB_STAGE/usr/share/isle-app"
mkdir -p "$DEB_STAGE/usr/share/applications"

cp debian/DEBIAN/control              "$DEB_STAGE/DEBIAN/"
cp debian/usr/bin/isle-app            "$DEB_STAGE/usr/bin/"
chmod 755                             "$DEB_STAGE/usr/bin/isle-app"
cp debian/usr/share/applications/isle-app.desktop \
                                      "$DEB_STAGE/usr/share/applications/"
cp target/isle-app-${VERSION}.jar     "$DEB_STAGE/usr/share/isle-app/isle-app.jar"

# ── Build .deb ──
echo "==> Building .deb..."
dpkg-deb --build "$DEB_STAGE" "$BUILD_DIR/isle-app_${VERSION}_all.deb"

echo ""
echo "==> Build complete!"
echo "    .deb:  build/isle-app_${VERSION}_all.deb"
echo "    JAR:   build/deb-staging/usr/share/isle-app/isle-app.jar"
echo ""
echo "    Run:   ./install.sh"
