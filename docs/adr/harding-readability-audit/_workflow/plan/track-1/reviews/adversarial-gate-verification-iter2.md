<!-- workflow-sha: 1065c173addca97b35fda8af611eb1e656e3ada2 -->
# Adversarial gate verification — Track 1 (iteration 2)

<!-- MANIFEST -->
```yaml
manifest:
  role: reviewer-adversarial
  phase: 3A
  track: "Track 1: Harden readability-auditor slicing and convergence"
  iteration: 2
  kind: verdict-producer
  verdicts:
    - {id: A1, disposition: REJECTED, verdict: REJECTED, note: "scope-bundle rejection sound; no downstream issue from leaving Concern C in-track"}
    - {id: A2, disposition: "ACCEPTED+FIXED", verdict: VERIFIED, note: "Plan-of-Work step 4 + D6 Risks/Caveats now relocate only the per-spawn params-file home and explicitly pin :791 output_path / :832 draft_path on plan/"}
    - {id: A3, disposition: "ACCEPTED+FIXED", verdict: VERIFIED, note: "D6 body + §2.5 reword + Plan step 4 name the phase4-creation comprehension output_path as moving; no contradiction with the retained Phase-2/3-stays line"}
    - {id: A4, disposition: "no-action", verdict: VERIFIED, note: "invariants unchanged by the A2/A3 edits; all five still well-defended"}
    - {id: A5, disposition: "no-action", verdict: VERIFIED, note: "anchor-fold-hash recomputability untouched by the edits; assumption still holds"}
  overall: PASS
  findings: 0
  blockers: 0
  should_fix: 0
  suggestions: 0
  index: []
```

## Verification certificates

#### Verify A1 (REJECTED): SCOPE — split Concern C (relocation) into its own track?
- **Rejection reason**: Bundling all three concerns (A: deterministic partition + guard; B: settled-state; C: relocation) into one ~6-file diff is correct. Splitting C out re-opens `edit-design` Step 4/6 and `create-plan` Step 4b item 9 in a second PR (A and C both edit `edit-design` Step 4/6; A/B/C all touch item 9), and C's new `_workflow/reviews/` home is where A's `slice_count`/`total_lines` params files and B's settled-state scaffolding land — so C-before-A/B is the only safe order. Maximize-bundle sizing (6 files ≪ ~12-file merge floor) puts the change inside the merge band, and the track file states the rationale (`## Interfaces` "Inter-track dependencies: none").
- **Downstream check**: Leaving Concern C in-track introduces no downstream issue. The track file's `## Interfaces` table and `## Interfaces` dependency line both carry the maximize-bundle justification; the Plan-of-Work orders edits operative-homes-first so each cross-reference points at an existing statement, and D6 lands in step 4 between the operative homes (steps 1-3) and the cross-references (step 5) — a coherent single-diff order. No stranded-intermediate-state risk, no second review cycle. The six in-scope files and their section anchors are all present in the live tree (verified: `create-plan/SKILL.md` item 9 at :764-849; `edit-design/SKILL.md` Step 4 auditor block at :676-682, the `phase4-creation` `output_path` at :617-628).
- **Verdict**: REJECTED (no action needed)

#### Verify A2: D6 "relocate item 9 params" over-application risk
- **Original issue**: `create-plan` Step 4b item 9 carries three `plan/`-rooted paths with two meanings — the per-spawn params-file home (relocate target) plus the author `output_path` and absorption `draft_path` (address `track-N.md` artifacts, must NOT move). Plan-of-Work step 4 said "item 9 params" generically, inviting an implementer to move all three and break the author/absorption draft-output wiring.
- **Fix applied**: Plan-of-Work step 4 (track-1.md:217) now reads "**only the per-spawn params-file home** in `create-plan` Step 4b item 9. Leave item 9's author `output_path` and absorption-check `draft_path` on their `plan/`-directory value — they address the `track-N.md` artifacts, not review files (D6 Risks/Caveats)." D6 Risks/Caveats (track-1.md:112) adds the matching exclusion paragraph: "Two `plan/`-rooted references in `create-plan` Step 4b item 9 are **not** review scaffolding and stay put: the author `output_path` and the absorption-check `draft_path` ... Only item 9's per-spawn params-file home relocates."
- **Re-check**:
  - Track-file location: Plan-of-Work step 4 (:217) and D6 Risks/Caveats (:112); the `## Interfaces` `create-plan` row (:262) also reads "relocate **only** the per-spawn params-file home → `_workflow/reviews/`, leaving the author `output_path` and absorption `draft_path` on `plan/`."
  - Current state: All three track-file sites now name the per-spawn params-file home as the sole relocate target and explicitly retain the two artifact-addressing paths. The three live targets are confirmed: params-file home at `create-plan/SKILL.md:782` ("per-spawn parameters in a params file under `_workflow/plan/`"), author `output_path` at :791 ("the `plan/` directory the track files land under"), absorption `draft_path` at :832-834 ("the `plan/` *directory* ... reads every `plan/track-N.md`"). The track file's three-vs-one mapping matches the live file exactly.
  - Criteria met: The realization instruction no longer under-specifies which of item 9's `plan/` references move; an implementer reading step 4 or D6 has an unambiguous single relocate target and an explicit do-not-move list.
