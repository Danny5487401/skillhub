#!/usr/bin/env bash
# Source-checkout diagnostic; writes only a random diagnostic prefix in a prepared bucket.
set -euo pipefail
umask 077
full=false
deadline=180s
if [[ "${1:-}" == --full && $# -eq 1 ]]; then
  full=true
  deadline=420s
elif (($#)); then
  echo "Usage: $0 [--full]" >&2
  exit 2
fi
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v timeout >/dev/null || { echo "ERROR: GNU timeout is required" >&2; exit 2; }
: "${SKILLHUB_STORAGE_S3_ENDPOINT:?A reachable S3 endpoint is required}"
: "${SKILLHUB_STORAGE_S3_BUCKET:?A prepared private bucket is required}"
log_dir="${SKILLHUB_STORAGE_DIAGNOSTICS_LOG_DIR:-$REPO_ROOT/.dev/storage-diagnostics}"
mkdir -p "$log_dir"
log_file="$(mktemp "$log_dir/run-XXXXXXXX.log")"
cd "$REPO_ROOT/server"
# Raw SDK/network errors may contain signed URLs; retain them only in the private log.
if timeout --signal=TERM --kill-after=15s "$deadline" ./mvnw -pl skillhub-storage -am \
  -Dtest=S3StorageContractTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dskillhub.storage.contract=true -Dskillhub.storage.contract.full="$full" test >"$log_file" 2>&1; then
  echo "Storage diagnostic passed (objects, metadata, signatures, privacy, deletion)."
  echo "Private log: $log_file"
else
  result=$?
  echo "Storage diagnostic failed (exit $result). Check the private log; do not publish it unredacted." >&2
  echo "Private log: $log_file" >&2
  exit "$result"
fi
