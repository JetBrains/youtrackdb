<!-- MANIFEST
findings: 1   severity: {Critical: 0, Recommended: 0, Minor: 1}
index:
  - {id: WC1, sev: Minor, loc: "docs/adr/drift-walk-fix/_workflow/staged-workflow/.claude/scripts/tests/test_workflow_startup_precheck.py:1355", anchor: "### WC1 ", cert: n/a, basis: "stale line anchor :619 in committed test docstring points at a blank line and disagrees with the track file's :618 framing of the same boundary"}
evidence_base: {section: "## Evidence base", certs: 0}
flags: [CONTRACT_OK]
-->

## Findings

### WC1 [Minor] Test docstring's `:612`-before-`:619` anchor points at a blank line and disagrees with the track file's `:618` framing

- **File:** `docs/adr/drift-walk-fix/_workflow/staged-workflow/.claude/scripts/tests/test_workflow_startup_precheck.py` (line 1355)
- **Axis:** cross-file rule restatement (committed line anchor vs track file vs staged script)
- **Cost:** stale line anchor baked into a committed artifact; a future reader resolving `:619` against the staged script lands on whitespace and the same boundary carries two different numbers across files.
- **Issue:** The new test `test_drift_phase4_empty_input_returns_kind_null_before_skip2` closes its docstring with "This pins the `:612`-before-`:619` ordering the skip-#2 placement depends on." Three things misalign at the `:619` end of that anchor:
  - In the staged script (`.../staged-workflow/.claude/scripts/workflow-startup-precheck.sh`, the §1.7(d)-resolved copy), line **619 is a blank line**. The empty-input return body is at 615-617, its closing `fi` at 618, and the new skip-#2 block runs 620-641 with its `return` at 640. So `:619` names neither the empty-input return it is meant to sit below nor the skip-#2 block it is meant to sit above — it points at whitespace.
  - The track file frames the *same* boundary as `:612`-before-**`:618`** (track-1.md:137 "the `:612`-before-`:618` ordering", and D1/Plan-of-Work/Concrete-Steps consistently call the empty-input return `:618`). The test docstring uses `:619` for what the track file calls `:618`, so the two in-repo records of one invariant cite different line numbers.
  - The Step 1 acceptance "Implementation pin (T2)" (track-1.md:309) also says "below the new block's `:619` return", reusing the same `:619`; the new block's actual return is at staged-script line 640. The `:619` figure traces to the pre-edit baseline (develop line 619 was the blank line before the unstamped check), which became stale the moment the 23-line block was inserted above it.
  - Referent: the empty-input-before-skip-#2 ordering boundary, which resolves to staged-script lines 618 (`fi`) / 620 (skip-#2 comment), not 619.
- **Suggestion:** Drop the hardcoded line numbers from the committed test docstring (line anchors in code that the same commit shifts are rot-by-construction), e.g. "This pins the empty-input-return-before-skip-#2 ordering the skip-#2 placement depends on." If a numeric anchor is wanted, make it match the track file's `:618`/`:620` framing rather than `:619`. The test's assertions are correct and behavior is unaffected; this is a prose-anchor cleanup only.

## Evidence base
