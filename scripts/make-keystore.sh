#!/usr/bin/env bash
# Interactive helper to generate a release signing keystore under keys/
# (gitignored) and print what's needed to wire it into GitHub Actions
# secrets. Never prints passwords.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
keys_dir="$root/keys"
keystore_path="$keys_dir/release.keystore"

command -v keytool >/dev/null 2>&1 || { echo "keytool not found (install a JDK)" >&2; exit 1; }

if [[ -e "$keystore_path" ]]; then
    read -r -p "$keystore_path already exists. Overwrite? [y/N] " confirm
    [[ "$confirm" == "y" || "$confirm" == "Y" ]] || { echo "aborted"; exit 1; }
    rm -f "$keystore_path"
fi

read -r -p "Key alias [release]: " alias
alias="${alias:-release}"

mkdir -p "$keys_dir"

keytool -genkeypair -v \
    -keystore "$keystore_path" \
    -alias "$alias" \
    -keyalg RSA -keysize 4096 \
    -validity 10000

echo
echo "==> keystore created at $keystore_path (kept out of git via .gitignore)"
echo
echo "==> base64-encoded keystore (copy the whole block below into the"
echo "    KEYSTORE_BASE64 GitHub secret):"
echo
base64 -w0 "$keystore_path" 2>/dev/null || base64 "$keystore_path"
echo
echo
echo "==> create these repository secrets (Settings -> Secrets and variables"
echo "    -> Actions) in Ginger-Beard/odin-keyboard-mapper:"
echo "      KEYSTORE_BASE64   -> the base64 output above"
echo "      KEYSTORE_PASSWORD -> the keystore password you just entered"
echo "      KEY_ALIAS         -> $alias"
echo "      KEY_PASSWORD      -> the key password you just entered"
echo
echo "(Passwords are not printed by this script; use what you typed above.)"
