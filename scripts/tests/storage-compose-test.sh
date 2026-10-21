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
run --storage rustfs -- --profile github-sync logs -f github-sync
[[ "$(cat "$work/args")" == *$'--profile\ngithub-sync\nlogs\n-f\ngithub-sync' ]] || fail "Profile log follow command not forwarded"
run --storage rustfs -- --profile github-sync logs --follow github-sync
[[ "$(cat "$work/args")" == *$'logs\n--follow\ngithub-sync' ]] || fail "Long log follow command not forwarded"
run --storage rustfs -- --ansi never --profile=github-sync --parallel 2 --progress=plain logs -f github-sync
[[ "$(cat "$work/args")" == *$'logs\n-f\ngithub-sync' ]] || fail "Global options obscured log command"
for args in 'rustfs --storage silo' 'unknown'; do
  # Intentional word splitting for these fixed malformed input fixtures.
  if run --storage $args -- config >/dev/null 2>&1; then fail "Accepted invalid selection"; fi
done
if run --storage rustfs -- -f compose.storage.silo.yml config >/dev/null 2>&1; then fail "Accepted file injection"; fi
if run --storage rustfs -- --file=compose.storage.silo.yml config >/dev/null 2>&1; then fail "Accepted file injection"; fi
reject_compose_args() {
  rm -f "$work/args"
  if run --storage rustfs -- "$@" >"$work/rejected" 2>&1; then
    fail "Accepted forbidden Compose selection: $*"
  fi
  [[ ! -e "$work/args" ]] || fail "Forwarded forbidden selection to Docker"
}
reject_compose_args --profile github-sync -f compose.storage.silo.yml config
reject_compose_args --profile github-sync -fcompose.storage.silo.yml config
reject_compose_args --profile github-sync --file=compose.storage.silo.yml config
reject_compose_args --profile github-sync --file compose.storage.silo.yml config
reject_compose_args --profile logs -f compose.storage.silo.yml config
reject_compose_args --profile=logs -f compose.storage.silo.yml config
reject_compose_args --profile github-sync logs -fcompose.storage.silo.yml
reject_compose_args --profile github-sync logs --file=compose.storage.silo.yml
reject_compose_args --profile github-sync logs --env-file "$work/env"
reject_compose_args --profile github-sync logs --env-file="$work/env"
reject_compose_args --profile github-sync logs --project-directory "$work"
reject_compose_args --profile github-sync logs --project-directory="$work"
if run --storage rustfs >/dev/null 2>&1; then fail "Accepted missing command"; fi
if run --env-file "$work/missing" --storage rustfs -- config >/dev/null 2>&1; then fail "Accepted missing env"; fi
echo "Storage Compose selection tests passed"