- **Regression check**: Checked the `## Interfaces` table row, the Plan-of-Work step-4 sentence, and the D6 Risks/Caveats paragraph for mutual consistency — all three agree (only the params-file home moves). No new contradiction with D5 (which preserves item 9's per-file partition verbatim) or with the Validation section's "`plan/` holds only `track-N.md`" claim, since the two retained paths address exactly those `track-N.md` artifacts. Clean.
- **Verdict**: VERIFIED

#### Verify A3: Phase-4 comprehension `output_path` scope boundary
- **Original issue**: The only design-path comprehension `output_path` is `phase4-creation`-only (Phase 4), but D6 scoped itself to "only the Phase-1 authoring loop" and exempted "Phase 2/3," leaving `:624`'s home undecided — Phase 4 fell in neither bucket.
- **Fix applied**: D6 body (track-1.md:106) now states "'Authoring loop' spans both creation kinds — design/track authoring at Phase 1 (`phase1-creation`) and design-final authoring at Phase 4 (`phase4-creation`) — because the spawn set names a phase-4-only spawn (the fidelity check) alongside the absorption check; the one design-path comprehension `output_path` literal in `edit-design` Step 4 is the `phase4-creation` design-final cold-read output, and it follows the move with the rest." §2.5's generalized home is reworded to "plan-scoped authoring-loop review scaffolding — the Phase-0→1 research-log gate it names today, plus Phase-1 design/track authoring and Phase-4 design-final authoring." Plan-of-Work step 4 (:217) names the literal explicitly: "the one `edit-design` Step 4 comprehension `output_path` literal (the `phase4-creation` design-final cold-read output, which moves with the rest per D6)."
- **Re-check**:
  - Track-file location: D6 body (:106), D6 Risks/Caveats (:110), §2.5 generalization restated in D6 and in the `## Interfaces` `conventions-execution.md` row (:261), Plan-of-Work step 4 (:217).
  - Current state: The "authoring loop" boundary is now explicitly two-creation-kind (Phase-1 `phase1-creation` + Phase-4 `phase4-creation`), and the `phase4-creation` comprehension `output_path` is named as moving. Confirmed against the live source: `edit-design/SKILL.md:617` ("Inject `output_path` only for `phase4-creation`"), `:624` (the `_workflow/plan/`-rooted literal), `:627` ("For every other kind — including `phase1-creation` — omit the `output_path` line") — so the literal D6 now relocates is exactly the single `phase4-creation` output the original finding identified, and no other comprehension kind carries an `output_path` to strand.
  - Criteria met: The rule text now says unambiguously whether `:624` moves (it does), closing the split-across-two-directories risk; §2.5's home covers Phase-1 and Phase-4 authoring explicitly.
- **Regression check** (the spawn-prompt-flagged contradiction check): Verified the retained "Execution-phase review files (Phase 2/3, `track-review`) keep their existing track-anchored home" line (D6 Risks/Caveats, :110) against the new "phase4 output moves" claim (D6 body, :106). **No contradiction**: the split is authoring-loop vs execution-review, not a raw phase-number split. Phase 4 here means design-final *authoring* (the `phase4-creation` dual-clean loop, which moves), while Phase 2/3 means per-track *execution* reviews (`track-review`, which stay). The retained line itself spells this out — "the move touches the design/track authoring loop only (Phase-1 creation and Phase-4 design-final creation), not the per-track execution reviews" — so the two adjacent claims partition cleanly by review class, not by phase number. Also checked §2.5's live anchor (`conventions-execution.md:707-732`, still develop-state "Phase 0→1 gate" wording, correct: no staged tree exists yet, the generalization is a Phase-B edit). Clean.
- **Verdict**: VERIFIED

#### Verify A4: track invariants not weakened by the A2/A3 edits
- **Original issue**: none (A4 was a survival-certificate record; all five invariants INFEASIBLE-to-THEORETICAL to violate).
- **Re-check**: The A2/A3 edits touched D6 body/Risks-Caveats, Plan-of-Work step 4, the §2.5 generalization wording, and the `## Interfaces` table — all file-location (Concern C) text. None touched the partition rule (D1), the agent-side guard (D2), the orchestrator-side settled-state (D3), the deterministic-partition obligation, or the verifiable-count self-check. The five invariants in `## Invariants & Constraints` (:278-283) — I6 staging, S1 no-log/no-settled-state, S4 one-prose-owner, whole-doc floor, deterministic-partition, verifiable-count — are verbatim unchanged. The relocation moves *where* params/review files live, not *what* the auditor reads or how the partition is computed, so no invariant's enforcement surface shifted.
- **Verdict**: VERIFIED (invariants intact; no edit weakened a defense)

#### Verify A5: anchor-fold-hash recomputability assumption still holds
- **Original issue**: none (A5 recorded that the orchestrator already reads the standing anchors to build spawns, so folding them into the content hash adds no new read or agent-side state — assumption HOLDS).
- **Re-check**: The A2/A3 edits are file-relocation text and did not touch D4 (the anchor-folded content hash) or D5 (the both-paths convergence cross-reference). The live spawn-builds the assumption rests on are unchanged: `edit-design/SKILL.md:680-681` (the auditor reads the standing anchors `## Overview` + `## Core Concepts`) and `create-plan/SKILL.md:823-824` (track-path anchors = plan Component Map + each track's `## Purpose / Big Picture`). The orchestrator still reads those anchors to construct each spawn regardless of where the params file lives, so hashing them remains free. Relocating the params-file home does not change which anchors the orchestrator holds.
- **Verdict**: VERIFIED (assumption holds; recomputability untouched)

## Findings

(none — pure verdict-producer pass; the A2/A3 fixes verified clean and introduced no regression, so no new adversarial finding surfaced)
