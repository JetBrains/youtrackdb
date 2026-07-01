<!-- MANIFEST
findings: 0
severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: 9 certificates (9 Ref, 0 Flow, 0 Invariant); all MATCHES / verified. Single-track design_gate=no — only the Track ↔ Code axis and the orphan-codebase-construct GAPS bullet ran; no design.md and no implementation-plan.md exist, so the design axes, the design half of GAPS, and the plan-content bullets were skipped per the axis narrowing.
cert_index: [C1 track-code-review.md guard/loop, C2 track-code-review.md preamble, C3 track-code-review.md post-loop narration, C4 track-code-review.md context block, C5 step-implementation.md guard/loop, C6 step-implementation.md preamble, C7 step-implementation.md post-loop+context block, C8 §1.7(k) references step 8 / holds no copy, C9 Inv5 no third copy + last-commit sha]
flags: [CONTRACT_OK]
-->

# Consistency review (Phase 2) — Track 1, iteration 1

Role `reviewer-plan`, phase `2`. Branch `review-new-files`. Single-track change, `design_gate=no` (ledger: `phase=0 design_gate=no tracks=1 phase1_complete=yes s17=opt-out`). No `design.md` and no `implementation-plan.md` exist, so the DESIGN ↔ CODE and DESIGN ↔ PLAN axes, the design half of GAPS, and the PLAN ↔ CODE plan-content bullets were skipped. Only the Track ↔ Code check and the orphan-codebase-construct GAPS bullet ran.

The track edits Markdown/bash workflow files, not Java symbols, so PSI is not the applicable tool — Read/Grep is authoritative for these files, and mcp-steroid (though reachable this session) indexes Java PSI and adds nothing here. Each certificate records that the verification target is a non-Java file and Read/Grep is authoritative for it.

Every current-state claim in the track's `## Context and Orientation` and `## Decision Log` was verified against the live on-disk files. All verdicts MATCH. Clean pass.

## Findings

_None. All certificates returned MATCHES; the track's current-state claims match the live workflow files exactly._

## Evidence base

