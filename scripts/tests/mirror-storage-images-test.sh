#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"
cat >"$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$MIRROR_COMMAND_LOG"
case "$*" in
  'buildx imagetools inspect '*) [[ "${TARGET_EXISTS:-false}" == true ]] ;;
  'buildx imagetools create '*) exit 0 ;;
  *) exit 2 ;;
esac
STUB
chmod +x "$work/bin/docker"
export PATH="$work/bin:$PATH" MIRROR_COMMAND_LOG="$work/commands"
export MIRROR_REGISTRY=registry.example.test MIRROR_NAMESPACE=skillhub
printf '%s\n' 'docker.io/rustfs/rustfs@sha256:abc rustfs:immutable-test' >"$work/images"
TARGET_EXISTS=true bash "$REPO_ROOT/scripts/mirror-runtime-images.sh" "$work/images" >"$work/output"
if grep -q 'imagetools create' "$work/commands"; then echo 'FAIL: existing target was refreshed' >&2; exit 1; fi
TARGET_EXISTS=false bash "$REPO_ROOT/scripts/mirror-runtime-images.sh" "$work/images" >"$work/output"
grep -q 'imagetools create --tag registry.example.test/skillhub/rustfs:immutable-test docker.io/rustfs/rustfs@sha256:abc' "$work/commands"
grep -q '^docker.io/pgsty/silo:RELEASE.2026-09-16T00-00-00Z minio:latest$' "$REPO_ROOT/deploy/runtime-mirror-images.txt"
grep -Fxq 'docker.io/pgsty/silo:RELEASE.2026-10-18T00-00-00Z@sha256:2636fc4155d9a7e41ff58cdb65ddd13ba9304e67184e13c25bc5cb34016269d7 silo:20261018-2636fc4155d9' "$REPO_ROOT/deploy/runtime-mirror-images.txt"
echo 'Storage mirror skip and legacy alias tests passed'
