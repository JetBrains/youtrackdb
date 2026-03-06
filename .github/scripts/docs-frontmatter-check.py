#!/usr/bin/env python3
"""Validate that documentation files have YAML frontmatter and are registered in docs-sync.yml.

No external dependencies — uses simple parsing for the limited YAML subset
found in frontmatter and docs-sync.yml.
"""

import sys
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DOCS_SYNC = REPO_ROOT / "docs" / "docs-sync.yml"

EXCLUDE_PATHS = {
    Path("CLAUDE.md"),
    Path("lucene/README.md"),  # Module excluded from build, kept as reference only
}
EXCLUDE_PREFIXES = (
    Path("docs/adr"),
)


def find_docs():
    """Return set of repo-relative Path objects for all docs that must have frontmatter."""
    docs = set()

    # All markdown under docs/ except ADRs
    for md in (REPO_ROOT / "docs").rglob("*.md"):
        rel = md.relative_to(REPO_ROOT)
        if any(rel.is_relative_to(p) for p in EXCLUDE_PREFIXES):
            continue
        docs.add(rel)

    # All README.md files in the repo
    for md in REPO_ROOT.rglob("README.md"):
        rel = md.relative_to(REPO_ROOT)
        parts = rel.parts
        if any(p.startswith(".") or p in ("target", "node_modules") for p in parts[:-1]):
            continue
        docs.add(rel)

    return docs - EXCLUDE_PATHS


def parse_frontmatter(path):
    """Return dict with keys found in frontmatter, or None if no frontmatter."""
    text = (REPO_ROOT / path).read_text(encoding="utf-8")
    match = re.match(r"^---\n(.*?\n)---\n", text, re.DOTALL)
    if not match:
        return None

    result = {}
    current_key = None
    for line in match.group(1).splitlines():
        # List item under current key
        if line.startswith("  - ") and current_key is not None:
            result[current_key].append(line[4:].strip().strip("\"'"))
        # New key with inline value
        elif ":" in line and not line.startswith(" "):
            key, _, value = line.partition(":")
            key = key.strip()
            value = value.strip()
            if value == "" or value == "[]":
                result[key] = []
                current_key = key
            else:
                result[key] = value.strip("\"'")
                current_key = None
    return result


def load_sync_mapping():
    """Return set of doc paths registered in docs-sync.yml."""
    registered = set()
    text = DOCS_SYNC.read_text(encoding="utf-8")
    for match in re.finditer(r"^\s*- doc:\s*(.+)$", text, re.MULTILINE):
        registered.add(Path(match.group(1).strip().strip("\"'")))
    return registered


def main():
    errors = []
    docs = find_docs()
    registered = load_sync_mapping()

    # Also validate all files listed in docs-sync.yml (catches docs outside docs/ and READMEs)
    for reg in registered:
        if reg not in EXCLUDE_PATHS and (REPO_ROOT / reg).exists():
            docs.add(reg)

    for doc in sorted(docs):
        fm = parse_frontmatter(doc)

        if fm is None:
            errors.append(f"{doc}: missing YAML frontmatter (---)")
            continue

        if "source_files" not in fm or not fm["source_files"]:
            errors.append(f"{doc}: frontmatter missing 'source_files'")
        if "related_docs" not in fm:
            errors.append(f"{doc}: frontmatter missing 'related_docs'")

        if doc not in registered:
            errors.append(f"{doc}: not registered in docs/docs-sync.yml")

    # Stale entries in docs-sync.yml
    for reg in sorted(registered):
        if not (REPO_ROOT / reg).exists():
            errors.append(f"docs/docs-sync.yml: references non-existent file '{reg}'")

    if errors:
        print("Documentation frontmatter check failed:\n")
        for e in errors:
            print(f"  - {e}")
        print(f"\n{len(errors)} error(s) found.")
        sys.exit(1)

    print(f"All {len(docs)} documentation files have valid frontmatter and are registered.")
    sys.exit(0)


if __name__ == "__main__":
    main()
