#!/usr/bin/env bash
set -euo pipefail

# Named volumes are created root-owned; hand the Gradle cache to the dev user.
sudo chown -R vscode:vscode "$HOME/.gradle" 2>/dev/null || true

echo "JDK:"
java -version

echo
echo "Android SDK at ${ANDROID_HOME}:"
sdkmanager --list_installed || true

echo
echo "Warming the Gradle wrapper (downloads the distribution once)…"
./gradlew --version
