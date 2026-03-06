#!/usr/bin/env python3
"""Validate that documentation files have YAML frontmatter, are registered
in docs-sync.yml, and that all cross-references resolve to existing files."""

import sys
import re
from pathlib import Path

import yaml

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
    """Return parsed YAML frontmatter dict, or None if no frontmatter."""
    text = (REPO_ROOT / path).read_text(encoding="utf-8")
    match = re.match(r"^---\n(.*?\n)---\n", text, re.DOTALL)
    if not match:
        return None
    try:
        return yaml.safe_load(match.group(1))
    except yaml.YAMLError:
        return None


def load_sync_mappings():
    """Return full mappings list and set of doc paths from docs-sync.yml."""
    with open(DOCS_SYNC, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    mappings = data.get("mappings", [])
    registered = {Path(m["doc"]) for m in mappings}
    return mappings, registered


def check_pattern(pattern):
    """Check a source file pattern. Returns None if valid, or an error description.

    - Exact paths (no wildcards): must exist as a file.
    - Glob patterns with wildcards: the base directory (portion before the first
      wildcard) must exist. This allows patterns like 'module/src/main/java/**'
      to pass when the directory exists but is currently empty, while catching
      patterns whose base directory has been moved or deleted.
    """
    if "*" not in pattern and "?" not in pattern:
        # Exact file path
        if not (REPO_ROOT / pattern).exists():
            return f"'{pattern}' references non-existent file"
        return None

    # Glob pattern: verify base directory exists
    parts = Path(pattern).parts
    base_parts = []
    for part in parts:
        if "*" in part or "?" in part:
            break
        base_parts.append(part)

    if base_parts:
        base_dir = REPO_ROOT / Path(*base_parts)
        if not base_dir.exists():
            return f"'{pattern}' base directory '{Path(*base_parts)}' does not exist"

    return None


def check_references(errors, doc, fm):
    """Validate that related_docs and source_files references are not broken."""
    # Check related_docs point to existing files
    for ref in fm.get("related_docs", []) or []:
        if not (REPO_ROOT / ref).exists():
            errors.append(f"{doc}: related_docs references non-existent '{ref}'")

    # Check source_files patterns are not broken
    for pattern in fm.get("source_files", []) or []:
        err = check_pattern(pattern)
        if err:
            errors.append(f"{doc}: source_files {err}")


def check_sync_patterns(errors, mappings):
    """Validate that source_patterns in docs-sync.yml are not broken."""
    for mapping in mappings:
        doc = mapping["doc"]
        for pattern in mapping.get("source_patterns", []):
            err = check_pattern(pattern)
            if err:
                errors.append(f"docs/docs-sync.yml ({doc}): source_patterns {err}")


def main():
    errors = []
    docs = find_docs()
    mappings, registered = load_sync_mappings()

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

        check_references(errors, doc, fm)

    # Stale entries in docs-sync.yml
    for reg in sorted(registered):
        if not (REPO_ROOT / reg).exists():
            errors.append(f"docs/docs-sync.yml: references non-existent file '{reg}'")

    # Validate source_patterns in docs-sync.yml
    check_sync_patterns(errors, mappings)

    if errors:
        print("Documentation frontmatter check failed:\n")
        for e in errors:
            print(f"  - {e}")
        print(f"\n{len(errors)} error(s) found.")
        sys.exit(1)

    print(f"All {len(docs)} documentation files have valid frontmatter and are registered.")
    print("All cross-references and glob patterns resolve to existing files.")
    sys.exit(0)


if __name__ == "__main__":
    main()
