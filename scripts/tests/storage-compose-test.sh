#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"
cat >"$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" >"$DOCKER_ARGS_FILE"
STUB
chmod +x "$work/bin/docker"
export PATH="$work/bin:$PATH" DOCKER_ARGS_FILE="$work/args"
touch "$work/env"
run() { "$REPO_ROOT/scripts/storage-compose.sh" --env-file "$work/env" "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
run --storage rustfs --project-name storage-contract -- config
[[ "$(cat "$work/args")" == *compose.storage.rustfs.yml* ]] || fail "RustFS overlay missing"
[[ "$(cat "$work/args")" != *compose.storage.silo.yml* ]] || fail "Unexpected Silo"
run --storage silo -- config
[[ "$(cat "$work/args")" == *compose.storage.silo.yml* ]] || fail "Silo overlay missing"
run --storage external -- --profile github-sync config
[[ "$(cat "$work/args")" != *compose.storage.* ]] || fail "External storage started a local backend"
run --storage rustfs -- logs -f
[[ "$(cat "$work/args")" == *logs* ]] || fail "Log command not forwarded"
for args in 'rustfs --storage silo' 'unknown'; do
  # Intentional word splitting for these fixed malformed input fixtures.
  if run --storage $args -- config >/dev/null 2>&1; then fail "Accepted invalid selection"; fi
done
if run --storage rustfs -- -f compose.storage.silo.yml config >/dev/null 2>&1; then fail "Accepted file injection"; fi
if run --storage rustfs -- --file=compose.storage.silo.yml config >/dev/null 2>&1; then fail "Accepted file injection"; fi
if run --storage rustfs >/dev/null 2>&1; then fail "Accepted missing command"; fi
if run --env-file "$work/missing" --storage rustfs -- config >/dev/null 2>&1; then fail "Accepted missing env"; fi
echo "Storage Compose selection tests passed"
