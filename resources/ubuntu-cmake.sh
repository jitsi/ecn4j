#!/usr/bin/env bash
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <ARCH> <JAVA_VERSION> <DIR>"
    echo "  ARCH: Architecture to build for (amd64, arm64, ppc64el)"
    echo "  JAVA_VERSION: Java version (11)"
    echo "  DIR: ecn4j project directory"
    exit 1
fi;

ARCH=$1
JAVA_VERSION=$2
DIR=$3

cd "$DIR" || exit 1
echo "$ARCH"
echo "$JAVA_VERSION"
if [ -f "cmake/$ARCH-linux-gnu.cmake" ]; then
    TOOLCHAIN_FILE="cmake/$ARCH-linux-gnu.cmake"
fi;

export JAVA_HOME=/usr/lib/jvm/java-$JAVA_VERSION-openjdk-$ARCH/
echo "$JAVA_HOME"

rm -rf cmake-build
cmake -B cmake-build \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME:PATH="$JAVA_HOME" \
    -DCMAKE_TOOLCHAIN_FILE:PATH="$TOOLCHAIN_FILE" || exit 1
cmake --build cmake-build --config Release --target install
