# Drafted replacement text for Mutations 8-15

Companion to `handoff-restructure-application.md`. Each section below is the
ready-to-apply replacement text for one remaining mutation in the
YTDB-965+842+975 design.md restructure. Apply via `/edit-design` (one
mutation per invocation) in the order listed in the handoff file. Delete
this file as part of the final cleanup (covered by Phase 4 `_workflow/`
sweep).

Mutations 6 and 7 are already applied and logged in `design-mutations.md`.

---

## Mutation 8 — `structural-rewrite` on §"Phase 1b plan derivation and ESCALATE back-edge"

**Target old section:** lines 659-700 in current design.md (`## Phase 1b plan derivation and ESCALATE back-edge` through to but not including `## Cross-reference tier mapping`).

**Mutation kind:** `structural-rewrite`
**Cold-read scope:** `whole-doc`
**Closes Mutation-6 blockers:** F4 (auto-resume detector pointing at vanished `feasibility-review.md`), F5 (ESCALATE entry shape).

**Replacement text:**

```markdown
## Phase 1b plan derivation and ESCALATE back-edge

**TL;DR.** A separate `/create-plan` session auto-resumes from a validated `design.md` and writes the strategic document: Architecture Notes, Decision Records (seeded from `decision-log.md ## Plan-time Decisions`), the track checklist, and one `plan/track-N.md` per declared track. The resume detector scans `decision-log.md ## Plan-time Decisions` for the most recent entry tagged `(Phase 1a → 1b)` (signaled by the user during Phase 1a) and verifies that `implementation-plan.md` does not yet exist. When the session hits a fundamental contradiction (missing primitive in the design, circular track dependency, step that cannot fit under ~5–7 steps without splitting a design-level construct), the agent writes a `(Phase 1b ESCALATE)`-tagged entry to `decision-log.md`, prints an explicit user-facing message, and ends the session.

The split into a separate session is the structural answer to working-memory pressure that Phase 1a design iteration accumulates. By the time `edit-design` has run many mutations plus the per-mutation fan-out has run its reviewers many times, the session's context carries design-iteration reasoning that would bias plan derivation. A fresh Phase 1b session forces the planner to re-derive plan structure from the durable artifacts (`design.md`, `decision-log.md`) rather than from chat-buffer memory. This mirrors the A/B/C session-boundary contract: durable files cross; chat context does not.

### Auto-resume detection

`/create-plan` startup scans `decision-log.md ## Plan-time Decisions` from most recent entry backward. The first encountered entry's tag determines the routing:

- `(Phase 1a → 1b)` + `implementation-plan.md` absent → **Phase 1b auto-resume**. Read `design.md`, read `decision-log.md` end-to-end, derive plan content.
- `(Phase 1b ESCALATE)` → **Phase 1a re-entry**. Surface the ESCALATE note; enter Phase 1a; user issues an `edit-design` mutation addressing the contradiction.
- No matching entry → **fresh start**. Run Phase 0 (research) → Phase 1a → Phase 1b as usual.
- `(Phase 1a → 1b)` + `implementation-plan.md` already exists → **resume Phase 1b mid-derivation** (the session was paused mid-derivation; pick up from where the partial plan content left off). Resume detection reads the plan file to identify the last written track and continues from there.
- Malformed log → ask the user explicitly which phase to enter.

The detector walks `## Plan-time Decisions` rather than a separate audit file because every phase-transition event the workflow needs to detect lives there: Phase 1a → 1b transition, Phase 1b ESCALATE, Phase 1a accepted-open-risks, per-mutation gate-verdict summaries. One file owns the workflow's transition log; one read at session start serves both auto-resume detection and rationale-seeding.

### Plan-derivation mapping from decision-log.md

After Phase 1b auto-resumes, the planner reads `decision-log.md` end-to-end and maps entries to plan content:

- A `## Plan-time Decisions` entry's `**Why:**` becomes the DR's rationale bullet; `**Alternatives rejected:**` becomes the DR's alternatives bullet. The one-line decision text becomes the DR title. Phase 1b entries that the planner appends during plan derivation (with the `(Phase 1b)` annotation) seed late-arriving DRs.
- A `## Plan-time Surprises` entry's `**Implication:**` becomes Component Map intent, Architecture Notes content, or Integration Points content depending on what it constrains. `**Source:**` survives as evidence.
- A `## Plan-time Open Questions` entry resolves one of three ways: written as a DR / invariant / non-goal in the plan; folded into an existing DR's risks; surfaced to the user with the question text. The planner does not silently elide unresolved questions.
- `(Phase 1a gate-verdict)` and `(Phase 1a accepted-open-risks)` entries are read but not re-aggregated into the plan; Phase 4 ADR aggregation handles them.
- `(Phase 1a → 1b)` is the transition signal only; not aggregated.
- `(Phase 1b ESCALATE)` entries from prior round trips inform plan derivation but are not aggregated as DRs themselves.

### ESCALATE shape

The ESCALATE entry is a Plan-time Decisions entry with structured sub-bullets:

```markdown
- <ISO timestamp> [ctx=<level>] (Phase 1b ESCALATE) Cannot derive a coherent plan from current design.md
  - **Reason:** <concrete description of the contradiction>
  - **Site:** design.md §"<section name>"
  - **Suggested design change:** <one sentence; the planner's best read>
```

After writing the entry, the planner prints the user-facing message:

> Phase 1b cannot derive a coherent plan from this design — <reason>. End this session; re-invoke `/create-plan` to re-enter Phase 1a, apply an `edit-design` mutation addressing the contradiction, signal "ready for Phase 1b" when the design is updated, and Phase 1b will auto-resume.

The session ends; no partial plan files land on disk. On the next `/create-plan` invocation, the auto-resume detector sees the `(Phase 1b ESCALATE)` entry as most recent, surfaces the ESCALATE note, and routes the session into Phase 1a. The user issues the addressing mutation, the fan-out runs as on any other mutation, and when the user signals "ready for Phase 1b" again, a fresh Phase 1b session auto-resumes. Multiple ESCALATE round trips are allowed; each leaves an audit trail in `decision-log.md`.

### Edge cases / Gotchas

