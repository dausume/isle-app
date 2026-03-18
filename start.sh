#!/bin/bash

# isle-app/start.sh
#
# Build, install, and run Isle App in one shot.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

"$SCRIPT_DIR/shells/build-deb.sh" && "$SCRIPT_DIR/shells/install.sh" && isle-app
