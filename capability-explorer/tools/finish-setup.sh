#!/usr/bin/env bash
# Completes setup once you have logged in to Garmin.
# Device targets are the one artefact Garmin puts behind an account login;
# the SDK itself downloads anonymously.
set -euo pipefail

CLI="$HOME/.local/bin/connect-iq-sdk-manager"
DEVICE="${1:-fr745}"

echo "==> accepting SDK agreement"
"$CLI" agreement accept

echo "==> downloading device target: $DEVICE (with simulator fonts)"
"$CLI" device download -d "$DEVICE" -F

echo "==> installed devices:"
ls "$HOME/.Garmin/ConnectIQ/Devices"

echo "==> dumping static capability spec"
python3 tools/dump-device-caps.py "$DEVICE" || true

echo
echo "Done. Now run:  make build"
