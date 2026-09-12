#!/usr/bin/env bash
# switch_profile.sh <profile|stock> [--restart|--reboot] - Thin backwards-
# compatible wrapper around install_profile.sh (kept under its old name).
# Prefer calling ./install_profile.sh directly in new usage/docs.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
exec "$SCRIPT_DIR/install_profile.sh" "$@"
