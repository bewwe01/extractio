#!/usr/bin/env bash
# Creates a release signing key and keystore.properties (both gitignored).
# Back both files up: every future update of the app must be signed with the same key.
set -euo pipefail
cd "$(dirname "$0")/.."
KS="${1:-saveit-release.jks}"
if [ -f keystore.properties ]; then echo "keystore.properties already exists; refusing to overwrite." >&2; exit 1; fi
PASS="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
keytool -genkeypair -v -keystore "$KS" -storetype PKCS12 -alias saveit \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=SaveIt, O=Personal"
cat > keystore.properties <<PROPS
storeFile=$KS
storePassword=$PASS
keyAlias=saveit
keyPassword=$PASS
PROPS
chmod 600 keystore.properties "$KS"
echo "Created $KS and keystore.properties. Keep copies somewhere safe (not in git)."
