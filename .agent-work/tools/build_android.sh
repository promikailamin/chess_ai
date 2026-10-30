#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root/android"
./gradlew :app:assembleDebug --no-daemon
mkdir -p "$repo_root/.agent-work/outputs"
cp app/build/outputs/apk/debug/app-debug.apk \
    "$repo_root/.agent-work/outputs/chess-ai-android-debug.apk"
echo "APK: $repo_root/.agent-work/outputs/chess-ai-android-debug.apk"
