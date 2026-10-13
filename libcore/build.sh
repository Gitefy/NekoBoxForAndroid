#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

# Wipe the whole cache. Partial rm leaves src-android-* dirs; gomobile's Mkdir
# then fails on Windows with "Cannot create a file when that file already exists."
rm -rf "$BUILD"

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

export GOBIND=gobind-matsuri
source ../buildScript/lib/core/get_source_env.sh
export GOTOOLCHAIN=go1.25.5
SING_BOX_VERSION="${SING_BOX_VERSION:?Missing pinned sing-box version}"
SING_BOX_REVISION="${COMMIT_SING_BOX:-unknown}"
# Android 16 devices may use 16 KB pages. The APK is zip-aligned separately,
# but the native ELF must also align every PT_LOAD segment to 16 KB.
NATIVE_INPUTS=$(python ../scripts/native-provenance.py inputs) || exit 1
LDFLAGS="-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384 -X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION} -X libcore.buildRevision=${SING_BOX_REVISION}"
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -target=android/arm64 -cache "$(realpath $BUILD)" -trimpath -ldflags="$LDFLAGS" -tags='with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
python ../scripts/native-provenance.py record "$NATIVE_INPUTS" || exit 1
echo ">> install $(realpath $proj)/libcore.aar"
