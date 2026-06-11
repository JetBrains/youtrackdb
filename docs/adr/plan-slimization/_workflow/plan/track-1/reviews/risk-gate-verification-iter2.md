<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

<!-- No new findings surfaced during gate verification. -->

## Verification certificates

#### Verify R1: third-scope gate reviewer can read `conventions-execution.md` §2.5
- **Original issue** (should-fix): The relocated Phase-0→1 adversarial gate runs as `reviewer-adversarial`/`planner` at phase `1` and must read §2.5 to emit its manifest-plus-sections file, but the live §2.5 TOC row carries phases `2,3A,3B,3C,4` (no `1`) and no `planner` role. Step 4 is the corrective axis edit; the risk was a sequencing hazard — step 5 (gate-spawn wiring) landing before step 4 (axis extension) would strand the schema read — plus an acceptance gap (the TOC-readability precondition was not asserted).
- **Fix applied**: The `## Plan of Work` ordering-constraints paragraph (track-1.md:228-230) now elevates step 4's phase-`1`/`planner` axis edit to a **hard precondition**: "without it the Phase-1 gate writer and the create-plan reader cannot resolve §2.5 under the TOC filter, so a step-5 spawn landing before step 4 would strand the schema read." A new `## Validation and Acceptance` bullet (track-1.md:267-271) asserts the staged §2.5 row resolves under the TOC filter for `reviewer-adversarial`@phase`1` (writer) and `planner`@phase`1` (create-plan reader), reachable from the staged Step-5 wiring with step 4 preceding step 5.
- **Re-check**:
  - Track-file location: ordering constraints at track-1.md:228-230; acceptance bullet at track-1.md:267-271.
  - Live-state confirmation: `grep -nE '2,3A,3B,3C,4|planner|reviewer-adversarial'` over `conventions-execution.md` — the §2.5 TOC row (`:13`) and the three used subsection annotations (`:14`, `:15`, `:474`, `:483`, `:546`) all carry phases `2,3A,3B,3C,4` (no `1`) and a roles list with `reviewer-adversarial` but no `planner`. The original CONTRADICTED-for-live-state premise (E1) still holds, confirming step 4 is the genuine corrective edit and the finding was correctly a sequencing/acceptance hazard, not an unaddressed gap.
  - Current state vs original issue: the ordering constraint now names the precondition explicitly ("hard precondition", strand consequence spelled out) rather than the prior softer "precede or accompany"; the acceptance bullet names both reader axes (`reviewer-adversarial`@`1` writer + `planner`@`1` create-plan reader) and the step-4-precedes-step-5 reachability walk. A dry-run read now catches a step-5-without-step-4 split.
  - Criteria met: the sequencing hazard is closed by the hard-precondition wording; the acceptance gap is closed by the explicit TOC-readability assertion naming both axes and the spawn-site reachability.
- **Regression check**: Checked the surrounding ordering-constraints paragraph (track-1.md:223-234) and the invariants line — S1/S2/S3/I6 statements are intact and unchanged; the new hard-precondition clause is additive prose, introduces no contradiction with the "Steps 6-9 order-flexible / step 12 independent" clause. The new acceptance bullet sits cleanly among the other acceptance bullets and does not duplicate or conflict with the existing "staged third scope is reachable … D17 output path" bullet (track-1.md:264-266) — they are complementary (spawn reachability vs TOC readability). Clean.
- **Verdict**: VERIFIED