- **What counts as "fundamental contradiction".** Three concrete shapes: (1) a referenced primitive the design assumes exists but no code or convention provides; (2) a circular dependency between two proposed tracks where neither can run before the other; (3) a track whose work cannot fit under ~5–7 steps without splitting a design-level construct the design treats as a single concept. Non-fundamental issues (a track that needs to split into two siblings; a section that needs an additional Architecture Notes paragraph) are solved inline during Phase 1b without ESCALATE.
- **Partial plan files on ESCALATE.** The planner does not write half a plan and bail. ESCALATE detection happens before any `implementation-plan.md` content lands. If the contradiction surfaces after some `plan/track-N.md` files have been written, the planner deletes them as part of the ESCALATE write so the worktree is clean for the Phase 1a re-entry.
- **User-driven ESCALATE.** The user can request "rethink the design" or "go back to Phase 1a" at any point during Phase 1b; the planner treats this as an ESCALATE signal, captures the user's stated reason in the entry, and ends the session.
- **Auto-resume false negative.** If the most recent `## Plan-time Decisions` entry is malformed or its tag is unrecognized, the detector falls back to asking the user explicitly: *"`decision-log.md` cannot be parsed; should I treat this as Phase 1a or Phase 1b?"* The user picks; the session continues accordingly.
- **Phase 1b on a pre-YTDB-975 design.** A design authored before YTDB-975 lands (an in-flight branch without the per-mutation fan-out) has no `(Phase 1a → 1b)` entry. The auto-resume detector treats this as the pre-YTDB-975 path: Phase 1b runs without a fan-out-validated design, and Phase 2 review covers the gap. New branches authored after YTDB-975 always carry the transition entry.

### References

- YTDB-975 — acceptance criteria for the Phase 1 split and ESCALATE back-edge.
- §"Per-mutation design review fan-out" — what runs during Phase 1a before the user signals transition.
- §"Decision-log file shape" — the file Phase 1b reads end-to-end.
- `.claude/workflow/workflow.md § Session Boundary Rules` — the A/B/C contract Phase 1a → 1b mirrors.
```

---

## Mutation 9 — `structural-rewrite` on §"Overview"

**Target old section:** lines 4-22 in current design.md (`## Overview` through to but not including `## Core Concepts`).

**Mutation kind:** `structural-rewrite`
**Cold-read scope:** `whole-doc`
**Closes Mutation-6 blockers:** F1, F2 (the line-12 / line-16 / line-22 stale phrasing) + should-fix F7 (`conventions.md §1.4` ref), F9 (`_workflow/design-reviews/` in §1.2 enumeration), F10 (finding-ID prefix list update).

**Replacement text:**

