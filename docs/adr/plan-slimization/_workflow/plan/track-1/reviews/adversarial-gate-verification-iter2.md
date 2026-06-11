<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: REJECTED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: A7, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

<!-- Pure-verdict pass: no new findings surfaced. -->

## Evidence base

#### Verify A1: edit-design Step 4 cold-read can observe the log-adversarial gate state
- **Original issue**: S3 keeps the design cold-read gated behind the log-adversarial gate clearing, but `edit-design` is a separate SKILL from `create-plan`; the track named no mechanism for the gate state to cross the SKILL boundary, so a Step-4a design with an open log decision could silently reach cold-read.
- **Fix applied**: Plan of Work step 8 (track-1.md:185-190) now names the carrier explicitly: "After D6 relocates the adversarial pass onto the research log, the log itself is the cross-SKILL state carrier: `edit-design`'s Step 4a cold-read reads the log's `### Adversarial review of this log …` section and blocks while any entry is unresolved (a `NEEDS REVISION` heading with open blockers/should-fix). This is a verdict/status read at the already-sanctioned Step 4a authoring point, not a new decision-content read site, so S2 holds."
- **Re-check**:
  - Track-file location: track-1.md:185-190 (Plan of Work step 8).
  - Current state: the gate-state owner is now the log, read in-place by `edit-design` Step 4a. The unnamed-mechanism gap A1 flagged is closed — the dry-run acceptance ("No documented path … reaches a design cold-read while a log-adversarial entry is open (S3)", track-1.md:272-274) now has a concrete artifact to check against.
  - Criteria met: S3 cross-SKILL mechanism is named, not left to implementation. The original WEAK survival verdict (CH-D7-S3) is lifted.
- **S2-safety cross-check**: The new read is framed as a verdict/status read (`NEEDS REVISION` heading + open-blocker count) at the Step 4a authoring point, which the S2 acceptance bullet (implementation-plan.md:409-414) already sanctions as a decision-content authoring site. Reading the *verdict status* of the adversarial section is strictly weaker than the decision-content read S2 permits there, so it adds no new decision-content site. Cross-checked against the live `edit-design/SKILL.md:454-463` cold-read gate (mechanical-blocker-clear today) — the new gate composes additively with it, no conflict.
- **Regression check**: Checked S2 (no third decision-content read introduced — the Step 4a authoring read is pre-existing and sanctioned), the §Interfaces "Signatures and contracts" block (unchanged, no contradiction), and the Validation acceptance bullets (the S3 bullet now resolves to a concrete carrier). Clean.
- **Verdict**: VERIFIED

#### Verify A2: D19 grounded in the §1.6(h) glob set shared across all three script walks
- **Original issue**: D19's justification cited only the Phase-1 (§1.6(h)) stamp walk, while the actual protection is that the *same hardcoded glob set* is shared by all three walks (drift, migrate, normalize); a future reader adding the log to one walk's glob would break the invisibility without the track flagging the protection is glob-shared, not walk-specific.
- **Fix applied**: the `## Context and Orientation` `conventions.md` bullet (track-1.md:67-73) now reads: "That glob is not a single site: the same four-type enumeration recurs at all three script walks (drift ~391-394, migrate ~488-491, normalize ~689-692), so the protection D19 leans on is the §1.6(h) glob set shared across the three, not the Phase-1 walk alone — adding `research-log.md` to stamping would mean editing all three sites, which S1 forbids."
- **Re-check**:
  - Track-file location: track-1.md:67-73 (Context and Orientation, conventions.md bullet).
  - Codebase location: `workflow-startup-precheck.sh:391-394` (drift walk glob), `:488-491` (migrate walk glob), `:689-692` (normalize walk glob) — all three confirmed to enumerate the identical four-entry set `implementation-plan.md`, `design.md`, `design-mechanics.md`, `plan/track-*.md`, none of which matches `research-log.md`.
  - Current state: the cited line ranges match the live script exactly; the "shared across the three" framing matches the live `conventions.md:704-706` ("the drift detection, the migrate-range walk, and the no-drift normalization recompute all run it"). The under-specification A2 flagged is closed.
  - Criteria met: the fragility is now surfaced (adding the log to any one walk breaks the invariant) rather than implying only the Phase-1 walk matters.
- **Regression check**: Checked the three cited script line ranges against the live script (all accurate), and the §1.6(f) note in step 1 (track-1.md:117) which now consistently defers stamp-immunity to D19's shared-glob rationale. Clean.
- **Verdict**: VERIFIED

