#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/SagerNet/sing-box.git
fi
pushd sing-box
# Pinned to official tag v1.15.0-alpha.6. Fetch the SHA from SagerNet so local
# checkouts whose origin is a fork still resolve it.
git fetch https://github.com/SagerNet/sing-box.git "$COMMIT_SING_BOX"
git checkout "$COMMIT_SING_BOX"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/starifly/libneko.git
fi
pushd libneko
git fetch origin "$COMMIT_LIBNEKO"
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
