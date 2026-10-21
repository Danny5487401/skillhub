#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/repo/scripts" "$work/repo/server" "$work/bin"
cp "$REPO_ROOT/scripts/storage-diagnostics.sh" "$work/repo/scripts/"
cat >"$work/repo/server/mvnw" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$DIAGNOSTIC_COMMAND_LOG"
echo 'sensitive-transport-detail-must-not-reach-console'
exit "${DIAGNOSTIC_EXIT_CODE:-0}"
STUB
cat >"$work/bin/timeout" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$DIAGNOSTIC_COMMAND_LOG"
[[ "${DIAGNOSTIC_TIMEOUT:-false}" != true ]] || exit 124
shift 3
exec "$@"
STUB
chmod +x "$work/repo/server/mvnw" "$work/bin/timeout"
export PATH="$work/bin:$PATH" DIAGNOSTIC_COMMAND_LOG="$work/commands"
export SKILLHUB_STORAGE_S3_ENDPOINT=https://storage.example.test SKILLHUB_STORAGE_S3_BUCKET=skillhub
fail() { echo "FAIL: $*" >&2; exit 1; }
script="$work/repo/scripts/storage-diagnostics.sh"
bash "$script" >"$work/output" 2>&1
grep -q 'Storage diagnostic passed' "$work/output" || fail 'Success not reported'
if grep -q sensitive-transport "$work/output"; then fail 'Raw transport details leaked'; fi
for log in "$work/repo/.dev/storage-diagnostics/"*.log; do
  [[ "$(stat -c %a "$log")" == 600 ]] || fail 'Raw diagnostic log must be private'
done
bash "$script" --full >"$work/output" 2>&1
grep -q -- '-Dskillhub.storage.contract.full=true' "$work/commands" || fail 'Full contract suite not enabled'
grep -q '420s' "$work/commands" || fail 'Full suite deadline missing'
if DIAGNOSTIC_EXIT_CODE=7 bash "$script" >"$work/output" 2>&1; then fail 'Test failure reported as success'; else [[ $? == 7 ]] || fail 'Exit code not preserved'; fi
if DIAGNOSTIC_TIMEOUT=true bash "$script" >"$work/output" 2>&1; then fail 'Timeout reported as success'; else [[ $? == 124 ]] || fail 'Timeout code not preserved'; fi
if bash "$script" --unknown >"$work/output" 2>&1; then fail 'Unknown flag accepted'; fi
if SKILLHUB_STORAGE_S3_ENDPOINT= bash "$script" >"$work/output" 2>&1; then fail 'Missing endpoint accepted'; fi
echo 'Storage diagnostic timeout, privacy and exit-code tests passed'
