<!-- MANIFEST
findings: 7   severity: {blocker: 0, should-fix: 4, suggestion: 3}
index:
  - {id: A1, sev: should-fix, loc: "track-1.md:289-293 (Signatures and contracts — S3 gate spawn)", anchor: "### A1 ", cert: "Assumption: edit-design Step 4 can observe the log-adversarial gate state", basis: "S3 gate is cross-SKILL; edit-design Step 4 has no documented input for the create-plan gate's open/clear state"}
  - {id: A2, sev: should-fix, loc: "track-1.md:74-84,118-126 (Plan of Work step 3 + D19)", anchor: "### A2 ", cert: "Violation scenario: an unstamped research-log.md trips a walk other than the §1.6(h) Phase-1 stamp walk", basis: "D19 reasons only over §1.6(h); the §1.6(f)/(g) drift and migrate walks share the same hardcoded glob set, so the claim holds, but the track cites only the Phase-1 walk"}
  - {id: A3, sev: should-fix, loc: "track-1.md:126-134 (Plan of Work step 4 — §2.5 access wiring) vs design.md:447-457", anchor: "### A3 ", cert: "Assumption: §2.5 needs planner/1 added on both axes", basis: "reviewer-adversarial is ALREADY in the §2.5 roles list; only phases needs `1`. The planner/1 framing over-states the edit and risks a spurious roles add"}
  - {id: A4, sev: should-fix, loc: "track-1.md:141-143 (Step 1c tier-aware resume branch) + implementation-plan.md S1", anchor: "### A4 ", cert: "Violation scenario: Step 1c cannot read a tier on resume because D18's tier line lives in a plan that does not yet exist", basis: "lite/minimal interrupted before the plan is written has no on-disk tier source; the branch must route on file-absence shape, not the tier"}
  - {id: A5, sev: suggestion, loc: "track-1.md:275-282 (Sizing justification)", anchor: "### A5 ", cert: "Challenge: Track 1 at 13 in-scope files sits one above the ≤~12 merge floor", basis: "the merge floor is ≤~12; 13 clears it by one. The conventions-execution straddle is the load-bearing defense, the file count alone is not"}
  - {id: A6, sev: suggestion, loc: "track-1.md:97-100,177-182 (Deliverables / step 12 live script edit)", anchor: "### A6 ", cert: "Assumption: the live design-mechanical-checks.py edit is safe to land mid-branch under I6", basis: "the new `decision cited without rationale` check is additive but could fire on the branch's own frozen design.md if that doc cites a decision without rationale; acceptance only tests the footer-spelling half"}
  - {id: A7, sev: suggestion, loc: "track-1.md:107-116 (Plan of Work step 1 — vocabulary-first ordering)", anchor: "### A7 ", cert: "Challenge: vocabulary-first ordering does not protect against the gate reading staged vocabulary that does not yet exist", basis: "the adversarial gate (step 5) runs against the live conventions glossary during this branch's own execution; the staged glossary is inert until Phase 4 promotion"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 7}
cert_index:
  - {id: CH-D7-S3, verdict: WEAK, anchor: "#### CH-D7-S3 "}
  - {id: VS-D19, verdict: INFEASIBLE, anchor: "#### VS-D19 "}
  - {id: AT-D17-AXES, verdict: FRAGILE, anchor: "#### AT-D17-AXES "}
  - {id: VS-STEP1C, verdict: CONSTRUCTIBLE, anchor: "#### VS-STEP1C "}
  - {id: CH-SIZING, verdict: WEAK, anchor: "#### CH-SIZING "}
  - {id: AT-MECH-LIVE, verdict: HOLDS, anchor: "#### AT-MECH-LIVE "}
  - {id: CH-VOCAB-ORDER, verdict: HOLDS, anchor: "#### CH-VOCAB-ORDER "}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [should-fix]
