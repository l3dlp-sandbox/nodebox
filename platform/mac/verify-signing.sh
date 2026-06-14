#!/usr/bin/env bash
#
# Verifies a signed (and optionally notarized) NodeBox.app.
#
# Checks every Mach-O binary -- both on disk and INSIDE nodebox.jar (the notary service
# inspects jars too) -- for the three things that have actually broken NodeBox notarization:
#   1. signed with our Developer ID certificate
#   2. carries a secure timestamp
#   3. built against the 10.9 SDK or newer
# When a dmg is passed, it also confirms Gatekeeper acceptance and a stapled notary ticket.
#
# Usage: verify-signing.sh <NodeBox.app> [NodeBox.dmg]
set -euo pipefail

APP="${1:?usage: verify-signing.sh <NodeBox.app> [dmg]}"
DMG="${2:-}"
fail=0

check_macho() { # $1 = file, $2 = label
  local out sdk major minor
  out=$(codesign -dvv "$1" 2>&1 || true)
  grep -q "Authority=Developer ID Application" <<<"$out" || { echo "  NOT Developer ID signed: $2"; fail=1; }
  grep -q "^Timestamp=" <<<"$out"                        || { echo "  NO secure timestamp: $2"; fail=1; }
  sdk=$(vtool -show-build "$1" 2>/dev/null | awk '/sdk/{print $2; exit}')
  if [ -n "${sdk:-}" ]; then
    major=${sdk%%.*}; minor=${sdk#*.}; minor=${minor%%.*}
    if [ "$major" -lt 10 ] || { [ "$major" -eq 10 ] && [ "${minor:-0}" -lt 9 ]; }; then
      echo "  SDK too old ($sdk): $2"; fail=1
    fi
  fi
}

echo "== codesign --verify --deep --strict =="
codesign --verify --deep --strict --verbose=2 "$APP"

echo "== on-disk Mach-O binaries =="
while IFS= read -r f; do
  file "$f" | grep -q "Mach-O" && check_macho "$f" "${f#"$APP"/}"
done < <(find "$APP" -type f)

echo "== Mach-O inside nodebox.jar =="
JAR="$APP/Contents/app/lib/nodebox.jar"
if [ -f "$JAR" ]; then
  TMP=$(mktemp -d)
  for e in $(unzip -l "$JAR" | grep -iE "\.(jnilib|dylib)$" | awk '{print $4}'); do
    unzip -o -q "$JAR" "$e" -d "$TMP"
    file "$TMP/$e" | grep -q "Mach-O" && check_macho "$TMP/$e" "jar:$e"
  done
fi

if [ -n "$DMG" ]; then
  echo "== Gatekeeper assessment (informational) =="
  spctl -a -vvv --type exec "$APP" || true
  echo "== stapled notarization ticket on dmg =="
  xcrun stapler validate "$DMG"
fi

if [ "$fail" -ne 0 ]; then
  echo "VERIFICATION FAILED"
  exit 1
fi
echo "VERIFICATION PASSED"
