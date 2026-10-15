#!/usr/bin/env python3
"""Check portable engineering specs and references to retired engineering Skills."""
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

RETIRED = (
    "api-and-namespace-design", "backend-module-structure", "code-conventions",
    "dev-workflow", "frontend-conventions", "pr-submission", "skill-lifecycle",
    "testing-and-ci",
)
LINK = re.compile(r"(?<!!)\[[^\]\n]*\]\(([^)\n]+)\)")


def check(root: Path) -> tuple[list[str], int, int]:
    spec = root / ".trellis/spec"
    errors = []
    files = sorted(spec.rglob("*.md"))
    if not (spec / "index.md").is_file():
        errors.append("missing .trellis/spec/index.md")
    paths = files + [root / name for name in ("AGENTS.md", "CONTRIBUTING.md")]
    links = 0
    for path in paths:
        if not path.is_file():
            errors.append(f"missing {path.relative_to(root)}")
            continue
        body = path.read_text(encoding="utf-8")
        label = path.relative_to(root)
        for name in RETIRED:
            if f".agents/skills/{name}" in body:
                errors.append(f"{label}: reference to retired Skill {name}")
        if path in files and re.search(r"/(?:home|Users|tmp)/|(?<!\w)~/", body):
            errors.append(f"{label}: machine-specific path in shared spec")
        for match in LINK.finditer(body):
            target = match.group(1).strip().split(' "', 1)[0].strip("<>")
            parts = urlsplit(target)
            if parts.scheme or parts.netloc or not parts.path:
                continue
            links += 1
            target_path = Path(unquote(parts.path))
            resolved = (path.parent / target_path).resolve()
            if not resolved.is_relative_to(root):
                errors.append(f"{label}: link escapes checkout: {target}")
            elif not resolved.exists():
                errors.append(f"{label}: missing link target: {target}")
            elif resolved.is_relative_to(root / ".trellis") and not resolved.is_relative_to(spec):
                errors.append(f"{label}: link depends on local Trellis state: {target}")
    for name in RETIRED:
        if (root / ".agents/skills" / name).exists():
            errors.append(f"retired engineering Skill still present: {name}")
    return errors, len(files), links


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    errors, files, links = check(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"Engineering specs: {files} documents, {links} local links checked.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
