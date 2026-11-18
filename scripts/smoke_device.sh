#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 || ( "$3" != phone && "$3" != tv ) ]]; then
  printf 'Uso: %s APK SERIAL phone|tv\n' "${0##*/}" >&2
  exit 2
fi

apk=$1
serial=$2
launcher=android.intent.category.LAUNCHER
if [[ "$3" == tv ]]; then
  launcher=android.intent.category.LEANBACK_LAUNCHER
fi

root=$(cd -- "$(dirname -- "$0")/.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$sdk" ]]; then
  printf 'Configure ANDROID_HOME ou ANDROID_SDK_ROOT.\n' >&2
  exit 2
fi
adb="$sdk/platform-tools/adb"
device=("$adb" -s "$serial")
package=com.xcloud.tclnative
activity="$package/.ui.MainActivity"

"${device[@]}" get-state | grep -Fx device
PYTHONDONTWRITEBYTECODE=1 python3 "$root/scripts/verify_apk_signing.py" "$apk" --require-nondebuggable
"${device[@]}" install -r "$apk" | grep -Fx Success
"${device[@]}" shell getprop ro.build.version.sdk
resolved=$("${device[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c "$launcher" -p "$package")
printf '%s\n' "$resolved" | tr -d '\r' | grep -Fx "$activity"
launch=$("${device[@]}" shell am start -W -a android.intent.action.MAIN -c "$launcher" -p "$package")
printf '%s\n' "$launch"
printf '%s\n' "$launch" | tr -d '\r' | grep -Fxq 'Status: ok'
