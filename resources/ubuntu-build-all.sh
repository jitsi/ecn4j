#!/usr/bin/env bash
PROJECT_DIR="$(cd "$(dirname "$0")/../"; pwd -P)"
JAVA_VERSION=11
DIST=focal
ARCHS=(amd64 arm64 ppc64el)

for ARCH in "${ARCHS[@]}"; do
    "$PROJECT_DIR/resources/ubuntu-build-docker.sh" "$ARCH" "$DIST" "$JAVA_VERSION" || exit 1
done;
