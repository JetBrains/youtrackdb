<!-- MANIFEST
findings: 2
severity: { blocker: 0, should-fix: 0, suggestion: 2 }
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - { id: WH1, sev: suggestion, loc: "workflow-book-builder/scripts/render-diagrams.sh:44-48,62-66", anchor: "WH1", cert: n/a, basis: judgment }
  - { id: WH2, sev: suggestion, loc: "workflow-book-builder/scripts/render-diagrams.sh:26-48", anchor: "WH2", cert: n/a, basis: judgment }
-->

## Findings

### WH1 [suggestion] Two guards use POSIX `[ ... ]` in an otherwise bash-idiomatic script

**Location:** `workflow-book-builder/scripts/render-diagrams.sh:44` and `:62`.

**Issue:** The script opens with `#!/usr/bin/env bash` and uses bash features throughout (`BASH_SOURCE`, `shopt -s nullglob`, `$(( ))`), but the directory-exists guard (`if [ ! -d "${DIAGRAMS_DIR}" ]`) and the rendered-count guard (`if [ "${rendered}" -eq 0 ]`) use the POSIX single-bracket test instead of `[[ ... ]]`. Both work correctly here because every operand is quoted, so there is no bug, only a style inconsistency with the project's bash-conditional convention. The render loop is correct and safe regardless.

**Proposed fix:** Use `[[ ! -d "${DIAGRAMS_DIR}" ]]` and `[[ "${rendered}" -eq 0 ]]` to match the rest of the script. No behavioral change.

### WH2 [suggestion] Missing-binary and missing-directory failures share exit code 1

**Location:** `workflow-book-builder/scripts/render-diagrams.sh:41` (missing `d2`) and `:47` (missing diagrams directory).

**Issue:** Both failure paths exit `1`. Each prints a distinct, unambiguous stderr message, so an operator reading the output is never confused — the graceful-failure requirement (acceptance criterion 5, D6) is fully met. The only gap is machine-distinguishability: a caller that wanted to branch on "tool not installed" versus "nothing to render" by exit code alone cannot. For an operator-run helper this is marginal, which is why it is a suggestion, not a should-fix.

**Proposed fix (optional):** If a future caller scripts around this helper, give the two causes distinct codes (for example `exit 2` for missing `d2`, `exit 3` for the missing directory) and document them in the header comment. Skip if the script stays purely hand-run.
