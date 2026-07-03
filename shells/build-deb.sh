#!/bin/bash

# isle-manager-app/build-deb.sh
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
mkdir -p "$DEB_STAGE/usr/share/isle-manager-app"
mkdir -p "$DEB_STAGE/usr/share/applications"
mkdir -p "$DEB_STAGE/usr/share/polkit-1/actions"

cp debian/DEBIAN/control              "$DEB_STAGE/DEBIAN/"
cp debian/usr/bin/isle-manager-app            "$DEB_STAGE/usr/bin/"
chmod 755                             "$DEB_STAGE/usr/bin/isle-manager-app"
cp debian/usr/share/applications/isle-manager-app.desktop \
                                      "$DEB_STAGE/usr/share/applications/"
cp target/isle-manager-app-${VERSION}.jar     "$DEB_STAGE/usr/share/isle-manager-app/isle-manager-app.jar"
cp debian/usr/share/polkit-1/actions/org.islemesh.permissions.policy \
                                      "$DEB_STAGE/usr/share/polkit-1/actions/"

# ── Bundle the isle CLI + runtime so dpkg installs a complete, self-contained tool ──
# (PROJECT_ROOT for the CLI scripts is the parent of isle-cli, so we lay the
#  sibling projects under /usr/share/isle-mesh/ to preserve their relative paths.)
echo "==> Bundling isle CLI + runtime..."
REPO_ROOT="$(cd "$PROJECT_DIR/.." && pwd)"
mkdir -p "$DEB_STAGE/usr/share/isle-mesh"
for d in isle-cli isle-agent openwrt-router mdns mesh-app-scaffolding; do
    if [ -d "$REPO_ROOT/$d" ]; then
        cp -r "$REPO_ROOT/$d" "$DEB_STAGE/usr/share/isle-mesh/"
    else
        echo "   ! WARNING: $REPO_ROOT/$d not found — CLI bundle may be incomplete"
    fi
done
# Strip VCS / node_modules cruft from the bundle
find "$DEB_STAGE/usr/share/isle-mesh" -maxdepth 2 -name node_modules -type d -exec rm -rf {} + 2>/dev/null || true
find "$DEB_STAGE/usr/share/isle-mesh" -maxdepth 2 -name '.git*' -exec rm -rf {} + 2>/dev/null || true

# Maintainer scripts: symlink `isle` onto PATH on install, remove it on uninstall
cp debian/DEBIAN/postinst debian/DEBIAN/prerm "$DEB_STAGE/DEBIAN/" 2>/dev/null || true
chmod 755 "$DEB_STAGE/DEBIAN/postinst" "$DEB_STAGE/DEBIAN/prerm" 2>/dev/null || true

# ── Build .deb ──
echo "==> Building .deb..."
dpkg-deb --build "$DEB_STAGE" "$BUILD_DIR/isle-manager-app_${VERSION}_all.deb"

echo ""
echo "==> Build complete!"
echo "    .deb:  build/isle-manager-app_${VERSION}_all.deb"
echo "    JAR:   build/deb-staging/usr/share/isle-manager-app/isle-manager-app.jar"
echo ""
echo "    Run:   ./install.sh"