**Certificate**: Assumption test — `edit-design` Step 4 cold-read can observe the log-adversarial gate state (CH-D7-S3)
**Target**: Invariant S3 (freeze order preserved across the SKILL boundary), Decision D6 (`edit-design` drops Step 3.5)
**Challenge**: D6 removes the adversarial pass from `edit-design` (live `edit-design/SKILL.md:398-444`, Step 3.5) and relocates it onto the research log inside `create-plan` Step 4. S3 then requires that the design's cold-read — which still lives in `edit-design` Step 4 — not run "while a log-adversarial entry is open." But `edit-design` is a separate SKILL spawned by `create-plan`; today its Step 4 cold-read gate keys only off mechanical-check blockers (`edit-design/SKILL.md:454-463`), and it has no documented input channel for the create-plan-side gate's open/clear status. The track's Plan of Work step 8 says the cold-read is "gated behind the log-adversarial gate clearing," but the track never names how the gate state crosses the SKILL boundary — whether `create-plan` only spawns `edit-design` after the gate clears (gate lives in the caller), or whether `edit-design` Step 4 grows a new precondition input. The plan-at-start track file leaves this to implementation, but a missed wiring here silently defeats S3: a Step-4a design with an open log-decision could reach cold-read.
**Evidence**: `edit-design/SKILL.md:398-411` (Step 3.5 today runs adversarial *inside* the loop, before Step 4); `create-plan/SKILL.md:222-227` (Step 4a invokes `edit-design`); design.md:510-519 (S3 "cold-read gated behind the log-adversarial gate clearing") asserts the gate but, like the track, locates it in the abstract.
**Proposed fix**: In Plan of Work step 8 (or the §Interfaces "Signatures and contracts" block), state which side owns the S3 precondition: either "`create-plan` Step 4a spawns `edit-design` only after the log gate clears, so `edit-design`'s own Step 4 needs no new input," or "`edit-design` Step 4 gains a gate-state input." The acceptance criterion ("no documented path reaches cold-read while a log entry is open") already exists; name the mechanism that the dry-run read will check.

### A2 [should-fix]
**Certificate**: Violation scenario — an unstamped `research-log.md` trips a stamp walk other than the §1.6(h) Phase-1 walk (VS-D19)
**Target**: Decision D19 (`research-log.md` unstamped, joins §1.6(f) exclusion), Invariant S1
**Challenge**: D19's rationale (implementation-plan.md:385-400, track-1.md:118-126) argues the log is safe to leave unstamped because "no §1.6(h) walk enumerates it." But the stamp machinery is read by three walks, not one: the §1.6(f)/(g) drift check, the migrate-range walk, and the no-drift normalization recompute all run the same enumerate-and-classify block (`conventions.md:704-707`). I attempted to construct a scenario where the *drift* walk (not the Phase-1 stamp walk) flags the unstamped log. It is INFEASIBLE — the drift walk uses the identical hardcoded glob set (`conventions.md:723-726`: `implementation-plan.md`, `design.md`, `design-mechanics.md`, `plan/track-*.md`), so `research-log.md` is invisible to every walk, not just the Phase-1 one. The decision survives. The weakness is in the track's *justification*, not the decision: track-1.md step 1 cites "the byte-source the script implements, pinned by a conformance fixture" and step 3/D19 reason over "the §1.6(h) walk," singular, while the load-bearing fact is that all three walks share one glob set. A future reader who adds the log to one walk's glob (e.g., a Phase-4 sweep that wants to stamp surviving artifacts) would break the invisibility the decision depends on without the track flagging that the protection is glob-shared, not walk-specific.
**Evidence**: `conventions.md:704-707` ("the drift detection, the migrate-range walk, and the no-drift normalization recompute all run it"); `conventions.md:723-726` (the single shared glob set); `conventions.md:677-679` (`design-mutations.md` exclusion, D19's cited precedent — same replay-immune rationale, also relies on the shared glob omission).
**Proposed fix**: Reword D19's risk/caveat (and track-1.md step 1's §1.6(f) note) to state the protection explicitly: "no walk enumerates the log because all three stamp walks (drift, migrate, normalize) share the §1.6(h) glob set, which omits `research-log.md`." This makes the invariant's fragility surface — adding the log to any one walk's glob breaks it — rather than implying only the Phase-1 walk matters.

