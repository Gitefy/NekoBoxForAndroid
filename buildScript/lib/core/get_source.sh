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
# The pinned commit is not on any branch/tag, so a default clone does not fetch
# it. Fetch the exact object by SHA before checking out (GitHub allows fetching by SHA).
git fetch origin "$COMMIT_SING_BOX"
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