#### Ref: track-code-review.md — `if [ -f "$live" ]` guard, no else branch (loop)
- **Document claim**: `## Context and Orientation` item 1 and D1: the bash loop in `track-code-review.md` step 8 guards the diff with `if [ -f "$live" ]` and has **no else branch** (~283-289); the loop enumerates new-file adds via `--diff-filter=A` under the anchored staged prefix and derives the live counterpart by prefix-strip.
- **Search performed**: Read `.claude/workflow/track-code-review.md` lines 255-303 (non-Java Markdown/bash file; Read/Grep is authoritative). PSI not applicable — target is bash inside Markdown, not a Java symbol.
- **Code location**: `.claude/workflow/track-code-review.md:283-290` (guard + body), `:276-277` (`--diff-filter=A --name-only` under `docs/adr/*/_workflow/staged-workflow/.claude/*`), `:281-282` (`sed` prefix-strip).
- **Actual signature/role**: `if [ -f "$live" ]; then { printf '=== delta: %s vs %s ===\n' … ; diff … ; } >> /tmp/claude-code-track-{N}-delta-$PPID.txt; fi` — the block closes with `fi` at line 290 with no `else` clause. Enumeration is `git diff {base_commit}..HEAD --diff-filter=A --name-only -- 'docs/adr/*/_workflow/staged-workflow/.claude/*'`.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: track-code-review.md — preamble ends "…and when the live file exists write `diff <live> <staged>`"
- **Document claim**: `## Context and Orientation` item 1 (~271): the preamble prose ends "…and when the live file exists write `diff <live> <staged>`".
- **Search performed**: Read `.claude/workflow/track-code-review.md` lines 258-272 (non-Java Markdown; Read/Grep authoritative).
- **Code location**: `.claude/workflow/track-code-review.md:270-272`.
- **Actual signature/role**: "…derive each live counterpart by stripping that prefix, and when the live file exists write `diff <live> <staged>` to a per-track delta temp file:". No no-live-counterpart / NEW-marker case is stated — matching the pre-fix current state.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: track-code-review.md — post-loop narration "fires only on a new-file add … that has a live counterpart"
- **Document claim**: `## Context and Orientation` item 1 (~293): the post-loop narration restricts the trigger to a new-file add "that has a live counterpart".
- **Search performed**: Read `.claude/workflow/track-code-review.md` lines 293-303 (non-Java Markdown; Read/Grep authoritative).
- **Code location**: `.claude/workflow/track-code-review.md:293-295`.
- **Actual signature/role**: "The trigger is precise: it fires only on a new-file add (`--diff-filter=A`) under the anchored staged prefix that has a live counterpart." — restricts to the delta case only, as the track claims.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: track-code-review.md — context block blanket "when non-empty, scope to the delta … the rest is out of scope"
- **Document claim**: `## Context and Orientation` item 1 (~454-465): the "Review-target delta for freshly-created staged copies" context block carries the blanket instruction that when the delta file is non-empty, scope findings to the delta and "the rest of each whole-file add is verbatim-copied, already-live, already-reviewed content and is out of scope."
- **Search performed**: Read `.claude/workflow/track-code-review.md` lines 445-472 (non-Java Markdown; Read/Grep authoritative).
- **Code location**: `.claude/workflow/track-code-review.md:454-465`.
- **Actual signature/role**: Heading `## Review-target delta for freshly-created staged copies` (454); body: "When that file is non-empty, scope your findings to the delta: the rest of each whole-file add is verbatim-copied, already-live, already-reviewed content and is out of scope. When it is empty (no freshly-created staged copy in range, or an ordinary plan), review the diff as usual." — single blanket case, no per-marker distinction, exactly as the track's pre-fix claim.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: step-implementation.md — `if [ -f "$live" ]` guard, no else branch (loop)
- **Document claim**: `## Context and Orientation` item 2 and D1: `step-implementation.md`'s Phase B step-level review carries the same loop else-gap (~498-504); byte-identical to the track-code-review.md loop except the temp-file path (`step-{N}-{M}-delta`) and two extra levels of indentation.
- **Search performed**: Read `.claude/workflow/step-implementation.md` lines 475-517 (non-Java Markdown/bash; Read/Grep authoritative).
- **Code location**: `.claude/workflow/step-implementation.md:498-505` (guard + body), `:491-492` (enumeration), `:496-497` (prefix-strip).
- **Actual signature/role**: `if [ -f "$live" ]; then { … } >> /tmp/claude-code-step-{N}-{M}-delta-$PPID.txt; fi` closing with `fi` at 505, no `else`. Temp-file path is `claude-code-step-{N}-{M}-delta` and the block sits two levels deeper than the track-code-review.md copy, confirming the only divergences the track names.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: step-implementation.md — preamble ends "…and when the live file exists write `diff <live> <staged>`"
- **Document claim**: `## Context and Orientation` item 2 (~486): the same preamble as track-code-review.md, ending on the "when the live file exists" case.
- **Search performed**: Read `.claude/workflow/step-implementation.md` lines 475-488 (non-Java Markdown; Read/Grep authoritative).
- **Code location**: `.claude/workflow/step-implementation.md:484-487`.
- **Actual signature/role**: "…derive each live counterpart by stripping that prefix, and when the live file exists write `diff <live> <staged>` to a per-step delta temp file:". No NEW-marker case — matching the pre-fix state.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: step-implementation.md — post-loop narration + context block (blanket out-of-scope)
- **Document claim**: `## Context and Orientation` item 2 (~508, ~610-621): the same post-loop narration restricting the trigger to a live-counterpart add, and the byte-identical context block with the blanket "when non-empty, scope to the delta … the rest is out of scope" instruction, differing only in temp-file path and indentation.
- **Search performed**: Read `.claude/workflow/step-implementation.md` lines 508-517 and 600-631 (non-Java Markdown; Read/Grep authoritative).
- **Code location**: `.claude/workflow/step-implementation.md:508-510` (post-loop narration), `:610-621` (context block).
- **Actual signature/role**: Narration: "The trigger is precise: it fires only on a new-file add (`--diff-filter=A`) under the anchored staged prefix that has a live counterpart." Context block heading `## Review-target delta for freshly-created staged copies` (610); body carries the same blanket "When that file is non-empty, scope your findings to the delta: … out of scope. When it is empty …, review the diff as usual." (617-621). Divergence from the track-code-review.md copy is only the `claude-code-step-{N}-{M}-delta` path and indentation.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: conventions.md §1.7(k) — references step 8, holds no copy of the loop or context block
- **Document claim**: D1 ("Scope confirmed closed"), D3, and `## Interfaces and Dependencies` (Out of scope): `conventions.md §1.7(k)` only *references* "the Phase C Startup staged-delta prep in track-code-review.md step 8" and holds **no** copy of the loop or context block.
- **Search performed**: Grep `.claude/` for `1.7(k)` and `step 8` / `staged-delta prep`; Read `.claude/workflow/conventions.md` lines 1336-1395 (§1.7(k) subsection). Non-Java Markdown; Read/Grep authoritative.
- **Code location**: `.claude/workflow/conventions.md:1346-1348` (the pointer); §1.7(k) subsection spans 1336-~1445.
- **Actual signature/role**: "The opt-out disables only the staging mechanism (write-routing, the pre-commit live-path gate, the Phase C Startup staged-delta prep in track-code-review.md:orchestrator,reviewer-dim-track:3C step 8, the Phase 4 promotion guard)…". The subsection names step 8 as a pointer with the inline `track-code-review.md` ref suffix and contains no `claude-code-track-…-delta` / `claude-code-step-…-delta` temp-path, no `diff <live> <staged>` loop, and no "Review-target delta" context block.
- **Verdict**: MATCHES
- **Detail**: Note — the section anchor lives in `conventions.md` proper; `§1.7(k)` as a literal token appears elsewhere only in three review prompts (risk/adversarial/technical-review.md). This does not contradict the track: those are additional consumers of the opt-out mode, none of which carries the loop or context block either.

#### Invariant: Inv 5 — no third copy of the loop/context block; last-commit sha
- **Document claim**: Inv 5 and `## Context and Orientation`: no file other than the two named carries a copy of the loop or context block (`§1.7(k)` holds only a pointer), verified by grepping `.claude/**` for the delta temp-path and the "freshly-created staged" prose; and the last workflow commit touching `track-code-review.md` is `03eac656fa`.
- **Code evidence**: `grep -rln 'claude-code-track-.*-delta' .claude/` → only `track-code-review.md`; `grep -rln 'claude-code-step-.*-delta' .claude/` → only `step-implementation.md`; `grep -rln 'freshly-created staged' .claude/` and `grep -rln 'Review-target delta for freshly-created staged copies' .claude/` → exactly `track-code-review.md` and `step-implementation.md`. `git log -1 --format='%h' -- .claude/workflow/track-code-review.md` → `03eac656fa` ("Phase-C review iteration keyed to the per-track complexity tag (#1188)").
- **Mechanism**: The delta temp-path and the context-block heading/prose appear in exactly the two named files and nowhere else in `.claude/**`; the only §1.7(k) mention of step 8 is a prose pointer, not a copy. Non-Java Markdown; Read/Grep authoritative.
- **Verdict**: ENFORCED
- **Detail**: —
