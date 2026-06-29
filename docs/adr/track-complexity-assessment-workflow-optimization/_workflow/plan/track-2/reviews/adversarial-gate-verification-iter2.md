<!-- MANIFEST
overall: PASS
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, prior_sev: should-fix, verdict: VERIFIED, cert: "Verify-A1"}
  - {id: A2, prior_sev: should-fix, verdict: VERIFIED, cert: "Verify-A2"}
  - {id: A3, prior_sev: should-fix, verdict: VERIFIED, cert: "Verify-A3"}
  - {id: A4, prior_sev: suggestion, verdict: VERIFIED, cert: "Verify-A4"}
  - {id: A5, prior_sev: suggestion, verdict: VERIFIED, cert: "Verify-A5"}
cert_index:
  - {id: "Verify-A1", verdict: VERIFIED, anchor: "#### Verify A1 "}
  - {id: "Verify-A2", verdict: VERIFIED, anchor: "#### Verify A2 "}
  - {id: "Verify-A3", verdict: VERIFIED, anchor: "#### Verify A3 "}
  - {id: "Verify-A4", verdict: VERIFIED, anchor: "#### Verify A4 "}
  - {id: "Verify-A5", verdict: VERIFIED, anchor: "#### Verify A5 "}
flags: [CONTRACT_OK]
-->

# Track 2 adversarial review — gate verification (iteration 2)

Overall: PASS. All five iteration-1 findings (A1/A2/A3 should-fix, A4/A5
suggestion) were ACCEPTED and the fixes landed correctly in the track file. The
fixes are plan-side edits to `plan/track-2.md` — the four out-of-scope live
files still carry their dangling references in the develop-state tree, which is
correct: Track 2 has not entered Phase B, and the track file now enumerates them
as in-scope re-key targets to be edited during implementation. The scope widening
(four files added to `## Interfaces`, two repo-wide reframes of the no-dangling
verification) introduces no contradiction with the immutable Decision Records or
the §1.7(b) staging rule. No regression. No new finding.

Verification ran against the live develop-state tree (IDE reachable, project
`track-complexity-assessment-workflow-optimization` matches the working tree;
ledger `s17=workflow-modifying`). These are workflow-prose path/anchor references
(`.claude/**` markdown + agent files), not Java symbols, so grep is the correct
tool under the workflow-machinery criteria — no PSI caveat applies. Fable 5 was
unavailable; this pass ran on Opus (D14 documented degradation).

#### Verify A1: four out-of-scope live files carry removed-agent / retired-`BC` references
- **Original issue**: `execute-tracks/SKILL.md:109`, `review-workflow-consistency.md:63`, `review-workflow-instruction-completeness.md:24`, and `dimensional-review-gate-check.md:43` name removed agents or the retired `BC` prefix, yet sat outside Track 2's enumerated in-scope set, so promotion would leave the live tree naming agents that no longer exist.
- **Fix applied**: all four added to the `## Interfaces and Dependencies` in-scope list with their re-key role and an "Added at Phase A" tag (track-2.md:529-541); Plan of Work step (4) (track-2.md:380-388) names all four explicitly as part of the "repo-wide sweep over all `.claude/**`".
- **Re-check**:
  - Track-file location: `## Interfaces and Dependencies` lines 529-541 + `## Plan of Work` step (4) lines 380-388.
  - Current state: `execute-tracks/SKILL.md` ("re-key the step-level-baseline description … Load-bearing prose, not an illustration"), `review-workflow-consistency.md` ("re-key its dangling-reference worked example off the removed `review-bugs-concurrency`"), `review-workflow-instruction-completeness.md` ("re-key its `review-test-completeness` self-analogy onto the merged `review-test-quality`"), `dimensional-review-gate-check.md` ("refresh the retired-`BC` finding-prefix example (`BC3`)"). Each is now an enumerated edit target. The live-tree dangling refs (confirmed still present at the four cited lines) are now owned, not orphaned.
  - Criteria met: the realized blast radius is now fully inside the enumerated scope; the load-bearing `execute-tracks/SKILL.md` site is flagged as such.
