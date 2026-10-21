#!/usr/bin/env bash
# Compose entry point that makes the optional local storage selection exclusive.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
usage() {
  echo "Usage: $0 --storage rustfs|silo|external [--env-file FILE] [--project-name NAME] -- COMPOSE_ARGS..."
}
fail() { echo "ERROR: $*" >&2; exit 2; }
storage=""
env_file=".env.release"
project_args=()
while (($#)); do
  case "$1" in
    --storage)
      [[ $# -ge 2 && -z "$storage" ]] || fail "Select exactly one storage backend"
      storage="$2"; shift 2 ;;
    --env-file)
      [[ $# -ge 2 && -n "$2" ]] || fail "Missing env file"
      env_file="$2"; shift 2 ;;
    --project-name)
      [[ $# -ge 2 && -n "$2" ]] || fail "Missing project name"
      project_args=(--project-name "$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    *) fail "Unknown option: $1" ;;
  esac
done
case "$storage" in rustfs|silo|external) ;; *) fail "Select rustfs, silo or external" ;; esac
(($#)) || fail "Missing Compose command"
# Do not allow additional global files to silently merge incompatible storage services.
for arg in "$@"; do
  # In logs, -f means follow rather than a global Compose file option.
  [[ "$arg" != -f || "${1:-}" != logs ]] || continue
  case "$arg" in
    -f|--file|-f?*|--file=*|--env-file|--env-file=*|--project-directory|--project-directory=*)
      fail "Compose file and env selection must use this script's options" ;;
  esac
done
cd "$REPO_ROOT"
[[ -f "$env_file" ]] || fail "Env file not found"
files=(-f compose.release.yml)
if [[ "$storage" != external ]]; then
  files+=(-f "compose.storage.$storage.yml")
fi
exec docker compose --env-file "$env_file" "${project_args[@]}" "${files[@]}" "$@"