```markdown
## Overview

Today, `.claude/workflow/` carries a coherent body of conventions but never names the design philosophy those conventions encode. Phase 0 (research) accumulates user-agent conversation that evaporates into chat context. The single-shot summary at `create-plan` Step 4 is the only thing Phase 1 sees, so research drift across `/compact` or partial pauses at the context-warning threshold silently lose information. Phase 1 design iteration captures mechanics in `design-mutations.md` but no rationale; design review fires once per mutation via `edit-design`'s cold-read sub-agent and never against the dimensions the design needs most — reference-accuracy against the codebase, devil's-advocate counter-arguments, optional domain checks (crash-safety / concurrency / performance for content; workflow-changes for design touching `.claude/workflow/**` or `.claude/skills/**`). And Phase 1 itself authors `design.md` last, after Architecture Notes and the track checklist, so the design back-fills decisions the plan already crystallized around.

This design closes those gaps with one philosophy artifact pair, one durable rationale file, a Phase 1 split into 1a/1b, and a richer per-mutation review fan-out:

- A lean `### Design philosophy` subsection inside `.claude/workflow/conventions.md` naming seven principles in one sentence each, plus a new load-on-demand `.claude/workflow/design-philosophy.md` carrying the paragraph-length explanations, the workflow-mapping table, the six failure modes, and the external citations (YTDB-842).
- A new `_workflow/decision-log.md` durable file carrying the verbatim user aim, Phase 0 decisions / findings / open questions, Phase 1a / 1b rationale entries, gate-verdict entries (PASS / accepted-open-risks), the Phase 1a → 1b transition signal, and ESCALATE entries from 1b back to 1a (YTDB-965 plus the Phase 1 extension).
- A Phase 1 split into Phase 1a (design-first authoring under per-mutation review fan-out) and Phase 1b (plan derivation in a fresh session), with the ESCALATE back-edge from 1b to 1a when plan derivation hits a fundamental contradiction (YTDB-975).
- A per-mutation review fan-out integrated into `edit-design` Step 4: cold-read (existing) plus two new mandatory reviewers (feasibility-review for PSI-backed reference accuracy; adversarial-design-review for devil's-advocate counter-arguments) plus optional content-triggered domain reviewers (crash-safety / concurrency / performance for storage / locking / hot-path content; workflow-changes for content touching `.claude/workflow/**` or `.claude/skills/**`). Workflow-changes triggers four sibling reviewers mirroring the existing code-side `.claude/agents/review-workflow-*` set at design-doc scope: consistency, context-budget, instruction-completeness, prompt-design. Every `edit-design` mutation triggers the fan-out; `phase1-creation` is not special. Reviewers write raw certificate-shape output to per-reviewer files under `_workflow/design-reviews/cycle-N-iter-M/`. An aggregator sub-agent consolidates them and returns a structured findings object to the orchestrator. Decision-shaped findings batch at user checkpoints between autonomous mechanical-fix chains.

These changes ship together because they share the same workflow files (`planning.md`, `research.md`, `create-plan/SKILL.md`, `workflow.md`, `implementation-review.md`, `edit-design/SKILL.md`, the new `_workflow/` directory layout in `conventions.md` §1.2, and a new sub-section in `conventions.md §1.4` defining the sub-agent prompt-by-reference spawn protocol all spawns use). The intellectual coupling is real: Principle 7 (Lean documents, load on demand) justifies the philosophy split's two-file shape AND motivates the prompt-by-reference protocol; YTDB-975 explicitly depends on YTDB-842 so its philosophy citations resolve; the decision-log shape depends on the Phase 1a / 1b split being known; the per-mutation review fan-out depends on the aggregator wiring + prompt-by-reference protocol landing in the same PR. Splitting into separate PRs would re-do `conventions.md`, `planning.md`, `research.md`, `create-plan/SKILL.md`, and `edit-design/SKILL.md` edits two or three times.

Phase 1a exits on a user signal recorded as a Plan-time Decisions entry; Phase 1b auto-resumes by reading that entry from `decision-log.md`. ESCALATE from Phase 1b is a Plan-time Decisions entry too. The new artifact set is: `decision-log.md` (knowledge — rationale, gate verdicts, transition signals), `design-mutations.md` (mechanics — unchanged), and `_workflow/design-reviews/cycle-N-iter-M/` (raw reviewer output, per-reviewer file). No separate `feasibility-review.md` — gate verdicts live in `decision-log.md` per the one-concern-per-file rule. The orchestrator never loads reviewer prompt bodies into its context; sub-agents read their prompts on entry per the new spawn protocol (savings ~10-20x in orchestrator context per mutation, depending on which optional domain triggers fire).

No `design-mechanics.md` companion — this is a small design under the length trigger; the single-file default applies.

The rest of this document is structured as: Core Concepts → Class Design → Workflow → ten topic sections (design philosophy with lean subsection plus detailed-doc split, decision-log file shape, initial-request write contract, write triggers, Phase 0 → Phase 1a transition mechanics, per-mutation design review fan-out, design-doc review directory shape, Phase 1a design-iteration rationale, Phase 1b plan derivation and ESCALATE back-edge, cross-reference tier mapping).
```

---

## Mutation 10 — `structural-rewrite` on §"Core Concepts"

**Target old section:** lines 24-46 in current design.md (`## Core Concepts` through to but not including `## Class Design`).

**Mutation kind:** `structural-rewrite`
**Cold-read scope:** `whole-doc`
**Closes Mutation-6 blockers:** F1 (Core Concepts line-40 stale entry) + should-fix F7 (novel-construct anchoring).

**Replacement text:**

```markdown
## Core Concepts

This design introduces twelve load-bearing ideas. Each is named and used without re-definition in the sections that follow.

**Design philosophy.** A lean `### Design philosophy` subsection inside `.claude/workflow/conventions.md` (always-loaded) naming seven principles in one sentence each, plus a new load-on-demand `.claude/workflow/design-philosophy.md` carrying paragraph-length explanations, the workflow-mapping table, the six failure modes, and the external citations. The lean subsection points at the detailed doc (two-step). Names what the conventions already do so future "optimizations" pay a visible cost. Replaces the unnamed status quo. → §"Design philosophy".

**decision-log.md.** A new `docs/adr/<dir-name>/_workflow/decision-log.md` file capturing the verbatim user aim, continuous-log entries for decisions / findings / open questions across Phase 0 (research), Phase 1a (design iteration), and Phase 1b (plan derivation), gate-verdict entries (per-mutation aggregator's PASS / NEEDS REVISION summary plus accepted-open-risks blocks), the Phase 1a → 1b transition signal, and ESCALATE entries from Phase 1b. Replaces single-shot Phase 0 → Phase 1 summarization AND absorbs the role the discarded `feasibility-review.md` was going to play. → §"Decision-log file shape".

**Initial request anchor.** A one-shot `## Initial request` section at the top of `decision-log.md` carrying the user's verbatim aim from `create-plan` Step 2. Plan-at-start (no timestamp / ctx field), distinguishing it from continuous-log entries that follow. Phase 1 reads this as the authoritative aim, replacing any "ask the user for the aim again" step. → §"Initial-request write contract".

**Write triggers.** Three events that cause the research agent to append to the log without asking permission: a **decision** (user picks or confirms a choice), a **finding** (PSI-backed reference-accuracy result, paper or library detail that constrains design), an **open question** (item the user defers to planning). Routine Q&A turns where no commitment was made produce no entry. → §"Write triggers".

**Aim-refinement double-write.** When the user refines or expands the aim during early research turns, the agent applies an LLM heuristic with safety net: judge whether the turn refines the goal or explores within it; when in doubt, append to `## Initial request` AND drop a Plan-time Decisions entry pointing back. The cost of a double-write is one extra line; the cost of a misclassification is a lost framing. → §"Initial-request write contract".

**Phase 1a design-iteration rationale entry.** When the user articulates a *why* alongside a `design.md` mutation in Phase 1a, `edit-design` Step 7 appends a Plan-time Decisions entry to `decision-log.md` carrying the rationale and the alternatives rejected. The mechanical record stays in `design-mutations.md` (unchanged). One file per concern. → §"Phase 1a design-iteration rationale".

**Per-mutation design review fan-out.** Integrated into `edit-design` Step 4. Every mutation against `design.md` triggers parallel review sub-agents: cold-read (existing) + feasibility-review (new, PSI-backed reference accuracy, finding ID prefix FD) + adversarial-design-review (new, devil's-advocate counter-arguments, finding ID prefix AD) + content-triggered domain reviewers (crash-safety / concurrency / performance / workflow-changes). Each reviewer writes its certificate-shape output to a per-reviewer file under `_workflow/design-reviews/cycle-N-iter-M/`. An aggregator sub-agent consolidates outputs and returns a structured findings object to the orchestrator. Mechanical findings drive autonomous chained mutations within `edit-design`'s `iteration_budget`; decision-shaped findings queue for the next user-review checkpoint. → §"Per-mutation design review fan-out".

**Design-doc-scoped reviewer prompts.** Six new prompts under `.claude/workflow/prompts/`: `feasibility-review.md` (FD), `adversarial-design-review.md` (AD), `crash-safety-design.md` (CS), `concurrency-design.md` (CC), `performance-design.md` (PF), and a four-sibling set for workflow-changes (`workflow-consistency-design.md`, `workflow-context-budget-design.md`, `workflow-instruction-completeness-design.md`, `workflow-prompt-design-design.md`, finding ID prefixes WCC / WCB / WCI / WCP). Crash-safety / concurrency / performance design prompts are scoped to design prose, not the code-diff inputs of `.claude/agents/review-crash-safety.md` and siblings. Workflow-changes design prompts are scoped siblings of the existing `.claude/agents/review-workflow-*` set, citing the code-side files for the dimensional taxonomy. → §"Per-mutation design review fan-out".

**Aggregator sub-agent.** A sub-agent that runs at the end of every `edit-design` Step 4 fan-out. Reads each per-reviewer file under the current `cycle-N-iter-M/` directory, classifies findings by severity and decision-shape, dedupes cross-reviewer overlap, and returns a small structured object to the orchestrator (~50 lines vs the ~1000+ lines of raw reviewer prose). Writes a one-line summary entry to `decision-log.md` (gate-verdict shape) and an optional `cycle-N-iter-M/summary.md` for human-readable audit. → §"Per-mutation design review fan-out".

**User-review checkpoint.** A user-driven pause between autonomous mechanical-fix chains. Surfaces queued decision-shaped findings, recent autonomous mutations for optional revert, and an open slot for fresh user observations. User signals "apply batch" → orchestrator builds a coordinated set of `edit-design` mutations grouped by target section. The gate stays autonomous within an iteration; user interaction happens at iteration boundaries only. → §"Per-mutation design review fan-out".

**Sub-agent prompt-by-reference spawn protocol.** A new `conventions.md §1.4` sub-section: every sub-agent spawn passes the prompt file as a path reference plus a small inputs block; the sub-agent reads the prompt on entry. Applies to all spawns in the workflow: design-doc reviewers, plan / track reviewers, code reviewers, the aggregator, and a future wrapped-`edit-design` sub-agent. Saves ~10-20x orchestrator context per mutation fan-out. → §"Per-mutation design review fan-out".

**Phase 1b plan derivation.** A separate session that auto-resumes from validated `design.md` and writes the strategic plan: Architecture Notes, Decision Records (seeded from `decision-log.md ## Plan-time Decisions`), the track checklist, and per-track `plan/track-N.md` files. Detects the resume condition by scanning `decision-log.md ## Plan-time Decisions` for the most recent entry tagged `(Phase 1a → 1b)` and verifying that `implementation-plan.md` does not yet exist. → §"Phase 1b plan derivation and ESCALATE back-edge".

**ESCALATE back-edge.** When Phase 1b hits a fundamental contradiction (missing primitive in the design, circular track dependency, step that cannot fit ~5–7 steps without splitting a design-level construct), the planner writes a `(Phase 1b ESCALATE)`-tagged entry to `decision-log.md ## Plan-time Decisions`, prints an explicit user-facing message, and ends the session. The user re-invokes `/create-plan`; the agent loads the ESCALATE note, applies an `edit-design` mutation in Phase 1a, signals "ready for Phase 1b" again, then 1b auto-resumes. → §"Phase 1b plan derivation and ESCALATE back-edge".

**Cross-reference tier mapping.** The set of one-line links from five workflow files (`planning.md`, `design-document-rules.md`, `conventions-execution.md`, `mid-phase-handoff.md`, `research.md`) to the new `conventions.md § Design philosophy`. The links anchor the rules near their motivating principle without duplicating the principle text; a future rename of the subsection cascades through these five sites in one commit (same lockstep-rename precedent as YTDB-836's house-style sections). → §"Cross-reference tier mapping".
```

---

## Mutation 11 — `structural-rewrite` on §"Class Design"

**Target old section:** lines 48-154 in current design.md (`## Class Design` through to but not including `## Workflow`).

**Mutation kind:** `structural-rewrite`
**Cold-read scope:** `whole-doc`
**Closes Mutation-6 blockers:** F3 (Class Design `feasibility_review_log_md` node) + should-fix F9 (design-reviews directory in Class Design).

**Replacement text:**

```markdown
## Class Design

The design touches no Java classes; the "classes" here are workflow artifacts (files / directories) and the SKILLs that read or write them. The diagram below shows the new artifacts plus the existing files this PR modifies, with arrows for reads (`..>`) and writes.

​```mermaid
classDiagram
    class conventions_md {
        +Lean Design philosophy subsection (names + one-sentence summaries)
        +Section 1.2 dir layout entries for decision-log.md and design-reviews/
        +Section 1.4 sub-agent prompt-by-reference spawn protocol
    }
    class design_philosophy_md {
        +Per-principle paragraph explanations
        +Workflow-mapping table (supplementary)
        +Six failure modes
        +External citations
    }
    class decision_log_md {
        +Initial request (one-shot)
        +Plan-time Decisions (append-only; includes gate-verdict entries, Phase 1a to 1b transition, Phase 1b ESCALATE)
        +Plan-time Surprises (append-only)
        +Plan-time Open Questions (append-only)
    }
    class design_mutations_md {
        +Mutation N entries (mechanics only)
        +Periodic whole-doc counter source
        +Working-mode counter source
    }
    class design_reviews_dir {
        +Per-cycle, per-iter directories
        +Per-reviewer files written by reviewer sub-agents
        +Optional per-reviewer-inputs.md for large inputs
        +Optional per-cycle-iter summary.md from aggregator
    }
    class feasibility_review_prompt_md {
        +Phase 1a mandatory prompt
        +PSI-verified reference-accuracy checks against design.md
        +Certificate-shape output per Principle 6
        +Finding ID prefix FD
    }
    class adversarial_design_review_prompt_md {
        +Phase 1a mandatory prompt
        +Devil's-advocate pass against design content
        +Distinct from track-level prompts/adversarial-review.md
        +Finding ID prefix AD
    }
    class crash_safety_design_prompt_md {
        +Design-doc-scoped variant of .claude/agents/review-crash-safety.md
        +Content-triggered (WAL, persistence, recovery, atomic, fsync, crash, durability)
        +Finding ID prefix CS
    }
    class concurrency_design_prompt_md {
        +Design-doc-scoped variant of .claude/agents/review-bugs-concurrency.md
        +Content-triggered (locks, atomics, barriers, synchronized, lock-free, volatile)
        +Finding ID prefix CC
    }
    class performance_design_prompt_md {
        +Design-doc-scoped variant of .claude/agents/review-performance.md
        +Content-triggered (hot-path, allocation, I/O, direct-memory, cache, latency)
        +Finding ID prefix PF
    }
    class workflow_changes_design_prompts {
        +Four sibling prompts mirroring code-side review-workflow-* set
        +Content-triggered (.claude/workflow/, .claude/skills/, .claude/agents/, prompts/, SKILL.md, phase/track/sub-agent)
        +consistency (WCC), context-budget (WCB), instruction-completeness (WCI), prompt-design (WCP)
    }
    class aggregator_subagent_prompt_md {
        +Reads per-reviewer files under cycle-N-iter-M/
        +Classifies by severity and decision-shape
        +Returns structured findings object to orchestrator
        +Writes one-line summary entry to decision-log.md
    }
    class create_plan_skill {
        +Phase 0: Step 1b creates decision-log.md; Steps 2-3 write triggers
        +Phase 0 to 1a: Step 4 reads decision-log.md and confirms aim
        +Phase 1a: per-mutation review fan-out via edit-design; user-checkpoint loop until user signals 1a to 1b transition
        +Phase 1b (fresh session): auto-resumes by reading decision-log.md for Phase 1a to 1b entry; derives Architecture Notes + tracks + plan files
        +Phase 1b ESCALATE: writes ESCALATE entry to decision-log.md, ends session
    }
    class edit_design_skill {
        +Step 4 expanded: reviewer fan-out (cold-read + feasibility + adversarial + triggered domains)
        +Step 4 sub-step: aggregator sub-agent consolidates findings
        +Step 5: iterate on mechanical findings; decision-shaped findings escape to orchestrator
        +Step 7 appends design-mutations.md entry (always)
        +Step 7 appends decision-log.md entry on articulated rationale (Phase 1a only)
        +All sub-agent spawns use prompt-by-reference protocol
    }
    class research_md {
        +Write triggers section (new)
        +Transition to Phase 1 updated for 1a / 1b split
        +Cross-ref to philosophy subsection
    }
    class planning_md {
        +Goal and Design Document re-framed for Phase 1a / 1b roles
        +Phase-overview diagram updated
        +Cross-ref to philosophy subsection
    }
    class mid_phase_handoff_md {
        +Phase 0 handoff keeps in-flight body
        +Open questions section points at decision-log.md
        +Phase 1a handoff shape added
        +Cross-ref to philosophy subsection
    }
    class implementation_review_md {
        +Phase 2 narrowed to plan-internal + plan-vs-design alignment
        +Design-side consistency folds into Phase 1a per-mutation fan-out
    }
    class workflow_md {
        +Phases listed with 1a and 1b sub-phases
        +Session-boundary contract for 1a to 1b
    }
    class conventions_execution_md {
        +Cross-ref to philosophy subsection
        +Cross-ref to conventions.md 1.4 spawn protocol
    }
    class design_document_rules_md {
        +Cross-ref to philosophy subsection
    }

    conventions_md ..> design_philosophy_md : detailed-doc ref
    create_plan_skill ..> decision_log_md : writes (Phase 0)
    edit_design_skill ..> decision_log_md : writes (Phase 1a rationale + gate verdicts)
    edit_design_skill ..> design_mutations_md : writes (mechanics)
    edit_design_skill ..> design_reviews_dir : spawns reviewers that write per-reviewer files
    edit_design_skill ..> aggregator_subagent_prompt_md : spawns aggregator
    aggregator_subagent_prompt_md ..> design_reviews_dir : reads per-reviewer files
    aggregator_subagent_prompt_md ..> decision_log_md : writes gate-verdict summary entry
    edit_design_skill ..> feasibility_review_prompt_md : spawns (every mutation, prompt-by-reference)
    edit_design_skill ..> adversarial_design_review_prompt_md : spawns (every mutation, prompt-by-reference)
    edit_design_skill ..> crash_safety_design_prompt_md : spawns when content-triggered
    edit_design_skill ..> concurrency_design_prompt_md : spawns when content-triggered
    edit_design_skill ..> performance_design_prompt_md : spawns when content-triggered
    edit_design_skill ..> workflow_changes_design_prompts : spawns when content-triggered (four siblings in parallel)
    create_plan_skill ..> decision_log_md : reads (Phase 1b auto-resume signal)
    create_plan_skill ..> research_md : reads
    create_plan_skill ..> planning_md : reads (Phase 1b)
    research_md ..> conventions_md : philosophy ref
    planning_md ..> conventions_md : philosophy ref
    mid_phase_handoff_md ..> conventions_md : philosophy ref
    implementation_review_md ..> decision_log_md : reads (Phase 2)
    workflow_md ..> create_plan_skill : phase listing
    conventions_execution_md ..> conventions_md : philosophy ref + spawn-protocol ref
    design_document_rules_md ..> conventions_md : philosophy ref
​```

Three durable artifacts own three complementary roles: knowledge (`decision_log_md` — rationale, alternatives, open questions, gate verdicts, transition signals, ESCALATE entries), mechanics (`design_mutations_md` — mutation kind, mechanical-check verdict, counter state — unchanged from today), and reviewer raw output (`design_reviews_dir` — per-cycle, per-iter directories containing one file per spawned reviewer plus an optional aggregator summary). One always-loaded surface (the lean `### Design philosophy` subsection inside `conventions_md`) plus one new always-loaded sub-section (`§1.4` spawn protocol) names the principles and protocols every session relies on; one new load-on-demand artifact (`design_philosophy_md`) carries the paragraph-length explanations, the workflow-mapping table, the failure modes, and the external citations.

Eight new prompt files at `.claude/workflow/prompts/` define the per-mutation fan-out's sub-agent behavior: two mandatory (`feasibility_review_prompt_md`, `adversarial_design_review_prompt_md`), three content-triggered domain (`crash_safety_design_prompt_md`, `concurrency_design_prompt_md`, `performance_design_prompt_md`), four sibling workflow-changes prompts (workflow-consistency-design / workflow-context-budget-design / workflow-instruction-completeness-design / workflow-prompt-design-design — collapsed into one node in the diagram for readability), and one aggregator (`aggregator_subagent_prompt_md`). All produce certificate-shape output per Principle 6; all spawn via the prompt-by-reference protocol so their bodies never load into the orchestrator. The workflow-changes set cites the existing code-side `.claude/agents/review-workflow-*` files as the source of the dimensional taxonomy; the design-doc prompts adapt criteria for prose-input rather than code-diff input.

Two SKILLs (`create_plan_skill`, `edit_design_skill`) own the writes. `create_plan_skill` writes to `decision_log_md` across Phase 0 and reads it for the Phase 1b auto-resume signal; `edit_design_skill` spawns the per-mutation fan-out, writes to `design_mutations_md` on every invocation, writes Phase 1a rationale entries to `decision_log_md`, and (via the aggregator) writes per-mutation gate-verdict summary entries to `decision_log_md`. Five workflow documents (`research_md`, `planning_md`, `mid_phase_handoff_md`, `implementation_review_md`, `workflow_md`) wire the new artifacts into existing phase boundaries on their read or coordination side. Five documents (`planning_md`, `design_document_rules_md`, `conventions_execution_md`, `mid_phase_handoff_md`, `research_md`) point at the lean `conventions_md § Design philosophy` subsection (two-step); the lean subsection itself points at `design_philosophy_md` so the deeper material stays load-on-demand. One additional document (`conventions_execution_md`) gains a one-line cross-reference to the new `conventions_md §1.4` spawn protocol.
```

**IMPORTANT for applier:** the Mermaid block above uses zero-width-space-prefixed backticks (`​```mermaid`) to escape the markdown fence boundary in this handoff file. When applying via `/edit-design`, replace those with normal ` ``` ` triple-backticks (one at start, one at end of the Mermaid block).

---

## Mutation 12 — `structural-rewrite` on the Phase 1a design-iteration Workflow diagram

**Target old subsection:** lines 200-219 (`### Phase 1a design-iteration rationale capture` heading + diagram + paragraphs through to but not including `### Phase 1a gate → Phase 1b derivation (with ESCALATE)`).

**Mutation kind:** `structural-rewrite`
**Cold-read scope:** `whole-doc`
**Closes Mutation-6 blockers:** F1 (per-mutation fan-out narrative in the Workflow section).

**Replacement text:**

```markdown
### Phase 1a design-iteration rationale capture (per-mutation fan-out)

​```mermaid
sequenceDiagram
    participant User
    participant ED as edit-design SKILL
    participant Design as design.md
    participant Mut as design-mutations.md
    participant Reviewers as Reviewer sub-agents
    participant ReviewsDir as design-reviews/cycle-N-iter-M/
    participant Agg as Aggregator sub-agent
    participant Log as decision-log.md
    participant Orch as Orchestrator (create-plan)

    User->>ED: request mutation (phase1-creation, content-edit, section-add, ...)
    ED->>Design: Step 1 apply edit (Edit/Write)
    ED->>ED: Step 3 mechanical checks (Bash script)
    par cold-read (existing)
        ED->>Reviewers: spawn cold-read (prompt-by-reference)
    and feasibility-review
        ED->>Reviewers: spawn feasibility-review (FD)
    and adversarial-design-review
        ED->>Reviewers: spawn adversarial-design-review (AD)
    and content-triggered crash-safety
        ED->>Reviewers: spawn crash-safety-design (CS) when triggered
    and content-triggered concurrency
        ED->>Reviewers: spawn concurrency-design (CC) when triggered
    and content-triggered performance
        ED->>Reviewers: spawn performance-design (PF) when triggered
    and content-triggered workflow-changes
        ED->>Reviewers: spawn 4 workflow-changes-* siblings when triggered (WCC, WCB, WCI, WCP)
    end
    Reviewers->>ReviewsDir: each writes raw certificate-shape output to its file
    ED->>Agg: spawn aggregator (prompt-by-reference)
    Agg->>ReviewsDir: read per-reviewer files
    Agg->>Log: write one-line gate-verdict summary entry (Plan-time Decisions)
    Agg-->>ED: return structured findings object
    ED->>ED: Step 6 iterate on mechanical findings within iteration_budget
    alt iteration_budget remaining + new mechanical findings
        ED->>Design: autonomous edit-design mutation (mechanical fix)
        Note over ED,Design: chain re-triggers fan-out (next iter-M)
    else iteration_budget exhausted OR decision-shaped findings outstanding OR PASS
        ED->>Mut: Step 7 append mutation entry (always)
        alt user articulated a why
            ED->>Log: Step 7 append Plan-time Decisions entry (rationale + alternatives)
        end
        ED-->>Orch: return diff + queued decision-shaped findings
        alt decision-shaped findings queued OR user wants to add observations
            Orch->>User: User-review checkpoint (batch surfaces queued findings + recent autonomous mutations for optional revert + slot for fresh observations)
            User->>Orch: decisions + observations + optional reverts
            Orch->>ED: batch-apply mutations (next round opens with user-driven mutation)
        else PASS clean (no decision-shaped findings)
            Orch-->>User: present diff + log entry; await next user input
        end
    end
​```

Two log files never duplicate content. `design-mutations.md` carries operational state (mutation kind, mechanical-check verdict, iteration count, working-mode counter) consumed by `edit-design`'s own machinery. `decision-log.md` carries knowledge (the user's *why*, gate-verdict summaries, accepted-open-risks, transition signals, ESCALATE entries) consumed by Phase 2 cross-reference and Phase 4 ADR aggregation.

Reviewer raw output is the third tier: per-reviewer files in `_workflow/design-reviews/cycle-N-iter-M/` give Phase 4 ADR aggregation and any future audit query the full certificate trace. The aggregator's structured object is what the orchestrator acts on; the orchestrator never reads raw reviewer prose.

The fan-out fires on every `edit-design` mutation. `phase1-creation` is not special — it's the first mutation in a Phase 1a session and triggers the same reviewers any later mutation would. The autonomous mechanical-fix chain inside `edit-design`'s `iteration_budget` may produce multiple sequential mutations (each one re-triggering the fan-out at the next iter-M); the chain converges when reviewers return PASS or the budget is exhausted. Decision-shaped findings always pause the chain and surface at the next user-review checkpoint.
```

**Mermaid fence escape:** same caveat as Mutation 11 — replace the zero-width-space-prefixed backticks with normal triple-backticks when applying.

---

## Mutation 13 — `structural-rewrite` on the Phase 1a → Phase 1b Workflow diagram

**Target old subsection:** lines 222-282 (`### Phase 1a gate → Phase 1b derivation (with ESCALATE)` heading + diagram + paragraphs through to but not including the next `##` H2).

**Mutation kind:** `structural-rewrite`
**Cold-read scope:** `whole-doc`
**Closes Mutation-6 blockers:** F2, F4 (gate-PASS vs user-signal exit; Phase 1b auto-resume detector).

**Replacement text:**

```markdown
### Phase 1a → Phase 1b transition (with ESCALATE)

​```mermaid
sequenceDiagram
    participant User
    participant CP as create-plan SKILL (orchestrator)
    participant Design as design.md
    participant Log as decision-log.md
    participant Plan as implementation-plan.md

    Note over User,CP: Phase 1a: per-mutation fan-out has iterated to a state the user is satisfied with (prior diagram)

    User->>CP: signal "ready for Phase 1b" (any phrasing conveying intent)
    CP->>Log: append Plan-time Decisions entry tagged (Phase 1a → 1b) with user's intent line
    CP->>User: Phase 1a complete; end session; re-invoke /create-plan for Phase 1b
    Note over User,CP: ----- session ends; fresh session below -----

    User->>CP: /create-plan (re-invoked)
    CP->>Log: scan Plan-time Decisions for most recent (Phase 1a → 1b) entry
    alt entry found AND implementation-plan.md does not yet exist
        Note over CP,Plan: Phase 1b auto-resume
        CP->>Design: Read validated design.md
        CP->>Log: Read decision-log.md end-to-end (Decisions + Surprises + Open Questions)
        alt derivation succeeds
            CP->>Plan: Write Architecture Notes + Decision Records + track checklist + plan/track-N.md files
            CP->>User: Phase 1b complete; plan ready
        else fundamental contradiction
            CP->>Log: Append Plan-time Decisions entry tagged (Phase 1b ESCALATE) with contradiction details
            CP->>User: ESCALATE message; end session; re-invoke /create-plan to re-enter Phase 1a
        end
    else most recent entry is (Phase 1b ESCALATE)
        Note over CP,Design: Phase 1a re-entry with ESCALATE note
        CP->>User: surface ESCALATE note; enter Phase 1a; user issues edit-design mutation addressing the contradiction; per-mutation fan-out runs as usual
    else neither found
        CP->>User: ask the user explicitly which phase to enter
    end
​```

The transition is decision-log-driven. There is no separate audit file: the Plan-time Decisions stream carries every phase-transition event the workflow needs to detect. Phase 1a → 1b is a user-driven transition (logged at user signal); Phase 1b ESCALATE is a planner-driven transition (logged when the planner hits a fundamental contradiction).

Phase 1b reads `decision-log.md` end-to-end at session start. The same read serves two purposes: scanning for the auto-resume signal and seeding Decision Records / Architecture Notes from the rationale entries. Phase 2's cross-reference also reads the same file; Phase 4's ADR aggregation reads it end-to-end.
```

**Mermaid fence escape:** same caveat as Mutation 11.

---

## Mutation 14a — `content-edit` on §"Phase 1a design-iteration rationale" integration-point paragraph

**Target old text:** look for the paragraph beginning "The integration point is `edit-design/SKILL.md` Step 7..." inside §"Phase 1a design-iteration rationale".

**Mutation kind:** `content-edit`
**Cold-read scope:** `bounded`

Replace with:

> The integration point is `edit-design/SKILL.md` Step 7 (review log append), which fires after the Step 4 fan-out + Step 6 iteration converge on the current mutation. The fan-out is upstream of this rationale-capture trigger; whether the mutation went through autonomous chained fixes or PASSed cleanly on the first iter doesn't affect whether the user articulated a *why* worth capturing in `decision-log.md`. After appending the per-mutation entry to `design-mutations.md`, the skill checks whether the user's mutation request carried an articulated *why* — a user message naming the rationale, or an explicit phrase like "because", "in order to", "to avoid", "to satisfy constraint X". When it did, the skill also appends a Plan-time Decisions entry to `decision-log.md` using the standard write-trigger format (ISO timestamp + `[ctx=<level>]` + decision line + `**Why:**` + `**Alternatives rejected:**`) plus the `(Phase 1a)` body annotation per §"Decision-log file shape".

---

## Mutation 14b — `content-edit`s on §"Decision-log file shape"

Three small edits in this section:

**14b.1:** In the per-entry format documentation for `## Plan-time Decisions`, extend the annotation list to include the new tags. Old text (in the file-template's HTML comment):

> `<ISO timestamp> [ctx=<level>] <one-line decision> [(Phase 1a) or (Phase 1b) annotation when relevant]`

New text:

> `<ISO timestamp> [ctx=<level>] <one-line decision> [annotation: (Phase 1a) | (Phase 1b) | (Phase 1a gate-verdict) | (Phase 1a accepted-open-risks) | (Phase 1a → 1b) | (Phase 1b ESCALATE) when relevant]`

**14b.2:** In the Lifecycle bullets, the **Written by** entry needs one addition. Old text:

> **Written by.** `create-plan` Steps 2 / 3 (Phase 0 research); `edit-design` Step 7 (Phase 1a rationale capture); `create-plan` Phase 1b plan-derivation writes when the planner articulates rationale during plan authoring.

New text:

> **Written by.** `create-plan` Steps 2 / 3 (Phase 0 research); `edit-design` Step 7 (Phase 1a rationale capture); `create-plan` Phase 1b plan-derivation writes when the planner articulates rationale during plan authoring; the aggregator sub-agent writes per-mutation gate-verdict summary entries during Phase 1a; `create-plan` writes the `(Phase 1a → 1b)` transition entry at user signal and the `(Phase 1b ESCALATE)` entry when plan derivation hits a fundamental contradiction.

**14b.3:** Add one new bullet to the Edge cases / Gotchas list:

> - **Gate-verdict and ESCALATE entries are read by Phase 1b auto-resume detection** (most-recent walk of `## Plan-time Decisions`); they are NOT aggregated into Phase 4 ADR content as decisions in their own right — only the underlying user-articulated rationale entries become ADR decisions. The aggregator's gate-verdict entries and the planner's ESCALATE entries serve audit + transition-detection purposes; Phase 4 reads them for audit but does not promote them to the ADR's key-decisions section.

---

## Mutation 14c — `content-edit` on §"Phase 0 → Phase 1a transition" post-ack paragraph

**Target old text:** the paragraph starting "After the ack, the session enters Phase 1a..." near the bottom of §"Phase 0 → Phase 1a transition".

**Mutation kind:** `content-edit`
**Cold-read scope:** `bounded`

Old text:

> After the ack, the session enters Phase 1a: `design.md` authoring via `edit-design`. The decision-log is left in place; Phase 1a continues to append rationale entries via `edit-design` Step 7 (see §"Phase 1a design-iteration rationale"), and Phase 1b later reads the file end-to-end to seed plan content (see §"Phase 1b plan derivation and ESCALATE back-edge").

New text:

> After the ack, the session enters Phase 1a: `design.md` authoring via `edit-design`. The first `edit-design` invocation is `phase1-creation`; it triggers the per-mutation review fan-out like any other mutation (see §"Per-mutation design review fan-out"). The decision-log is left in place; Phase 1a continues to append rationale entries via `edit-design` Step 7 and per-mutation gate-verdict summaries via the aggregator (see §"Phase 1a design-iteration rationale"); Phase 1b later reads the file end-to-end to seed plan content and detect the auto-resume signal (see §"Phase 1b plan derivation and ESCALATE back-edge").

---

## Mutation 14d — `content-edit` on §"Cross-reference tier mapping"

Add a one-paragraph note after the existing table about the spawn-protocol cross-reference being a separate single-site mapping. Add right before §"Cross-reference tier mapping" `### Edge cases / Gotchas`:

> A separate one-site cross-reference set anchors the new spawn protocol at `conventions.md §1.4.X`. `conventions-execution.md` carries a one-line link to the protocol so a reader entering execution-phase rules sees the spawn rule near the rules that depend on it. This set is intentionally smaller than the philosophy mapping (one site vs five) because the spawn protocol applies symmetrically across all phases — readers entering any specific phase find it via the always-loaded conventions.md without needing per-phase cross-refs.

---

## Mutation 15 — Add `conventions.md §1.4.X` sub-section (direct workflow file edit, NOT via /edit-design)

**Target file:** `.claude/workflow/conventions.md`
**Location:** Add as a new sub-section under §1.4 *Tooling discipline*, between the existing sub-sections (after `### When PSI is required (not optional)` would be natural; the exact placement depends on the current state of §1.4).

**Body to add:**

```markdown
### 1.4.X Sub-agent spawn protocol — prompt by reference

When the orchestrator spawns a sub-agent via the `Agent` tool for any review, aggregator, or wrapped-skill purpose, the spawn prompt MUST pass the prompt file as a path reference, not as the prompt body. The sub-agent reads the prompt file on entry. The orchestrator never loads the prompt body into its context.

Spawn-prompt template (the orchestrator constructs this; never reads the prompt-file body):

> Read `<absolute path to prompts/<name>.md>`.
> Apply the inputs below.
> Write your output to `<absolute path to output file>`.
> Inputs:
> - <key>: <value>
> - <key>: <value>
> - ...

Two cases for inputs:

- **Small inputs** (paths, scope flags, a few finding IDs): embed inline in the spawn body under the `Inputs:` block. No temp file.
- **Large inputs** (cumulative findings across iterations, multi-page context the sub-agent needs in addition to its primary target file): write to disk at `<iter-or-cycle-dir>/<reviewer>-inputs.md` and reference the path in the spawn body's `Inputs:` block. The inputs file lives in the same directory as the sub-agent's output for audit. Discriminator: total inputs > ~50 lines OR inputs contain structured tables / verbatim quoted text → write to disk; otherwise inline.

The orchestrator pays a constant spawn-template cost (~10 lines per dispatch) regardless of prompt size. The prompt body (~60–200 lines for review prompts; multi-hundred lines for richer prompts like `prompts/design-review.md`) never lands in orchestrator context. Per-mutation savings in Phase 1a are estimated at ~10x with mandatory-only fan-out, ~20x when domain triggers fire.

This rule applies symmetrically across the workflow:

- Phase 1a design-doc reviewers (per-mutation fan-out — see `design.md §"Per-mutation design review fan-out"`)
- Phase 2 plan reviewers (`implementation-review.md`)
- Phase A track reviewers (`track-review.md`, `track-adversarial-review.md`, `track-technical-review.md`, `track-risk-review.md`)
- Phase C code reviewers (`track-code-review.md` dispatched via `/code-review`)
- The aggregator sub-agent
- Future wrapped-skill spawns (e.g., a sub-agent `edit-design` invocation if that becomes the routing in a later workflow change)

A prompt file too small to warrant by-reference passing (~under 30 lines) MAY be embedded directly in the spawn body if the orchestrator is constructing the prompt on the fly. Files maintained at `.claude/workflow/prompts/**` always pass by reference regardless of size; the maintenance assumption is that they'll grow.

Reviewer / aggregator output is always written to disk at a path the orchestrator names. The orchestrator's read of the output uses targeted reads (`Read offset / limit`, or reads a small `summary.md` produced by the aggregator) rather than the full reviewer file. Per `conventions.md §1.4` *Recipes* (existing), full-file reads of multi-thousand-line review output are flagged by `review-workflow-context-budget` as instant-consumption hits.
```

This Mutation 15 does NOT go through `/edit-design` (target is `conventions.md`, not `design.md`). Apply via direct `Edit`/`Write` on the workflow file. After landing, run `review-workflow-consistency` against the diff to catch broken cross-references.

---

## After Mutation 15: final whole-doc cold-read

Once Mutations 8-15 are all applied, spawn one final whole-doc cold-read sub-agent on the current state of design.md. The expected outcome is PASS — all six structural-contradiction blockers from Mutation 6's cold-read should have closed:

- F1 (Overview / Core Concepts contradictions) → closed by Mutations 9 + 10
- F2 (Workflow diagram contradictions) → closed by Mutations 12 + 13
- F3 (Class Design contradictions) → closed by Mutation 11
- F4 (§"Phase 1b plan derivation" auto-resume detector) → closed by Mutation 8 + 13
- F5 (§"Decision-log file shape" missing gate-verdict annotation taxonomy) → closed by Mutation 14b
- F6 (Overview section list line 22) → closed by Mutation 9

Plus the should-fix items from Mutations 6 and 7's cold-reads should also be cleared (or downgraded if they were noted as forward-deferred).

If the final cold-read returns NEEDS REVISION, iterate on the residual findings (the iteration budget is the default 3). If it returns PASS, the restructure is complete.

## Commit and push

After all mutations land cleanly:

```
git add docs/adr/ytdb-965-dd-decision-log/_workflow/
git add .claude/workflow/conventions.md
git commit -m "YTDB-965+842+975: Restructure design.md for per-mutation review fan-out"
git push
```

Then delete this `handoff-restructure-application-drafts.md` file and the `handoff-restructure-application.md` file as part of the next session's wrap-up commit (the Phase 4 `_workflow/` cleanup will sweep them anyway, but they're stale once the restructure is done).