- **Regression check**: checked Track 1's in-scope and Track 2's out-of-scope lists — none of the four files is claimed by Track 1, so no double-ownership. Track 2's out-of-scope "consistency / structural review prompts (the ledger schema, resume routing, Phase-1 artifact existence)" refers to Track 1's plan-review prompt concerns, distinct from the consistency *agent* file's dangling-ref example now in Track 2's scope. §1.7 staging block (track-2.md:554-562) already covers all `.claude/**` adds/edits, so the four new entries stage identically. Clean.
- **Verdict**: VERIFIED

#### Verify A2: `inline-replanning.md` re-key is a mechanism rewrite plus a hard-failure write, not a one-clause vocabulary swap
- **Original issue**: Plan of Work step (6) described the `inline-replanning.md` edit as one clause ("the tier-escalation path → complexity"), naming neither the D11/D12 single-tier-model rewrite (live lines 141-195) nor the literal `--append-ledger --tier <new-tier>` write at line 169 that `exit 2`s after Track 1 drops `--tier`.
- **Fix applied**: step (6) (track-2.md:408-416) now names both — the literal write ("line ~169 runs `workflow-startup-precheck.sh --append-ledger --tier <new-tier>`, a flag Track 1 removed, so after promotion that invocation `exit 2`s mid-escalation") and the model rewrite ("the whole D11/D12 'tier upgrade rides ESCALATE' mechanism … is built on a single-tier model the unbundling dissolves, so re-express the escalation in axis terms (a `design_gate` flip and/or a per-track tag raise written through the new flags), not a mechanical `tier`→`complexity` search-replace").
- **Re-check**:
  - Track-file location: `## Plan of Work` step (6) lines 408-416; cross-referenced by the in-scope entry at lines 510-513.
  - Live ground truth: `inline-replanning.md:169` is exactly `--append-ledger --tier <new-tier>` (grep-confirmed, the only such write site); lines 141-195 carry the "Tier upgrade rides this same path", "Materialize first, then write the upgraded tier", ledger-append, and rollback prose — all premised on a single escalating `tier` value. The fix names the line number, the `exit 2` failure mode, and the axis-term re-expression requirement.
  - Criteria met: the highest-risk single edit is now described in axis terms with both the behavioral write and the conceptual rewrite called out.
- **Regression check**: checked the in-scope `## Interfaces` entry (510-513) for consistency with step (6) — both now name the `--append-ledger --tier` writer and the escalation-model re-expression; no drift. The "No surviving tier read or write" acceptance bullet (470-474) and the no-dangling invariant (627-628) already cover the `--append-ledger --tier` writer. Clean.
- **Verdict**: VERIFIED

