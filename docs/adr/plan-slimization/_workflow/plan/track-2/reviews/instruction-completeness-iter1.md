<!--
MANIFEST
dimension: instruction-completeness
review_target: "Track 2: Execution-side tier consumption (f34bc56f06..HEAD)"
iteration: 1
findings_total: 3
evidence_base: 3
cert_index: [C1, C2, C3]
flags: []
index:
  - id: WI1
    sev: should-fix
    anchor: "#wi1-should-fix-missing-tier-line-check-not-delegated-to-the-consistency-sub-agent"
    loc: "prompts/consistency-review.md (staged) lines 55-70; implementation-review.md (staged) lines 189-191"
    cert: C1
    basis: judgment
  - id: WI2
    sev: should-fix
    anchor: "#wi2-should-fix-mid-flight-tier-upgrade-never-rewrites-the-d18-tier-line-the-re-entered-selectors-read"
    loc: "inline-replanning.md (staged) lines 141-148; track-2.md line 457"
    cert: C2
    basis: judgment
  - id: WI3
    sev: suggestion
    anchor: "#wi3-suggestion-consistency-prompt-defines-no-behavior-when-the-tier-line-is-absent"
    loc: "prompts/consistency-review.md (staged) lines 55-58"
    cert: C3
    basis: judgment
-->

## Findings

### WI1 [should-fix] Missing-tier-line check not delegated to the consistency sub-agent
- **File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/prompts/consistency-review.md` (lines 55-70), with the asserting claim at `staged .../implementation-review.md` (lines 189-191).
- **Axis:** sub-agent handshake (phase output → next-phase input).
- **Cost:** the one check that protects every downstream tier selector — Phase-2/3A/4 all key off the D18 tier line — is asserted to run but is never delegated to the agent that runs it, and is actively suppressed under `minimal`.
- **Issue:** the orchestrator-facing `implementation-review.md` states "the Phase-2 consistency review flags a plan that lacks [the tier line]" (line 190), and D18's Risks/Caveats in the plan says the consistency review "should flag a plan with no tier line." The consistency review is performed by the sub-agent driven by `prompts/consistency-review.md`. That prompt tells the reviewer to *read* the tier line to select the tier (line 56) but carries **no instruction to emit a finding when the tier line is missing**. Worse, the `minimal` branch tells the reviewer "do not raise findings against the stub plan's absent content" (line 70) — and the tier line is stub-plan content (D1/D18 put it in the `minimal` stub), so the suppression rule would silence exactly this check in the tier where the stub is most likely to omit it. The orchestrator delegates a gate to the sub-agent; the sub-agent prompt does not carry the gate, and the one tier that needs it carves it out.
- **Suggestion:** add a tier-line-presence check to the consistency prompt that runs in **every** tier (it is the precondition for the prompt's own tier selection, not stub-content drift): "If `implementation-plan.md` has no D18 tier line, emit a finding (the tier line is required in every tier per D18; the `minimal` stub template must include it) and treat the plan as malformed." Carve the tier line out of the `minimal` "absent stub content" suppression explicitly, so the suppression covers decision/ordering content but never the tier line.

### WI2 [should-fix] Mid-flight tier upgrade never rewrites the D18 tier line the re-entered selectors read
- **File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/inline-replanning.md` (lines 141-148), cross-checked against `track-2.md` line 457 ("The D18 tier line is read-only for every consumer in this track; only `create-plan` writes it").
- **Axis:** phase output → next-phase input (state marker transition).
- **Cost:** after an upgrade, every re-entered selector (Phase 2 consistency shape, Phase 3A panel, Phase 4 artifact set) reads the stale pre-upgrade tier and silently keeps running the old, lighter tier's passes — the upgrade is announced but never takes effect downstream.
- **Issue:** the D12 tier-upgrade note says an upgrade "adds the new tier's artifacts and runs that tier's Phase-3A passes from the upgrade point onward" (lines 141-148), and the matching `implementation-review.md` note says the plan "re-enters the design-present branches." All those re-entered branches select on the D18 tier line in `implementation-plan.md`. Track 2's own Interfaces note (line 457) states the tier line is **read-only for every consumer in this track — only `create-plan` writes it.** But a mid-flight upgrade is an inline replan during execution, where `create-plan` does not run. No staged instruction in the upgrade path (or anywhere in Track 2's scope) says **update the tier line to the new tier**, and Track 1's only writer (`create-plan`) is not on the upgrade path. The upgrade therefore has no defined writer for the new tier value: the conditional "re-enter the new-tier branches" has its trigger (the upgrade) but no mechanism to make the trigger observable to the selectors that gate on it. (Confirmed absent: grep for `tier line` / `D18` / `update tier` across `inline-replanning.md` and `track-review.md` finds only *read*-side references.)
- **Suggestion:** in the D12 tier-upgrade note (`inline-replanning.md`), add the tier-line rewrite as the first artifact the upgrade lands: "the upgrade rewrites the D18 tier line in `implementation-plan.md` to the new tier before the next State-0 re-run, so the Phase-2/3A/4 selectors read the new tier." If by S1/scope the tier line stays a `create-plan`-owned write, name the hand-off explicitly (e.g., the ESCALATE replan invokes the `create-plan` tier-confirmation step that owns the line) rather than leaving the writer undefined on the upgrade path. Either way the read-only-for-consumers statement at track-2.md:457 needs a carve-out for the upgrade writer, or it reads as "no one updates it on upgrade."

