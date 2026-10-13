#!/bin/bash

source buildScript/init/env_ndk.sh

if [[ "$OSTYPE" =~ ^darwin ]]; then
  export SRC_ROOT=$PWD
else
  export SRC_ROOT=$(realpath .)
fi

case "$OSTYPE" in
  msys*|cygwin*) NDK_HOST=windows-x86_64 ;;
  darwin*) NDK_HOST=darwin-x86_64 ;;
  *) NDK_HOST=linux-x86_64 ;;
esac
DEPS=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST/bin

export ANDROID_ARM64_CC=$DEPS/aarch64-linux-android21-clang
export ANDROID_ARM64_CXX=$DEPS/aarch64-linux-android21-clang++
export ANDROID_ARM64_STRIP=$DEPS/aarch64-linux-android-strip
