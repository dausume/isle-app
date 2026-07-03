#!/bin/bash

# isle-manager-app/start.sh
#
# Dev shortcut: build, install via the NORMAL package route (dpkg → app + bundled
# CLI), and launch. This mirrors what an end user gets from ../appInstall.sh.
#
# (For a fast, non-dpkg dev copy instead, use shells/install.sh — dev-only.)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEB="$SCRIPT_DIR/build/isle-manager-app_0.1.0_all.deb"

"$SCRIPT_DIR/shells/build-deb.sh"
sudo dpkg -i "$DEB" || sudo apt-get -f install -y
isle-manager-app