#### Verify A3 (REJECTED): §2.5 access wiring needs `planner`/`1` on both annotation axes
- **Rejection reason**: A3 claimed adding `planner` to the §2.5 roles axis is redundant because `reviewer-adversarial` (the gate writer) and `orchestrator` are already in the roles list, so only the phases axis needs `1`. The orchestrator REJECTED this: the Phase-1 gate-output READER is `/create-plan`, which runs as role `planner` — a role distinct from `orchestrator` and absent from the §2.5 roles list.
- **Verification of the orchestrator's counter-reasoning** (not rubber-stamped):
  - `create-plan/SKILL.md:16` reads verbatim "Your role: planner." — confirmed `/create-plan` runs as role `planner`.
  - The live §2.5 TOC row roles list (`conventions-execution.md:13`, mirrored on the subsection markers at lines 474, 483, 546) is: `orchestrator,decomposer,implementer,reviewer-dim-step,reviewer-dim-track,reviewer-plan,reviewer-technical,reviewer-risk,reviewer-adversarial`. A `grep -n "planner"` over `conventions-execution.md` returns only line 588 (unrelated prose, "the orchestrator or planner for strategic reviews") — `planner` is NOT in the §2.5 roles list.
  - The role `reviewer-plan` IS in the list but is a *distinct* role from `planner` (it is the Phase-2 plan reviewer, not the create-plan main agent). A3 conflated "planner reads via orchestrator-class routing" — but the TOC match protocol (`conventions-execution.md`-style filter, see create-plan/SKILL.md:13: "Match TOC rows where Roles contains any of your roles") is an exact per-role membership test, not class-based. A `planner`-role reader does NOT match a row that lists `orchestrator` but not `planner`.
  - Therefore the create-plan gate-output reader (role `planner`) genuinely cannot resolve the §2.5 schema under the TOC filter without `planner` added to the roles axis. A3's "redundant / over-states" claim is wrong; the orchestrator's counter holds.