### WI3 [suggestion] Consistency prompt defines no behavior when the tier line is absent
- **File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/prompts/consistency-review.md` (lines 55-58).
- **Axis:** empty-input / degenerate case.
- **Cost:** a reviewer handed a plan with no tier line has no defined tier and no fallback, so the axis-selection (which of DESIGN/PLAN/TRACK checks run) is undefined — the review proceeds in an unspecified shape rather than failing cleanly.
- **Issue:** the prompt's tier-and-design-presence guard says "the set of artifacts you compare depends on the plan's confirmed tier (read the tier line in `implementation-plan.md`)" and then enumerates `full`/`lite`/`minimal` (lines 55-70). It defines no behavior for the borderline/degenerate case where the tier line cannot be read. This is the tie-breaker complement to WI1: WI1 says "emit a finding"; WI3 asks what shape the *current* review runs in while that finding stands. Without a rule, the reviewer guesses a tier and the design-presence guard becomes the only thing keeping the run coherent (it happens to be safe because it tests `design.md` existence directly, not the tier name — but that safety is incidental, not specified).
- **Suggestion:** add one tie-breaker clause: "If the tier line is absent, fall back to the design-presence test alone for axis selection (compare against whatever artifacts exist on disk) and raise the WI1 missing-tier-line finding." This makes the degenerate run deterministic and ties it to the WI1 gate.

## Evidence base

#### C1 — WI1: tier-line-flag check delegated to sub-agent? (refuted — gap confirmed)
Checked the complement of the asserted gate. `implementation-review.md:190` (orchestrator-facing) asserts "the Phase-2 consistency review flags a plan that lacks it"; D18 Risks/Caveats in the slim plan repeats "should flag a plan with no tier line." The performer is the sub-agent under `prompts/consistency-review.md`. Read that prompt's tier section (lines 51-78) and grepped `tier|flag|missing|lacks|absent` across the whole prompt — the only tier references are read-side (line 56 "read the tier line") and the design-presence axes (lines 219, 267, 311). No instruction emits a missing-tier-line finding. The `minimal` branch (line 70) instructs "do not raise findings against the stub plan's absent content," which suppresses the check in the at-risk tier. Gate asserted upstream, absent in the delegated prompt → confirmed handshake gap.

#### C2 — WI2: tier-line writer on the mid-flight upgrade path? (refuted — gap confirmed)
The upgrade note (`inline-replanning.md:141-148`) promises new-tier passes "from the upgrade point onward," and the re-entry path is the State-0 re-run (inline-replanning step 6 resets `## Plan Review` to `[ ]`, routing the next session through Phase 2 — verified at `inline-replanning.md:196-228`). Every re-entered selector reads the D18 tier line. `track-2.md:457` pins the line as "read-only for every consumer in this track; only `create-plan` writes it." `create-plan` is a Track-1 Phase-0/1 skill, not on the execution-time upgrade path. grep for `tier line|D18|update tier|rewrite tier` across `inline-replanning.md` and `track-review.md` returns only read-side hits. No writer for the new tier value exists on the upgrade path → the upgrade trigger has no mechanism to reach the gating selectors → confirmed.

#### C3 — WI3: degenerate-input rule for absent tier line? (refuted — gap confirmed)
Read `prompts/consistency-review.md:55-78`. The tier guard branches `full`/`lite`/`minimal` on the confirmed tier and applies the design-presence mechanical test, but enumerates no branch for "tier line unreadable/absent." The run remains coherent only incidentally, because the design-presence guard keys off `design.md` existence (a direct filesystem test) rather than the tier name — so the design-half skip still fires correctly with no tier. But the plan-content-cross-check narrowing (the `minimal`-only drop) keys off the tier name, and with no tier it is undefined whether that narrowing applies. No tie-breaker clause present → confirmed (minor: incidental safety, not specified).