### A3 [should-fix]
**Certificate**: Assumption test — §2.5 access wiring needs `planner`/`1` on both annotation axes (AT-D17-AXES)
**Target**: Decision D17 (gate output as §2.5 review files), Plan of Work step 4
**Challenge**: D17's wiring note (design.md:447-457) and track-1.md step 4 both say "§2.5 needs Phase-0→1 access on both annotation axes — its TOC row, plus the section markers ... extend their phases with `1` and their roles with `planner`." But the live §2.5 TOC row already carries `reviewer-adversarial` in its roles list (`conventions-execution.md:13`, and the same on the subsection markers at lines 474, 483, 546). The actual reviewer that the gate spawns is `reviewer-adversarial` (this very review is one), not `planner` — the orchestrator/planner only *reads* the manifest, and `orchestrator` is also already in the roles list. So the roles axis needs no edit for the reviewer to read its own schema; only the **phases** axis is missing `1`. Adding `planner` to the roles is either redundant (planner already reads via orchestrator-class routing) or wrong-target (the gate's writer is the adversarial reviewer). An implementer following the track literally would add a `planner` role the schema does not need, widening the annotation surface for no consumer.
**Evidence**: `conventions-execution.md:13` (TOC row roles already include `reviewer-adversarial` AND `orchestrator`; phases are `2,3A,3B,3C,4` — `1` absent); lines 474/483/546 (subsection markers, same roles, same missing `1`); the adversarial prompt's own Output Format routes the reviewer to write the §2.5 file (`adversarial-review.md:324-340`), confirming `reviewer-adversarial` is the writer-role that must be present.
**Proposed fix**: Narrow the track-4 step (and, deferred to Phase 4, D17's wiring note) to: "§2.5's TOC row and used-subsection markers extend their **phases** with `1`; `reviewer-adversarial` and `orchestrator` are already in the roles, so the roles axis needs an addition only if a planner-role read is actually required." Resolve at implementation whether `planner` is a real consumer before adding it.

### A4 [should-fix]
**Certificate**: Violation scenario — Step 1c cannot read the tier on resume because D18's tier line lives in a plan that does not yet exist (VS-STEP1C)
**Target**: Decision D1 (Step 1c tier-aware branch), D18 (tier persists in `implementation-plan.md`), Invariant S1
**Challenge**: track-1.md step 5 describes the Step 1c branch as distinguishing "`lite`/`minimal` in progress, no `design.md` by design" from "fresh start." Construct the resume: a `lite` change finishes Phase 0, the gate clears, the user confirms `lite`, and the session `/clear`s mid-Step-4b *before* `implementation-plan.md` is written. On resume, Step 1c runs `ls design.md implementation-plan.md` (live `create-plan/SKILL.md:132-134`). Both are absent. Today that is the "neither file exists → fresh start → Step 4a design authoring" branch (`create-plan/SKILL.md:169-170`). The new branch must NOT route a `lite`/`minimal` resume to Step 4a (there is no design by design). But the tier line (D18) lives in `implementation-plan.md`, which is exactly the file that is absent in this window — so Step 1c cannot read the tier to know it is `lite`/`minimal`. The branch must therefore route on something other than the tier (e.g., the research log's presence-plus-confirmed-tier marker, or a handoff). The track says the branch "distinguishes" the two states but does not name the on-disk signal it reads, and the obvious signal (the tier line) is unavailable in the precise window the branch exists to handle.
**Evidence**: `create-plan/SKILL.md:132-134` (Step 1c reads only `design.md`/`implementation-plan.md` presence); `create-plan/SKILL.md:169-175` (current neither/both branches); implementation-plan.md:370-383 (D18 puts the tier in the plan, "the one artifact every tier loads at startup" — but startup for `/execute-tracks`, not for a mid-Step-4b `/create-plan` resume where the plan is not yet authored); design.md:626-632 (Step 1c "distinguishes ... no design by design from fresh start" — same gap, no named signal).
**Proposed fix**: Name the on-disk signal Step 1c reads when both `design.md` and `implementation-plan.md` are absent but planning is in progress. Candidates: the research log's `## Initial request` plus a confirmed-tier note (the log is present in every tier from Phase 0), or a mid-phase handoff carrying the tier. Add it to step 5's branch description and to the track acceptance, since this is "S1's lone routing change" and a wrong route here sends a no-design tier into Step 4a design authoring.

### A5 [suggestion]
**Certificate**: Challenge — Track 1 at 13 in-scope files sits one above the ≤~12 merge floor (CH-SIZING)
**Target**: Scope (track sizing), §Interfaces sizing justification
**Challenge**: The sizing justification (track-1.md:275-282) reads "Thirteen in-scope files — above the merge floor, under the ceiling." The live rule (`planning.md:484-488`): "a track of ≤~12 in-scope files that folds into an adjacent track under the ceiling is a merge candidate." Thirteen clears the ≤~12 floor by exactly one file, and the floor is soft (`~12`). So the file count alone does not robustly establish "above the merge floor" — round the wrong way and it is a merge candidate. The real defense is the second sentence: the `conventions-execution.md` straddle (§2.5 here, §2.1 in Track 2) means folding would split a subject to co-locate a file. That argument is sound and survives. But the justification leads with the count, which is the weakest part; the merge-candidate test is not just "count > floor," it is "count ≤ floor AND folds cleanly into a neighbor." The decision survives because the subject-coherence argument blocks the fold, not because 13 > 12.
**Evidence**: `planning.md:484-488` (≤~12 merge-candidate floor, soft); `planning.md:490-502` (out-of-bounds passes with written justification; the thresholds are "soft review-capacity estimates"); track-1.md:275-282 (the justification, whose second half is load-bearing).
**Proposed fix**: Lead the sizing justification with the subject-coherence argument (folding splits a subject to co-locate a file) and treat the file count as secondary, since 13-vs-~12 is within the soft band and does not carry the argument on its own.

### A6 [suggestion]
**Certificate**: Assumption test — the live `design-mechanical-checks.py` edit is safe to land mid-branch under I6 (AT-MECH-LIVE)
**Target**: Decision D11 (live-path mechanical-check edit), Constraint (backward-compatibility), Invariant I6
**Challenge**: Plan of Work step 12 adds two things to the live script: (a) accept both footer spellings, and (b) a new "decision cited without rationale" check (track-1.md:177-182). The backward-compatibility argument and the acceptance criterion (track-1.md:227, design-mechanical-checks.py:666-676 confirmed `section_has_references` matches `### References`/`**References.**` today, and neither `Decisions & invariants` nor the new check exists yet) both cover only half (a): "passes against this branch's frozen `design.md` (old footer) and against a synthetic doc using the new footer." The new check (b) is a *behavioral addition* that could fire on the branch's own frozen `design.md` if any of its sections cite a D-code without rationale — the script runs live, on this branch, before Phase 4. design.md's `### References` footers list D-codes as bare citations by design (e.g., design.md:295-302), which is precisely the "decision cited without rationale or cross-reference" shape the new check targets unless the References footer is exempted. The track does not state that the new check exempts the References footer, and the acceptance does not test the new check against the live frozen design at all.
**Evidence**: `design-mechanical-checks.py:666-676` (today's `section_has_references`, no `Decisions & invariants`, no rationale check); design.md:294-302 (a live `### References` footer that lists D-records as bare citations — the shape the new check could flag); track-1.md:227 (acceptance tests only the footer-spelling half); design-mechanical-checks.py:870-955 (`check_dsc_parenthetical_asides` already special-cases the References block via `in_references` — precedent that any decision-citation check must likewise exempt References).
**Proposed fix**: Extend step 12's acceptance to test the *new* check against the branch's live frozen `design.md`, and state that the decision-cited-without-rationale check exempts the `### References` / `**References.**` footer (mirroring the existing `in_references` carve-out in `check_dsc_parenthetical_asides`). Otherwise the live edit risks turning the branch's own design red mid-flight, an I6-adjacent self-destabilization the live-path exception was meant to avoid.

### A7 [suggestion]
**Certificate**: Challenge — vocabulary-first ordering does not protect the gate, which reads the *live* glossary during this branch's own execution (CH-VOCAB-ORDER)
**Target**: Scope / ordering (Plan of Work ordering constraints), Constraint (§1.7 staging / I6)
**Challenge**: The Plan of Work orders "vocabulary lands first, then ... the SKILL wiring that cites both" (track-1.md:104-105, 184). This ordering protects the *staged* artifacts: a staged SKILL that cites a staged glossary term resolves in the staged mirror. But the gate this track builds (D6, step 5) does not run against the staged mirror during this branch — under D13/I6 the live workflow stays at develop state until Phase 4, so when *this* branch executes its own Phase 0→1 gate (it is `full`-tier, design.md:34/291), the gate prompt and the glossary it primes from are the *live* develop-state files, which have none of the new tier vocabulary. The ordering constraint is correct for what the track produces but creates a false sense that "vocabulary-first" makes the new machinery usable on this branch; it does not become live until promotion. This is consistent with I6 and not a defect, but the track's ordering rationale ("precedes everything that cites it") reads as if it governs runtime resolution when it only governs staged-artifact internal consistency. A reader could mistakenly expect the new gate to be exercisable mid-branch.
**Evidence**: track-1.md:184-190 (ordering constraints + I6 restated); design.md:1049-1057 (D13: live `.claude/**` stays at develop until promotion); the staged mirror does not exist yet this session, so every staged-read falls back to live (per the spawn's staged-read precedence note) — confirming the new vocabulary is not resolvable at runtime on this branch.
**Proposed fix**: Add one sentence to the ordering constraints clarifying scope: the vocabulary-first order guarantees internal consistency of the *staged* artifacts at authoring time; the new machinery is exercised only after the Phase-4 promotion makes it live (I6), so this branch's own gate runs under the develop-state rules, not the rules it stages. No mechanism change — a rationale-precision fix.

## Evidence base

#### CH-D7-S3 — Challenge: D6 removes Step 3.5 from edit-design; S3 must survive the SKILL boundary
- **Chosen approach**: D6 relocates the adversarial pass from `edit-design` Step 3.5 onto the research log inside `create-plan` Step 4; S3 keeps the design cold-read gated behind the log-adversarial gate clearing.
- **Best rejected alternative**: keep the adversarial pass inside `edit-design` (D6's rejected "keep it on design.md" / "run on both"). Rejected for leaving lite/minimal uncovered — sound.
- **Counterargument trace**:
  1. Today `edit-design` Step 3.5 runs adversarial *inside* the same loop as Step 4 cold-read (`edit-design/SKILL.md:398-411`), so the ordering is enforced by one SKILL's control flow.
  2. After D6, the adversarial gate lives in `create-plan` Step 4 and the cold-read stays in `edit-design` Step 4; the two SKILLs are separate spawns (`create-plan/SKILL.md:222-227`).
  3. `edit-design` Step 4's only documented gate today is mechanical-blocker-clear (`edit-design/SKILL.md:454-463`); nothing carries the create-plan-side gate state in. If the wiring is left implicit, a design with an open log decision can reach cold-read — S3 silently broken.
- **Codebase evidence**: `edit-design/SKILL.md:398-411` and `:454-463`; `create-plan/SKILL.md:222-227`; design.md:510-519.
- **Survival test**: WEAK — S3 is stated as an acceptance criterion but the cross-SKILL mechanism is unnamed; the dry-run read needs a concrete owner to check against.

#### VS-D19 — Violation scenario: unstamped research-log.md trips a non-Phase-1 stamp walk
- **Invariant claim**: D19 — `research-log.md` may be left unstamped because no walk enumerates it.
- **Violation construction**:
  1. Start state: branch mid-execution with `research-log.md` present and unstamped.
  2. Action sequence: a session-start drift check runs the §1.6(f)/(g) walk (`conventions.md:704-707`), not the Phase-1 §1.6(h) stamp walk.
  3. Intermediate state: the walk enumerates `_workflow/` stamped artifacts via its glob set.
  4. Violation point: would the drift walk's glob include `research-log.md`? `conventions.md:723-726` — the glob set is `implementation-plan.md`, `design.md`, `design-mechanics.md`, `plan/track-*.md`. `research-log.md` is not matched.
  5. Observable consequence: none — the log is invisible to the drift walk too.
- **Feasibility**: INFEASIBLE — all three walks share one hardcoded glob set that omits the log. The decision is correct; only the track's *justification* under-specifies why (it cites the Phase-1 walk, the protection is glob-shared across all three walks).

#### AT-D17-AXES — Assumption test: §2.5 needs planner/1 on both axes
- **Claim**: D17/track step 4 — §2.5's TOC row and used-subsection markers must extend phases with `1` and roles with `planner`.
- **Stress scenario**: an implementer applies the edit literally and adds `planner` to the §2.5 roles list.
- **Code evidence**: `conventions-execution.md:13` — the TOC row roles already contain `reviewer-adversarial` and `orchestrator`; phases are `2,3A,3B,3C,4`, so `1` is genuinely absent. The gate's writer is `reviewer-adversarial` (`adversarial-review.md:324-340`), already present; the manifest reader is orchestrator-class, already present.
- **Verdict**: FRAGILE — the phases-axis `1` addition is real and necessary; the roles-axis `planner` addition is redundant or mis-targeted. The track over-states the edit.

#### VS-STEP1C — Violation scenario: Step 1c has no tier source in the no-plan resume window
- **Invariant claim**: D1/S1 — the Step 1c tier-aware branch routes a `lite`/`minimal` in-progress resume away from Step 4a design authoring.
- **Violation construction**:
  1. Start state: `lite` change, gate cleared, tier confirmed, `/clear` before `implementation-plan.md` is written.
  2. Action sequence: resume `/create-plan`; Step 1c runs `ls design.md implementation-plan.md` (`create-plan/SKILL.md:132-134`).
  3. Intermediate state: both files absent.
  4. Violation point: the current branch routes "neither exists" to "fresh start → Step 4a design authoring" (`create-plan/SKILL.md:169-170`). The new branch must override this for `lite`/`minimal`, but the tier line (D18) is in the not-yet-written plan, so the tier is unreadable here.
  5. Observable consequence: without a named alternative signal, the branch either cannot fire (falls through to Step 4a, sending a no-design tier into design authoring) or relies on an undocumented input.
- **Feasibility**: CONSTRUCTIBLE — the window is real (gate clears before Step 4b writes the plan); the track names no on-disk signal for it.

#### CH-SIZING — Challenge: 13 files vs the ≤~12 merge floor
- **Chosen approach**: Track 1 packs 13 in-scope files, justified as "above the merge floor, under the ceiling" plus the conventions-execution straddle.
- **Best rejected alternative**: fold §2.5 into Track 2 (co-locating both conventions-execution edits), shrinking Track 1 toward the floor.
- **Counterargument trace**:
  1. The merge-candidate floor is ≤~12 AND folds-into-a-neighbor (`planning.md:484-488`); 13 is one above a soft threshold.
  2. The fold alternative would put §2.5 (gate-output schema, Track 1's subject) into Track 2, splitting a subject across tracks to co-locate a file.
  3. Outcome: the subject-coherence cost of the fold exceeds the one-file overshoot, so the pack survives — but on the coherence argument, not the count.
- **Codebase evidence**: `planning.md:484-502`; track-1.md:275-282.
- **Survival test**: WEAK — decision survives, but the justification leads with the weakest evidence (the count); the load-bearing argument is subject coherence.

#### AT-MECH-LIVE — Assumption test: the new live-script check is safe on the branch's own frozen design
- **Claim**: the D11 live-path edit is backward-compatible because it accepts both footer spellings.
- **Stress scenario**: the new "decision cited without rationale" check (step 12b) runs live against this branch's frozen `design.md`, whose `### References` footers list D-codes as bare citations.
- **Code evidence**: `design-mechanical-checks.py:666-676` (no such check today); design.md:294-302 (a References footer listing D-records as bare citations); `design-mechanical-checks.py:886-933` (`in_references` exemption precedent in `check_dsc_parenthetical_asides`).
- **Verdict**: HOLDS *iff* the new check exempts the References footer; the track does not state the exemption and acceptance does not test the new check on the live design, so the safety is assumed, not demonstrated.

#### CH-VOCAB-ORDER — Challenge: vocabulary-first ordering vs live-during-branch execution
- **Chosen approach**: vocabulary lands first so the staged SKILL wiring resolves the staged glossary.
- **Best rejected alternative**: none material — the ordering is internally correct.
- **Counterargument trace**:
  1. The ordering guarantees staged-artifact internal consistency at authoring time.
  2. But this branch is `full`-tier and runs its own Phase 0→1 gate against the *live* develop-state workflow (D13/I6, design.md:1049-1057); the new vocabulary is not live until Phase 4 promotion.
  3. Outcome: no defect, but the rationale "precedes everything that cites it" can be misread as runtime-resolution protection when it is authoring-time consistency only.
- **Codebase evidence**: track-1.md:184-190; design.md:1049-1057; staged mirror absent this session (every staged-read falls back to live).
- **Survival test**: HOLDS — consistent with I6; a rationale-precision suggestion, not a mechanism change.
