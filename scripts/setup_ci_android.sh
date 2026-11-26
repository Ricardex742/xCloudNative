#!/usr/bin/env bash
set -euo pipefail
tool_root="$RUNNER_TEMP/apk-tools"
mkdir -p "$tool_root/android-sdk/cmdline-tools"
curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip -o "$tool_root/android-tools.zip"
echo "e025545c62a8e64c7559119566a569fb1dec5f60  $tool_root/android-tools.zip" | sha1sum -c -
unzip -q "$tool_root/android-tools.zip" -d "$tool_root/android-sdk/cmdline-tools"
mv "$tool_root/android-sdk/cmdline-tools/cmdline-tools" "$tool_root/android-sdk/cmdline-tools/latest"
export ANDROID_HOME="$tool_root/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# sdkmanager closes stdin after the last license, so the result of the pipeline is its status.
(set +o pipefail; yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses)
"$sdkmanager" --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0'
curl -fsSL https://downloads.gradle.org/distributions/gradle-8.7-bin.zip -o "$tool_root/gradle.zip"
echo "544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d  $tool_root/gradle.zip" | sha256sum -c -
unzip -q "$tool_root/gradle.zip" -d "$tool_root"
echo "ANDROID_HOME=$ANDROID_HOME" >> "$GITHUB_ENV"
echo "ANDROID_SDK_ROOT=$ANDROID_HOME" >> "$GITHUB_ENV"
echo "$tool_root/gradle-8.7/bin" >> "$GITHUB_PATH"
