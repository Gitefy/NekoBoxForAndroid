#!/bin/bash
set -euo pipefail

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
source "$PROJECT_ROOT/buildScript/lib/core/get_source_env.sh"
mkdir -p "$PROJECT_ROOT/.deps"

fetch_source() {
  local directory="$1" url="$2" revision="$3"
  local created=0
  if [ ! -d "$directory/.git" ]; then
    git clone --config core.autocrlf=false --no-checkout --depth 1 "$url" "$directory"
    created=1
  fi
  if ! git -C "$directory" cat-file -e "$revision^{commit}" 2>/dev/null; then
    git -C "$directory" fetch "$url" "$revision"
  fi
  if [ "$created" = 1 ] || [ "$(git -C "$directory" rev-parse HEAD 2>/dev/null || true)" != "$revision" ]; then
    if [ "$created" = 0 ] && [ -n "$(git -C "$directory" status --porcelain)" ]; then
      echo "Refusing to replace modified dependency: $directory" >&2
      exit 1
    fi
    git -C "$directory" checkout --detach "$revision"
  fi
}

fetch_source "$PROJECT_ROOT/.deps/sing-box" https://github.com/SagerNet/sing-box.git "$COMMIT_SING_BOX"
fetch_source "$PROJECT_ROOT/.deps/libneko" https://github.com/starifly/libneko.git "$COMMIT_LIBNEKO"

for patch in "$PROJECT_ROOT"/buildScript/lib/core/patches/*.patch; do
  if git -C "$PROJECT_ROOT/.deps/sing-box" apply --reverse --check "$patch" 2>/dev/null; then
    continue
  fi
  git -C "$PROJECT_ROOT/.deps/sing-box" apply --check "$patch"
  git -C "$PROJECT_ROOT/.deps/sing-box" apply "$patch"
done
