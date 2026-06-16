#!/usr/bin/env bash
#
# render-diagrams.sh — render the book's D2 diagram sources to committed SVG.
#
# The workflow book draws most figures in ASCII (see DIAGRAMS.md). A short
# enumerated set of dense figures is authored in D2 source kept in sidecar
# `.d2` files; this script renders each one to a committed `.svg` beside it,
# so readers need no build step. Run it during a production cycle whenever a
# touched chapter added or changed a figure in the enumerated SVG set.
#
# The `d2` binary is not installed in a fresh environment. The script checks
# for it first and, on a miss, prints the one-time install command and exits
# without rendering, rather than failing with an opaque "command not found".
#
# Usage: workflow-book-builder/scripts/render-diagrams.sh
#
# Exit codes:
#   0  rendered the sidecars, or found none to render (both are success)
#   2  the `d2` binary is not installed
#   3  the diagrams directory does not exist

set -euo pipefail

# Directory holding the .d2 sidecars and the rendered .svg outputs.
# Resolved relative to this script so the command works from any cwd.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
DIAGRAMS_DIR="${REPO_ROOT}/docs/workflow-book/assets/diagrams"

# Guard: d2 must be installed. On a miss, print the install command and stop.
if ! command -v d2 >/dev/null 2>&1; then
  cat >&2 <<'EOF'
render-diagrams.sh: the `d2` binary is not installed.

Install it once, then re-run this script:

  # macOS / Linux one-line installer:
  curl -fsSL https://d2lang.com/install.sh | sh -s --

  # or via Go:
  go install oss.terrastruct.com/d2@latest

See workflow-book-builder/BOOK_BRIEF.md and workflow-book-builder/DIAGRAMS.md
for the one-time install step and the diagram convention.
EOF
  exit 2
fi

if [[ ! -d "${DIAGRAMS_DIR}" ]]; then
  echo "render-diagrams.sh: diagrams directory not found: ${DIAGRAMS_DIR}" >&2
  echo "Nothing to render. Create the .d2 sidecars first (see DIAGRAMS.md)." >&2
  exit 3
fi

# Render each .d2 sidecar to a committed .svg beside it. The `nullglob`
# setting makes the loop a no-op when no sidecars exist yet, rather than
# iterating over the literal pattern string.
shopt -s nullglob
rendered=0
for src in "${DIAGRAMS_DIR}"/*.d2; do
  out="${src%.d2}.svg"
  echo "rendering: ${src} -> ${out}"
  d2 "${src}" "${out}"
  rendered=$((rendered + 1))
done

if [[ "${rendered}" -eq 0 ]]; then
  echo "render-diagrams.sh: no .d2 sidecars found in ${DIAGRAMS_DIR}; nothing to render."
else
  echo "render-diagrams.sh: rendered ${rendered} diagram(s)."
fi
