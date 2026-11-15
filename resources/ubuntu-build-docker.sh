#!/usr/bin/env bash
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <ARCH> <DIST> <JAVA_VERSION>"
    echo "  ARCH: Architecture to build for (amd64, arm64, ppc64el)"
    echo "  DIST: Ubuntu release for base image (focal, jammy)"
    echo "  JAVA_VERSION: Java version (11)"
    exit 1
fi;

ARCH=$1
DIST=$2
JAVA_VERSION=$3

PROJECT_DIR="$(cd "$(dirname "$0")/../"; pwd -P)"
SOURCES_LIST="$PROJECT_DIR/resources/sources_${DIST}_${ARCH}.list"

fill_template() {
    sed -e "s@_MIRROR_@$1@g" -e "s@_DIST_@$DIST@g" -e "s@_ARCH_@$2@g" "$PROJECT_DIR/resources/sources.list.template"
}

UBUNTU_MIRROR_AMD64=${UBUNTU_MIRROR_AMD64:-"http://archive.ubuntu.com/ubuntu/"}
fill_template "$UBUNTU_MIRROR_AMD64" amd64 > "$SOURCES_LIST"

if [[ "$ARCH" != "amd64" ]]; then
    UBUNTU_MIRROR_PORTS=${UBUNTU_MIRROR_PORTS:-"http://ports.ubuntu.com/ubuntu-ports/"}
    fill_template "$UBUNTU_MIRROR_PORTS" "$ARCH" >> "$SOURCES_LIST"
fi;

# The build image is always amd64 and cross-compiles for the other architectures.
docker build --platform linux/amd64 "$PROJECT_DIR/resources" --build-arg DIST="$DIST" --build-arg ARCH="$ARCH" --build-arg JAVA_VERSION="$JAVA_VERSION" -t "ecn4j-$DIST-$ARCH" || exit 1
docker run --platform linux/amd64 --rm -v "$PROJECT_DIR":/build "ecn4j-$DIST-$ARCH" /build/resources/ubuntu-cmake.sh "$ARCH" "$JAVA_VERSION" /build