- **Orchestrator's precision edit verified**: track-1.md step 4 (lines 135-148) now disambiguates both axis-readers: the phase-`1` add covers the gate *writer* (`reviewer-adversarial`, already in roles but absent from the phases axis which stops at `2,3A,3B,3C,4`), and the `planner` add covers the gate-output *reader* (`/create-plan` runs as `planner`, "a role distinct from the already-listed `orchestrator` and `reviewer-plan`"). This is factually accurate against the live files. The plan's own Integration Points (implementation-plan.md:435-436) independently names `planner`/`1` as the canonical wiring, corroborating.
- **Downstream check**: leaving A3 "fixed" the way A3 proposed (drop `planner`) would have broken the create-plan reader's §2.5 access at runtime — the orchestrator's rejection prevents a real defect. No downstream issue from the REJECT.
- **Verdict**: REJECTED (orchestrator's counter-reasoning is sound; `planner` is genuinely the Phase-1 create-plan reader role, absent from the §2.5 list and distinct from `orchestrator`/`reviewer-plan`)

#### Verify A4: Step 1c tier-aware resume branch reads no tier from a not-yet-written plan
- **Original issue**: the no-design `lite`/`minimal` resume window can occur before `implementation-plan.md` is written; the tier line (D18) lives in that absent plan, so Step 1c had no on-disk tier source and the obvious signal was unavailable in the exact window the branch exists to handle.
- **Fix applied**: track-1.md step 5 (lines 156-167) now states Step 1c "disambiguates on `implementation-plan.md` presence and its D18 tier line, never on a new log read (a Phase-0→1 tier read from the log would be a third decision-content read site and break S2)." The shape-complete stub (D1/D18) means the "`lite`/`minimal` in progress, no `design.md`" branch fires only when `implementation-plan.md` is present (tier readable there). The narrow gate-cleared-but-no-stub window "reads as 'fresh start': Step 4's classifier re-runs and re-derives the tier from the now-populated log through its existing sanctioned authoring read — no extra read site, S2 intact."
- **Re-check**:
  - Track-file location: track-1.md:156-167 (Plan of Work step 5).
  - Current state: the branch now keys on file-presence shape (`implementation-plan.md` + its tier line), not on an unreadable tier. The window A4 constructed (plan absent) is explicitly routed to "fresh start" → Step 4 classifier re-derivation, which uses the *existing* sanctioned authoring read of the log, not a new read site.
  - Criteria met: the on-disk signal is now named (`implementation-plan.md` presence + D18 tier line), closing the VS-STEP1C "no named signal" gap.
- **S2 third-read cross-check** (per spawn instruction): The S2 acceptance bullet (implementation-plan.md:409-414, mirrored at track-1.md:280-283) names exactly two decision-content read sites — Step 4a/4b authoring and the Phase-2 consistency cross-check. The fixed branch reads decision content from the log only via Step 4's classifier (a Step-4 authoring read, already one of the two sanctioned sites), NOT a new Step-1c log read. The D18 tier line read from `implementation-plan.md` is a format/marker read, not a log decision-content read. Confirmed: NO third decision-content read site is introduced. S2 holds.
- **Regression check**: Checked the live `create-plan/SKILL.md:128-175` Step 1c branches (the fix composes with the existing committed-and-clean `design.md` proxy logic without contradicting it), the D18 placement note (implementation-plan.md:370-382, "the stub template must include it" — so the tier line is readable whenever the stub exists), and the S2 invariant. Clean.
- **Verdict**: VERIFIED

#### Verify A5: sizing justification leads with subject cohesion, file count secondary
- **Original issue**: the justification led with "Thirteen in-scope files — above the merge floor," which is the weakest evidence (13 clears the soft ~12 floor by one); the load-bearing argument is the conventions-execution §2.5/§2.1 subject straddle.
- **Fix applied**: the §Interfaces sizing justification (track-1.md:327-336) now opens "The load-bearing reason this track is not folded into a neighbor is subject cohesion, not the file count," develops the §2.5-here / §2.1-Track-2 straddle, and only then states "Thirteen in-scope files sits one above the soft ~12 merge floor … so the count alone never forced a merge."
- **Re-check**:
  - Track-file location: track-1.md:327-336 (Sizing justification).
  - Current state: subject cohesion leads; file count is explicitly demoted to "the count alone never forced a merge." The CH-SIZING "leads with the weakest evidence" concern is resolved.
  - Criteria met: the merge-candidate test (`planning.md:484-488`, ≤~12 AND folds-cleanly) is now correctly anchored on the fold-blocking subject-coherence argument, not the count.
- **Regression check**: Checked that the in-scope file list (track-1.md:305-317, thirteen entries) still matches the count cited in the justification — 13 entries, consistent. Clean.
- **Verdict**: VERIFIED

#### Verify A6: new decision-cited-without-rationale check tested against the frozen design.md and exempts the References footer
- **Original issue**: the new "decision cited without rationale" check (step 12b) runs live on this branch before Phase 4; the frozen `design.md`'s `### References` footers list D-codes as bare citations, which is precisely the shape the new check targets — and acceptance only tested the footer-spelling half (a), risking the branch's own design turning red mid-flight (I6-adjacent self-destabilization).
- **Fix applied**: step 12 (track-1.md:213-219) now states the new check "is scoped so the legacy References footer never trips it, and the acceptance exercises the new check against the frozen `design.md`, not only the footer-spelling half." The acceptance bullet (track-1.md:275-279) is rewritten: "The live `design-mechanical-checks.py` passes against this branch's frozen `design.md` (old footer) and against a synthetic doc using the new footer, **with the new decision-cited-without-rationale check active in both runs** — the frozen `design.md`'s bare-`D<N>` References footer does not trip the new check."
- **Re-check**:
  - Track-file location: track-1.md:213-219 (step 12) and 275-279 (acceptance).
  - Codebase location: `design-mechanical-checks.py:886-933` confirms the `in_references` exemption precedent exists in `check_dsc_parenthetical_asides` (toggles on `### References` / `**References.**`, lines 924-928); the frozen `design.md` has `### References` footers at lines 293, 374, 574 with bare D-codes — the exact shape the new check must exempt.
  - Current state: the References exemption is now stated, and acceptance tests the new check against the live frozen design (not just the footer-spelling half). The AT-MECH-LIVE "HOLDS *iff* the check exempts References, but the track doesn't state it" gap is closed — the exemption is now explicit and tested.
  - Criteria met: the I6-adjacent self-destabilization risk is mitigated by an explicit exemption mirroring the existing `in_references` carve-out, and the acceptance demonstrates (not assumes) safety.
- **Regression check**: Checked that the backward-compatibility claim for footer-spelling (a) is still present and now two-sided (track-1.md:214-217), and that the live-edit scope (`design-mechanical-checks.py` is the only live edit, S1 untouched) is unchanged. Clean.
- **Verdict**: VERIFIED

#### Verify A7: vocabulary-first ordering rationale scoped to authoring-time, not runtime
- **Original issue**: the ordering rationale "precedes everything that cites it" could be misread as runtime-resolution protection; in fact this `full`-tier branch runs its own Phase 0→1 gate against the *live* develop-state workflow (D13/I6), so the new vocabulary is not live until Phase 4 promotion.
- **Fix applied**: the ordering-constraints paragraph (track-1.md:223-225) now reads: "step 1 (vocabulary) precedes everything that cites it — this protects staged-artifact consistency at authoring time, not runtime; this `full`-tier branch's own gate runs against the live develop-state workflow per I6, not the staged vocabulary."
- **Re-check**:
  - Track-file location: track-1.md:223-225 (Ordering constraints).
  - Current state: the scope clause is added — authoring-time staged-artifact consistency vs runtime live-develop-state execution. The CH-VOCAB-ORDER "reads as if it governs runtime resolution" concern is resolved.
  - Criteria met: consistent with I6 (live `.claude/**` stays at develop until Phase 4 promotion); a rationale-precision fix with no mechanism change, exactly as the suggestion proposed.
- **Regression check**: Checked the I6 invariant statement (implementation-plan.md:422-424, "live workflow at develop until promotion") — the new clause is consistent with it. Checked the staged-read precedence note (staged mirror absent → reads fall back to live) — consistent. Clean.
- **Verdict**: VERIFIED

---

**Summary: PASS** — All seven prior adversarial findings are resolved: A1/A2/A4/A5/A6/A7 VERIFIED (fixes applied correctly, no regressions, S2/S3/I6 invariants intact), A3 REJECTED (the orchestrator's counter-reasoning verified sound — `planner` is genuinely the Phase-1 create-plan gate-output reader role, confirmed at `create-plan/SKILL.md:16`, and is absent from the live §2.5 roles list and distinct from both `orchestrator` and `reviewer-plan`, so the `planner` roles-axis add is correct, not redundant). No new findings surfaced; no regressions introduced.