#### Verify R2: D11 decision-cited-without-rationale check exercised against the frozen `design.md`
- **Original issue** (suggestion): The D11 footer rename and the net-new decision-cited-without-rationale check land on the live `design-mechanical-checks.py`; the net-new check has no prior shape to inherit a test from. The risk was that step 12 and the acceptance bullet would cover only the footer-spelling half (old + new footer pass) and leave the new check unexercised against the branch's own frozen `design.md`.
- **Fix applied**: Step 12 (track-1.md:211-221) now states backward compatibility is "two-sided and both sides are tested" and that "the acceptance exercises the new check against the frozen `design.md`, not only the footer-spelling half." The acceptance bullet (track-1.md:275-279) now requires "the new decision-cited-without-rationale check active in both runs — the frozen `design.md`'s bare-`D<N>` References footer does not trip the new check."
- **Re-check**:
  - Track-file location: step 12 at track-1.md:211-221; acceptance bullet at track-1.md:275-279.
  - Current state vs original issue: both the step and the acceptance now name the new check explicitly and require it active against the frozen `design.md`, with the concrete negative-pass criterion (bare-`D<N>` References entries must not trip it). This covers the net-new check the E2 evidence flagged as lacking an inherited test shape.
  - Criteria met: the regression guard for the net-new check is now named in both the step description and the acceptance criterion; the footer-spelling-only gap is closed.
- **Regression check**: Checked that step 12 still preserves S1 (the fixture is "a new file" and "No existing test file is modified" — track-1.md:219-221) and that the in-scope file list still marks `design-mechanical-checks.py` as the live backward-compatible edit (track-1.md:316). Clean.
- **Verdict**: VERIFIED

#### Verify R3: `minimal`-stub acceptance walks all four documented State transitions
- **Original issue** (suggestion): `section_first_checkbox_token` is a total closed-enum parse that `parse_error`-exits on a malformed glyph, so a fixture asserting only the initial State 0 read would miss a stub that strands a later transition. The finding asked the fixture to walk all four documented transitions (initial State 0; Plan Review `[x]` → State A/C; track `[x]` → walks; Final Artifacts `[x]` → State D/Done).
- **Disposition**: ALREADY COVERED (no edit) — verify the bullet is present and walks all four transitions.
- **Re-check**:
  - Track-file location: acceptance bullet at track-1.md:259-263.
  - Current state: the bullet has the fixture "asserts a readable state" (the initial State 0 read across the three closed-enum sections) "and walks the post-review transitions: Plan Review flipped to `[x]` yields State A/C, track and Final Artifacts flipped yield State D/Done." This covers the four transitions the disposition named — initial readable State 0, Plan Review→State A/C, and the track + Final Artifacts flips → State D/Done — condensed into the documented-walk phrasing.
  - Criteria met: the fixture exercises all three closed-enum section reads (`## Plan Review`, `## Checklist`, `## Final Artifacts`) and the full post-review transition sequence, not just the initial read; the parse_error-exit hazard is covered because each flipped section is read in turn.
- **Regression check**: No edit was made for R3, so no fix-introduced regression is possible. Confirmed the bullet is unchanged and present. Clean.
- **Verdict**: VERIFIED

#### Verify R4 (accepted residual): absorption/fidelity have no mechanical backstop
- **Rejection reason / disposition**: ACCEPTED RESIDUAL (no edit) — absorption-completeness and full-tier seed↔track fidelity are semantic checks performed by the retargeted cold-read sub-agent; S3 is a documentation-only reachability property. The plan records this as D8's explicitly accepted residual. The verification task is to confirm the track does not falsely claim a mechanical backstop exists.
- **Downstream check**: Re-read step 8 (track-1.md:181-193) and step 9 (track-1.md:194-201): both describe absorption and fidelity as criteria carried by the cold-read prompt/sub-agent (semantic judgment), with no claim of a script or gate enforcing them. The S3 acceptance bullet (track-1.md:272-274) reads "No documented path in the staged `edit-design`/`create-plan` flow reaches a design cold-read while a log-adversarial entry is open" — a documentation/reachability walk, not a runtime or mechanical assertion. No track text claims a mechanical backstop for absorption/fidelity; the framing is consistently authoring-time discipline (D8 accepted residual; post-authoring divergence owned by the Track 2 propagation duty). The accepted-design-tradeoff characterization is sound, not an unaddressed defect.
- **Verdict**: REJECTED (no action needed — accepted residual correctly framed; no downstream issue)

---

**Summary**: PASS. All four iteration-1 risk findings VERIFIED/REJECTED as dispositioned; no regressions introduced by the R1/R2 edits; no new findings surfaced.
