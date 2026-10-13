#!/bin/bash
set -euo pipefail

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export GOTOOLCHAIN=go1.25.5
export GOPATH="${GOPATH:-$(go env GOPATH)}"
if [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]]; then
    GOPATH=$(cygpath -u "$GOPATH")
fi

# Keep binding tools private to this project and pin the existing Matsuri fork.
revision=17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f
source_dir="$PROJECT_ROOT/.deps/gomobile"
export GOBIN="$PROJECT_ROOT/.deps/tool-bin"
if [ ! -d "$source_dir/.git" ]; then
    git clone --config core.autocrlf=false --no-checkout https://github.com/MatsuriDayo/gomobile.git "$source_dir"
    git -C "$source_dir" checkout --detach "$revision"
fi
if [ "$(git -C "$source_dir" rev-parse HEAD)" != "$revision" ] || [ -n "$(git -C "$source_dir" status --porcelain)" ]; then
    echo "Unexpected binding tool sources; refusing to overwrite them" >&2
    exit 1
fi
mkdir -p "$GOBIN"
(cd "$source_dir" && go install -v ./cmd/gomobile ./cmd/gobind)

# Android binding needs this work directory, not OpenAL. Fork `init` always
# installs upstream gobind@latest, which now requires a newer Go version.
mkdir -p "$GOPATH/pkg/gomobile"
