#!/usr/bin/env bash
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <JAVA_HOME> <ARCH>"
    echo "  JAVA_HOME: Path to Java installation"
    echo "  ARCH: Architecture to build for (x86_64 or arm64)"
    exit 1
fi;

JAVA_HOME=$1
ARCH=$2

case $ARCH in
    "x86-64"|"x86_64")
        INSTALL_PREFIX_ARCH=x86-64
        OSX_ARCH=x86_64
        ;;
    "arm64"|"aarch64")
        INSTALL_PREFIX_ARCH=aarch64
        OSX_ARCH=arm64
        ;;
    *)
        echo "Unknown architecture $ARCH"
        exit 1
        ;;
esac

rm -rf cmake-build
cmake -B cmake-build \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME="$JAVA_HOME" \
    -DCMAKE_INSTALL_PREFIX="src/main/resources/darwin-$INSTALL_PREFIX_ARCH" \
    -DCMAKE_OSX_ARCHITECTURES="$OSX_ARCH" || exit 1
cmake --build cmake-build --config Release --target install
