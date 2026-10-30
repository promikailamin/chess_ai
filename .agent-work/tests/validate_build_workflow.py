#!/usr/bin/env python3
"""Validate the Android APK build workflow configuration.

The android-actions/setup-android action used to default to the
"tools platform-tools" SDK packages. The legacy "tools" package has been
removed from the SDK repository, so the workflow must request explicit,
still-available packages instead.
"""
import sys
from pathlib import Path

import yaml

WORKFLOW = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "build.yml"


def main() -> int:
    data = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    steps = data["jobs"]["build"]["steps"]
    setup = next(s for s in steps if s.get("name") == "Set up Android SDK")
    packages = setup.get("with", {}).get("packages", "")

    errors = []
    if not packages:
        errors.append("setup-android step must set explicit 'packages'")
    tokens = packages.split()
    if "tools" in tokens:
        errors.append("legacy 'tools' package is no longer available")
    if "platform-tools" not in tokens:
        errors.append("'platform-tools' is required")
    if "platforms;android-35" not in tokens:
        errors.append("'platforms;android-35' is required for compileSdk 35")
    if "build-tools;35.0.0" not in tokens:
        errors.append("'build-tools;35.0.0' is required")

    if errors:
        for error in errors:
            print(f"FAIL: {error}")
        return 1

    print(f"OK: setup-android packages = {packages}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
