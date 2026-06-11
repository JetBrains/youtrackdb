<!--
MANIFEST
dimension: workflow-hook-safety
target: track-1 (full diff, steps 1-6); in-scope subset = .claude/scripts/** live code
range: 2775833bc33bab3d8acc1f3dd34a219e8ebe5ea7..HEAD
findings: 2
evidence_base:
  certs: 0
index:
  - id: WH1
    sev: Minor
    anchor: "WH1 [Minor] Stale `### References` footer name in per-section-length advice text"
    loc: ".claude/scripts/design-mechanical-checks.py:1150"
    cert: n/a
    basis: script
  - id: WH2
    sev: Minor
    anchor: "WH2 [Minor] Stub-precheck `run_precheck` has no subprocess timeout"
    loc: ".claude/scripts/tests/test_workflow_startup_precheck_stub.py:83"
    cert: n/a
    basis: judgment
flags: []
-->

## Findings

### WH1 [Minor] Stale `### References` footer name in per-section-length advice text

- File: `.claude/scripts/design-mechanical-checks.py` (line 1150)
- Axis: JSON validity / hook output correctness (live-script user-facing message)
- Cost: an author who trips the `per-section-length` blocker on a post-rename design is told to "reference via the `### References` footer" — the pre-D11 footer name the same script no longer requires; mild advice drift, zero detection-logic impact.
- Issue: the `per-section-length` blocker's description string still names the old footer spelling: `"Move long-form material to design-mechanics.md and reference via the ### References footer."` D11 renamed the canonical footer to `### Decisions & invariants` and the detection regexes (`FOOTER_HEADING_RE`, `FOOTER_HEADING_SEARCH_RE`) now accept both spellings, but this advice string was not updated alongside them. The literal at line 1182 (`check_dsc_parenthetical_asides` docstring, "inside the `### References` block") is the same residue. Detection is unaffected — both spellings still match — so this is cosmetic, not a correctness bug. Note: the script change is otherwise fully backward-compatible. I confirmed by running the live script against this branch's frozen `design.md` (legacy `### References` footers, inline-rationale codes): verdict PASS, 0 blockers, 0 `decision-cited-without-rationale` findings, only one pre-existing `per-section-length` suggestion unrelated to D11. The S1 invariant also holds: `git diff` over the range shows `workflow-startup-precheck.sh` and `test_workflow_startup_precheck.py` byte-unchanged; the `.claude/scripts/**` delta is the script edit plus additive new test/fixture files only.
- Suggestion: update the two advice/docstring literals to the new footer name (or to a both-spellings phrasing such as "the `### Decisions & invariants` footer") so the live script's guidance tracks the rename it implements. Cosmetic; safe to defer or fold into the Phase-C consistency batch.

### WH2 [Minor] Stub-precheck `run_precheck` has no subprocess timeout

- File: `.claude/scripts/tests/test_workflow_startup_precheck_stub.py` (line 83, `run_precheck`)
- Axis: error handling (test robustness against a wedged subprocess)
- Cost: under a hung `bash workflow-startup-precheck.sh` child, the test would block indefinitely on CI rather than failing fast; in practice bounded by the script's own internal `timeout 10 git fetch`, so a true hang is improbable.
- Issue: the D11 test's `run_script` sets `timeout=60` on its `subprocess.run` and converts `TimeoutExpired` into a clear `SystemExit` ("possible infinite loop"). The stub-precheck test's `run_precheck` omits any timeout, so a wedged precheck child (e.g. a stalled git operation that escapes the script's own `timeout 10` guard) would hang the runner with no outer bound. The realistic risk is low: the fixture's upstream is a local `file://` bare repo (no real network), `--mode full`'s only network-facing step is `git fetch`, and the script wraps that in `timeout 10` with a `fetch-failed` fallthrough. Cleanup and `/tmp` isolation are otherwise correct — `tempfile.TemporaryDirectory(prefix="precheck-stub-")` gives each run an `mkdtemp`-unique dir and the context-manager `__exit__` guarantees teardown. I verified empirically: three concurrent runs all pass with zero orphan `precheck-stub-*` dirs left in `/tmp`.
- Suggestion: add a `timeout=` to the `subprocess.run` in `run_precheck` (mirroring the D11 test's 60s + `TimeoutExpired` handling) so a wedged child surfaces as a fast, clear failure instead of a hang. Optional hardening; the script's internal `timeout 10` already covers the only plausible stall path.

## Evidence base
