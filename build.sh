#!/usr/bin/env bash
# Choir build script — Unix shell

set -euo pipefail

TARGET="${1:-debug}"

case "$TARGET" in
    debug)           ./gradlew assembleDebug ;;
    release)         ./gradlew assembleRelease ;;
    test)            ./gradlew test ;;
    clean)           ./gradlew clean ;;
    install)         ./gradlew installDebug ;;
    install-release) ./gradlew installRelease ;;
    *)
        echo "Usage: build.sh [debug|release|test|clean|install|install-release]"
        exit 1
        ;;
esac
