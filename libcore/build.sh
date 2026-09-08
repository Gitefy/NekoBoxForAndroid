#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

export GOBIND=gobind-matsuri
source ../buildScript/lib/core/get_source_env.sh
SING_BOX_VERSION="${SING_BOX_VERSION:-1.15.0}"
SING_BOX_REVISION="${COMMIT_SING_BOX:-unknown}"
# Android 16 devices may use 16 KB pages. The APK is zip-aligned separately,
# but the native ELF must also align every PT_LOAD segment to 16 KB.
LDFLAGS="-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384 -X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION} -X libcore.buildRevision=${SING_BOX_REVISION}"
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags="$LDFLAGS" -tags='with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
