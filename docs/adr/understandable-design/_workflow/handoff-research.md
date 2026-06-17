# Handoff: Phase 0 (research) — two-role design-authoring loop (understandable-design)

**Paused:** 2026-06-16
**Phase:** 0 (research, `/create-plan`)
**Context level at pause:** info (39%, user-requested proactive pause below the warning trigger)
**Branch:** understandable-design
**HEAD:** f31e961c6a "Workflow book builder machinery (#1149)"
**Unpushed:** <no upstream — see workflow.md §What to do before ending a session> (first push this session sets `-u`; draft PR opens at Step 5 after planning)

## What I was investigating

The two-role author/cold-read loop for design-document and track-file authoring —
YTDB-1130's authoring-side companion (comment 3). A code-grounded **author**
sub-agent (generate) paired with a dedicated cold **readability auditor** (verify)
in a bounded loop, to fix the curse-of-knowledge density that the current
single-cold-read review misses.

## Already ruled out / settled (full rationale in research-log.md `## Decision Log`)

- **Domain-density floor is not irreducible** — terms get glossed in-place, mechanisms get worked interleavings/diagrams; the bar is *reconstructibility by a mid-level dev*, not simplicity. (D 13:33Z, 13:38Z)
- **Author is code-grounded**, not log-only (comment-4 measured −50%). Reads log + codebase/PSI, writes for a cold reader. (13:46Z)
- **Cold auditor = a dedicated review role reusing the `readability-feedback` contract**, reads `house-style.md` + the doc only, never the log. Realized as an **agent definition** (for tool allow-lists), not a `prompts/`-only general-purpose spawn. (13:46Z / 16:00Z)
- **One cold review at design creation** (no warm reviewer): the de-warmed `design-review.md` keeps comprehension + structural; absorption is isolated. (14:05Z)
- **Absorption is a per-round check INSIDE the loop** (warm Sonnet, doc-vs-log), co-equal with readability; loop exits only when both clean (cap 3, else escalate). No separate before/after stage → no back-edge thrash. (14:12Z → 14:32Z)
- **§ Human-reader items split by context-need:** auditor (range + Overview/Core-Concepts anchors) owns glossary-introduction, why-before-what, explanatory register, audience-fit prose; comprehension reviewer keeps navigability + "Overview names a reader" + the 7 questions + structural. (15:30Z)
- **phase4 fidelity = primarily doc-vs-episodes** (cheap Sonnet), PSI only for the diagram/signature residual. (15:45Z)
- **Keep Mermaid** — no diagram-format change. (14:45Z)
- **Loop wired at TWO authoring points:** `edit-design` Steps 1/4/6 (design, phase1+phase4) and `create-plan` Step 4b (track files, every tier — so `lite`/`minimal` get readability). (14:38Z / 14:58Z)
- **Cost levers:** minimal tool allow-lists via agent definitions; fan-out cache warm-up (spawn 1 → wait ~1 min in `[cold-write done, 5-min TTL)` → fan out 2–N concurrently) with byte-identical prompt + params-in-file; author grounds whole doc once, targeted re-grounding after; findings-in-file across iterate rounds. (16:00Z / 16:10Z / 16:45Z)
- **MEMORY/CLAUDE.md NOT per-agent configurable** (verified via claude-code-guide vs official docs) — baked into custom agents (~10K); only Explore/Plan skip them and Explore's disposition is wrong for our roles. Mitigated by the cache warm-up, not removal. `tools:` allowlist DOES cut tool-schema cost. (16:45Z)
- **Agent SDK + cross-model review:** deferred (keep Agent tool). Cache is content-keyed not session-keyed, so the SDK *can* cache — the real cost against it is engineering burden + paradigm, and cross-model's readability value is uncertain (1130 says the win is disposition, not model). Separate workflow-wide initiative. (17:00Z / 17:10Z)
- **Collapse the Step-4a/4b session boundary into one `/create-plan` invocation** (the boundary was a context-isolation proxy that sub-agent authoring makes redundant) — conditional on by-reference orchestration; freeze+commit retained as the logical gate + crash checkpoint. (17:25Z)
- **Dogfooding:** cannot self-wire (§1.7 staging keeps the new routine non-live this branch; temporal bootstrap — it's this branch's output). Honor via the existing `readability-feedback` dry-run on this design once authored + a Phase-3 validation against the known-dense `transactional-schema` design.md + natural post-promotion use. (17:40Z)

## Out of scope (→ research-log + PR Motivation Non-Goals)

PR-description readability (existing issue); the 1128/1129 rules (separate PR — the auditor reads live `house-style.md` and absorbs them when they land); diagram-format change; Agent SDK / cross-model review.

## Most promising lead / where we are

**Research is complete.** Every decision is in `research-log.md` (the durable Phase-0/1 ledger). The design shape is fully settled; ready for **Step 4** (tier classification → adversarial gate → design authoring → plan/track derivation).

## Open questions

None blocking. The two design-phase threads (§ Human-reader split; phase4 fidelity cost) are resolved. Remaining items are implementation-mechanism details recorded in the log (e.g., the diff-scoped fidelity delta detection; the warm-up trigger heuristic).

## Raw notes / partial findings

Do not duplicate the decisions here — `docs/adr/understandable-design/_workflow/research-log.md` is the complete record (read its `## Decision Log`, `## Surprises & Discoveries`, `## Open Questions`). Machinery already read and understood this session: `edit-design/SKILL.md`, `prompts/design-review.md`, `skills/readability-feedback/SKILL.md`, `house-style.md` (§ Orientation, § Plain language, the five Document-shape § Human-reader rules), `conventions.md` §1.1/1.2/1.4/1.5.

## Resume notes

- **Do NOT re-explore:** the machinery files listed above; the cost/cache findings (settled); the four headline decisions + the cost/process decisions in the log. Do NOT re-ask the aim — it is in `research-log.md` `## Initial request`.
- **Next action on resume (`/create-plan`, this handoff drives it):** run **Step 4**.
  1. **Tier classification** — propose `full` (workflow-modifying, needs a design, multi-track: rewires `edit-design`, `create-plan` Step 4b, `design-review.md`, `create-final-design.md` + two new agent defs + the absorption/fidelity agent + `conventions.md`). Confirm the centrally-matched HIGH-risk categories with the user (read `risk-tagging.md` §Gate 1 reuse) — likely Architecture / cross-component coordination.
  2. **Adversarial gate** on `research-log.md` (`reviewer-adversarial`, `model: fable` for `full`, output to `_workflow/reviews/research-log-adversarial-iter<N>.md`, thin-manifest return; loop on blockers, gate on should-fix, cap = `iteration_budget` 3). Record the verdict in the log's `## Adversarial gate record`.
  3. **Step 4a** — author `design.md` via `edit-design` (`phase1-creation`). **IMPORTANT — this branch uses the CURRENT (live) workflow**, which is develop-state: the new two-role loop and the 4a/4b collapse are what we are BUILDING (staged, non-live this branch per §1.7), so do NOT apply them to this branch's own authoring. Standard `edit-design` + the standard 4a/4b session boundary apply.
  4. **Step 4b** — plan + track files; seed the phase ledger with `--phase 0 --tier full --categories <…> --s17 <staging-mode>` (this branch is §1.7 staging-bound — workflow-modifying).
  5. **Dogfood (1130 deliverable):** after the design is authored, run `/readability-feedback` on this branch's `design.md` as the verify-half dry-run.
- **Do NOT redo:** the research itself; the design is NOT yet authored (no `design.md`/plan on disk — this is a clean Phase 0 → Step 4 transition, not a mid-authoring resume).
