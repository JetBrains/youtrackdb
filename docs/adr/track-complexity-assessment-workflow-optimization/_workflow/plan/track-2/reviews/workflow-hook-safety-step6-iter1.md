<!--MANIFEST
dimension: workflow-hook-safety
step: 6
track: 2
iteration: 1
prefix: WH
findings_total: 1
blockers: 0
evidence_base:
  certs: 0
cert_index: []
flags: []
index:
  - id: WH1
    sev: Minor
    anchor: "#wh1-minor--non-discriminating-routing-tests-rule_7-rule_6"
    loc: "docs/adr/track-complexity-assessment-workflow-optimization/_workflow/staged-workflow/.claude/scripts/tests/test_workflow_reindex.py:99-172"
    cert: n/a
    basis: script
-->

## Findings

### WH1 [Minor] — Non-discriminating routing tests (rule_7, rule_6)

- **File:** `docs/adr/track-complexity-assessment-workflow-optimization/_workflow/staged-workflow/.claude/scripts/tests/test_workflow_reindex.py` (lines 99-172)
- **Axis:** Python script (test quality / non-vacuity)
- **Cost:** two of the four new tests pass even with the fix reverted, so they do not guard the core partition behavior they appear to test
- **Issue:** I ran a non-vacuity probe — forced `_is_staged_nonanchor_skill` to always return `False` (partition disabled) and re-ran the four new cases. Result:
  - `test_staged_nonanchor_skill_no_toc_no_over_fire` **FAILS** (emits `rule_2` + 3× `rule_4`) — this is the load-bearing discriminator and it works.
  - `test_staged_anchor_skill_malformed_toc_still_fails` **PASSES** (correct — anchor skills validate regardless of the partition; it proves the fix is not a blanket exemption).
  - `test_staged_nonanchor_skill_rule_7_silent_when_no_bootstrap` **PASSES even with the partition disabled.** `rule_7` is gated by `parsed.path not in bootstrap_paths` (script line 2302), and a staged non-anchor skill is never added to `bootstrap_paths` in either branch, so the assertion holds whether or not the file is partitioned out of `parsed_files`.
  - `test_staged_nonanchor_skill_rule_6_still_fires_on_bare_ref` **PASSES even with the partition disabled.** `check_rule_6_cross_file_refs` runs in both the `parsed_files` loop and the `parsed_agent_files` loop (script lines 2524, 2534), so a bare ref fires `rule_6` in either scope.

  These two tests are valid guardrails against a *future* routing regression (e.g. if someone later routed staged non-anchor skills out of validation entirely, `rule_6` would stop firing), but they do not discriminate the Step-6 fix as shipped. Only `no_toc_no_over_fire` does. This is a test-design nuance, not a correctness defect — the partition itself is correct and the anchor test plus the over-fire test together establish non-vacuity for the load-bearing behavior.
- **Suggestion:** Optional. Leave as-is (the tests are harmless and add regression coverage), or add a one-line comment on the `rule_7`/`rule_6` cases noting they assert routing invariants that survive the partition rather than discriminating it, so a future reader does not mistake them for fix-vacuity guards.

## Evidence base