#### Verify A3: the no-dangling invariant's verification was scoped to five mirror sites
- **Original issue**: the `## Invariants & Constraints` no-dangling bullet verified the invariant only "across the five selection mirror sites", so a dangling ref in an out-of-scope file (A1's four) was constructively uncatchable by the stated grep.
- **Fix applied**: the no-dangling bullet now verifies repo-wide; the `## Validation and Acceptance` "No dangling roster references" bullet aligned.
- **Re-check**:
  - Track-file location: `## Invariants & Constraints` lines 623-629 and `## Validation and Acceptance` lines 464-469.
  - Current state: invariant reads "No dangling reference to a removed agent … survives anywhere under `.claude/**` in promoted state (the five selection mirror sites are the densest cluster, not the whole surface) … verified by the consistency review and repo-wide greps." Acceptance bullet reads "A repo-wide grep over all `.claude/**` (staged copies for in-scope files, the live tree otherwise) finds no reference to a removed agent … The five selection mirror sites are the densest cluster, not the whole surface." Both verifications are now repo-wide, matching A1's scope-side widening.
  - Criteria met: the Violation-1 construction is closed — the grep that enforces the invariant now reads `execute-tracks/SKILL.md` and the other three out-of-scope files, so a surviving `review-bugs-concurrency` there is caught.
- **Regression check**: A1 (scope side) and A3 (verification side) are now consistent — the in-scope list, Plan of Work step (4) repo-wide sweep, the acceptance bullet, and the invariant all say "repo-wide over `.claude/**`". The staged-vs-live read note ("staged copies for in-scope files, the live tree otherwise") matches the §1.7(d) staged-read precedence rule. No half-fix (hazard widened without check, or vice versa). Clean.
- **Verdict**: VERIFIED

#### Verify A4: D9 introduces a third (track-level) granularity for the same seven triggers
- **Original issue**: the seven HIGH triggers are now read at three granularities (change-level Gate 1, track-level D9, step-level risk tag) and the coherence load was undocumented; a reader could mistake the track-level read for the change-level Gate 1 run.
- **Fix applied**: Plan of Work step (1) distinguishes the three granularities and their "central to X" scoping, folded into the risk-tagging implementation instruction rather than a Decision-Record edit (DRs stay immutable).
- **Re-check**:
  - Track-file location: `## Plan of Work` step (1) lines 324-328.
  - Current state: "Distinguish the three granularities that read the same seven triggers, so the track-level read is not mistaken for the others: **change-level** (Gate 1 reuse, 'central to the whole change', the design-gate source), **track-level** (this D9 rule, 'central to this track's planned work'), and **step-level** (the per-step `risk:` tag, 'this step introduces it')." The three "central to X" scopings are each named.
  - Criteria met: the coherence distinction the suggestion asked for is present; the implementer will author the distinction into `risk-tagging.md`.
- **Regression check**: confirmed the D9 Decision-Record body (track-2.md:210-235) is unchanged — the fix went into the implementation instruction, honoring DR immutability. The two-taxonomies distinction (7 triggers vs 13 categories) in D9's Risks/Caveats is untouched and does not conflict with the new three-granularities clause (they answer orthogonal questions). Clean.
- **Verdict**: VERIFIED

#### Verify A5: reconciled-tag must co-emit with `--track` on one ledger line
- **Original issue**: Plan of Work step (2) said "Write the reconciled tag … via `--append-ledger`" without pinning co-emission with `--track <N>`; a separate `--append-ledger --reconciled-tag` line lacking a `track=` token would write a tag the track-scoped reader (`ledger_tail_value_for_track`) cannot find.
- **Fix applied**: step (2) pins co-emission onto the existing A→C append that already carries `--track <N>`.
- **Re-check**:
  - Track-file location: `## Plan of Work` step (2) lines 344-350.
  - Current state: "append `--reconciled-tag <max(step tags)>` onto the **existing** A→C boundary append (the `track-review.md` call already carrying `--track <N> --substate steps-partial`), not a separate line — the track-scoped reader resolves the tag only on a ledger line that also carries its `track=` token". The constraint is explicit, and step (2) adds the idempotence-on-resume recompute.
  - Live ground truth: the A→C boundary append at `track-review.md:599-601` emits `--append-ledger --ctx <level> --phase C --track <N> --substate steps-partial` on one line — exactly the host the fix names, so folding `--reconciled-tag` into it is feasible.
  - Criteria met: the Violation-2 (THEORETICAL) construction is closed — a future editor is now told to keep the reconciled tag on the `--track`-carrying line.
- **Regression check**: checked the D5 Decision-Record (track-2.md:90-119) and the "Reconciled-tag write" key-contract (577-579) — both unchanged and consistent with the pinned co-emission. The track-scoped-read invariant (inherited from Track 1) is now matched by a track-scoped write instruction. Clean.
- **Verdict**: VERIFIED

## Findings

<!-- Pure-verdict pass: no new finding surfaced during re-check. -->

## Summary

PASS. All five iteration-1 findings VERIFIED. No regression introduced by the
scope widening; the Decision Records remain immutable and internally consistent,
and the §1.7(b) staging rule is honored for the four newly in-scope files. No new
finding.
