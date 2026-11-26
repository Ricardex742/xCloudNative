#!/usr/bin/env bash
set -euo pipefail

scripts=$(cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$scripts/smoke_device.py" "$@"
