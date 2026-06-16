<!-- review-manifest
verdict: pass
mode: verdict-producer
batch_decision: D12
d12_verdict: VERIFIED
consistency: "D12 is consistent with D10 (single pipeline doc preserved — START is a lead section, not a new file) and D11 (PIPELINE.md already the sole collapsed pipeline file; D12 adds its lead section, introduces no file). One descriptive lag noted, not a contradiction."
findings: 1
index:
- id: A6, sev: suggestion, anchor: "#a6-suggestion", loc: "research-log.md D12", cert: "Challenge: D12 — embedded START block vs separate START.md vs builder README", basis: model-MAINTENANCE_PROMPT-and-README-structure
-->

# Adversarial Review — Research Log (Phase 0 → 1 gate), iter 3 (verdict-producer, D15 review-hold batch)

**Target:** `docs/adr/workflow-book/_workflow/research-log.md` — D12 only (batch decision)
**Mode:** verdict-producer; D1–D11 not re-challenged except where D12 conflicts
**Verdict:** PASS — D12 is sound (VERIFIED); no blocker, no should-fix; one suggestion (A6). D12 is consistent with the already-PASSED D10 and D11.

D12 chooses to embed the operator's copy-paste START prompt as the lead section of
`PIPELINE.md`, rejecting a separate `START.md` paste-target. The decision survives challenge
on the strongest grounds available: it mirrors the chosen base model's actual structure.

## D12 verdict — VERIFIED

The base model's `MAINTENANCE_PROMPT.md` is a single file whose lead section is
`## Prompt (copy-paste this block into a new Claude Code session)`, with the remainder being
operator context ("The rest of this file is context — the prompt is self-contained and will
re-read what it needs"). D12 reproduces that exact shape: one pasteable lead block, operator
context below, the four `prompts/*.md` role files spawned by the orchestrating session rather
than pasted by hand. So D12's "mirrors the base model exactly" claim is accurate, not
aspirational — the model puts the paste block inside the prompt file itself, not in a
separate entry file.

**Rejected `START.md` alternative — correctly rejected.** A separate `START.md` that points
at `PIPELINE.md` adds one indirection hop (operator opens START, START says "see PIPELINE")
and a second file whose content must stay aligned with the pipeline's empty-vs-non-empty
baseline branch (D10). The model demonstrates the embedded-lead pattern works without that
second file, and D11 explicitly minimizes the machinery file count by collapsing
PRODUCTION_PROMPT + MAINTENANCE_PROMPT into the single `PIPELINE.md`. A separate START.md
would reintroduce a file-count cost D11 just removed, for a discoverability gain the model
shows is unnecessary. D12 survives.

**Consistency with D10 (already PASSED).** D10 mandates one unified pipeline doc, not a
separate PRODUCTION_PROMPT + MAINTENANCE_PROMPT. D12 adds a *lead section* to that one doc; it
creates no new file and preserves the single-pipeline-doc property. D12's Risks/Caveats
explicitly carries D10's empty-vs-non-empty-baseline branch into the start block ("it must
carry the empty-vs-non-empty-baseline branch (D10) so one paste covers both initial production
and evolution"), so the single human entry point covers both the from-scratch and incremental
control-flow arms D10 flagged. No contradiction.

**Consistency with D11 (already PASSED).** D11's file list already names `PIPELINE.md` as "the
unified evolution-aware pipeline (D10)" and the sole collapse target of the two former
prompts. D12 places the START block inside that exact file — it introduces no file absent from
D11's list and contradicts none present. The only seam is descriptive (see A6): D11's one-line
gloss of `PIPELINE.md` does not yet mention the embedded START lead section D12 adds. That is
narrative lag in an earlier entry, not a layout conflict — D12 is the later refinement and the
plan derives from both together.

## Findings

### A6 [suggestion]
**Certificate:** Challenge: D12 — embedded START block vs separate START.md vs builder README
**Target:** Decision D12 (operator entry point embedded as PIPELINE.md's lead section)
**Challenge:** D12 lists only one rejected alternative (a separate `START.md`). The base model
actually has a *third* arrangement D12 does not name: the paste block lives in the prompt file
(`MAINTENANCE_PROMPT.md`) while the **`README.md` carries the "Start here" pointer** for both
readers and maintainers. The workflow-book mirror of that would be a one-line "to start a run,
paste the lead block of `PIPELINE.md`" pointer in `workflow-book-builder/README.md` (if the
builder gets a README) or in `docs/workflow-book/README.md`'s production-record section.
D12's choice (paste block in PIPELINE.md) already matches where the model puts the *paste
block*, so this is not a competing entry point — it is a discoverability pointer that
complements D12 rather than replacing it. Separately, D11's one-line description of
`PIPELINE.md` does not mention the embedded START lead section, a small narrative lag the
plan author should reconcile so D11's file gloss and D12 agree.
**Evidence:** `docs-ytdb-internals-book/.../MAINTENANCE_PROMPT.md` lead `## Prompt` block +
`README.md` "Start here" (maintainers → BOOK_BRIEF / readers → TOC) — the model's paste block
and its discoverability pointer live in different files; D12 adopts the paste-block placement
but the pointer side is unaddressed. `research-log.md` D11 PIPELINE.md gloss vs D12.
**Verdict:** HOLDS — D12's placement is correct and matches the model; the suggestion is
additive (a README pointer) plus a one-line D11 reconciliation, neither of which changes D12.
**Proposed fix:** Optionally note in D12 (or D11) that `workflow-book-builder/README.md`
carries a one-line "start here → paste PIPELINE.md's lead block" pointer mirroring the model's
README "Start here", and update D11's `PIPELINE.md` gloss to mention its embedded START lead
section so the file list and D12 read consistently. Both are cosmetic; the gate clears without
them.

## Evidence base

#### Challenge: D12 — embedded START block vs separate START.md vs builder README
- **Chosen approach:** copy-paste START prompt embedded as the lead section of `PIPELINE.md`;
  one human paste-target; role prompts spawned by the orchestrating session (D12).
- **Best rejected alternative (listed):** a separate `START.md` pointing at `PIPELINE.md`.
- **Unlisted alternative:** the model's split — paste block in the prompt file, a "Start here"
  pointer in `README.md`.
- **Counterargument trace:**
  1. The model's `MAINTENANCE_PROMPT.md` lead section is the copy-paste Prompt block; the rest
     is operator context — exactly D12's shape.
  2. A separate `START.md` would reintroduce the second file and indirection hop D11 removed by
     collapsing the two prompts into one `PIPELINE.md`, for a discoverability gain the model
     shows is unnecessary.
  3. The README-pointer arrangement is complementary, not competing — it points at the same
     embedded block D12 defines.
- **Codebase evidence:** `MAINTENANCE_PROMPT.md` (single-file, lead paste block) and
  `README.md` "Start here" in `docs-ytdb-internals-book/`; D11's PIPELINE.md collapse in the
  research log.
- **Survival test:** YES — D12's placement mirrors the model exactly and is consistent with D10
  and D11; the only open items are an additive README pointer and a one-line D11 gloss
  reconciliation, both cosmetic.

#### Consistency check — D12 vs D10 / D11
- **D10:** single pipeline doc preserved — START is a lead section of PIPELINE.md, not a new
  file; D12's Risks/Caveats carries D10's empty-vs-non-empty baseline branch into the start
  block. Consistent.
- **D11:** PIPELINE.md is already the sole collapsed pipeline file in D11's layout; D12 adds
  its lead section and introduces no file outside D11's list. Consistent; one descriptive lag
  in D11's PIPELINE.md gloss noted under A6.
