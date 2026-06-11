# Research (Phase 0)

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Overview | planner | 0 | Phase 0 is interactive research and exploration; no plan or design documents are produced. |
| §Goal | planner | 0 | Build shared user-agent understanding of the codebase, constraints, alternatives, and prior art. |
| §The research log | planner | 0,1 | The five-section durable decision ledger Phase 0 writes; the append cadence and the one-way read-scope discipline (S2). |
| §How it works | planner | 0 | The user drives an exploration loop; the agent answers, explores code, and never starts planning early. |
| §Transition to Phase 1 | planner | 0,1 | On the user's go-ahead, summarize findings and carry every decision into Phase 1 planning. |
| §Rules | planner | 0 | No premature planning, stay responsive, be thorough, surface trade-offs, record decisions, internet research allowed. |
| §Tooling for code research | planner | 0 | Reference-accuracy questions go through PSI via mcp-steroid, not grep; grep is fine only for orientation. |

<!--Document index end-->

## Overview
<!-- roles=planner phases=0 summary="Phase 0 is interactive research and exploration; no plan or design documents are produced." -->

This document covers Phase 0 of the development workflow — interactive
research and exploration before planning. The agent answers user questions
related to the aim of the change, performs code research, and does internet
research. This is an open-ended, user-driven conversation — the agent does
not produce any plan, track, or design documents during this phase. The
one durable artifact Phase 0 writes is the **research log** (§The research
log) — a decision ledger, not a plan or design.

Research completes **only when the user explicitly asks to create the plan
and design documents** (e.g., "create the plan", "let's plan this",
"proceed to planning"). Until then, the agent stays in research mode.

## Goal
<!-- roles=planner phases=0 summary="Build shared user-agent understanding of the codebase, constraints, alternatives, and prior art." -->

Build shared understanding between the user and the agent about:
- The relevant parts of the codebase
- Technical constraints and trade-offs
- Alternative approaches and their implications
- External references (libraries, algorithms, papers, prior art)

The output of this phase is the **research log** — a durable on-disk
ledger (`_workflow/research-log.md`) that captures the verbatim aim and
the decisions, surprises, and open questions the user and agent discuss,
so the record survives a `/clear` and the Phase 0 → 1 boundary instead of
living only in conversation context. See §The research log below for the
structure and the append cadence. The log is carried forward into Phase 1
(Planning) within the same session and seeds every Phase-1 artifact.

## The research log
<!-- roles=planner phases=0,1 summary="The five-section durable decision ledger Phase 0 writes; the append cadence and the one-way read-scope discipline (S2)." -->

The research log (`_workflow/research-log.md`) is the single durable
Phase-0/1 decision ledger. It is produced in **every** tier (the tier is
not yet chosen during Phase 0; the log is what Step 4 reads to propose
one), and it is the one artifact present across all three tiers — which is
why the relocated adversarial review gates the log rather than a
tier-specific artifact (see `prompts/adversarial-review.md`
§Research-log-scoped review (Phase 0→1) and `planning.md` §Tier
classification).

**Six sections:**

- `## Initial request` — the verbatim aim, written **once** at aim
  capture so a later session boundary never re-asks it. A plan-at-start
  anchor, not an append log.
- `## Decision Log` — append-only. One entry per decision the user and
  agent settle, each with the `**Why:**` and `**Alternatives rejected:**`
  fields the adversarial gate challenges.
- `## Surprises & Discoveries` — append-only. Codebase realities and
  assumptions surfaced during exploration.
- `## Open Questions` — append-only. Unresolved questions carried toward
  planning.
- `## Baseline and re-validation` — filled **only** on a
  workflow-modifying branch (the rebase-drift anchor such a branch needs);
  omitted otherwise.
- `## Adversarial gate record` — the durable verdict carrier the Phase-0→1
  adversarial gate writes. One `### Adversarial review of this log (<ISO>) — <PASS | NEEDS REVISION[: <counts>]>`
  heading per gate iteration, each followed by a one-line note pointing at
  the iteration's ephemeral `_workflow/reviews/research-log-adversarial-iter<N>.md`
  file. This section is the gate's on-log record, distinct from those
  ephemeral review files; it is what the Step-4a/4b cold-reads and the
  Phase-4 fold read as the verdict. See the gate-record cadence below.

**Append cadence.** Write `## Initial request` when the user gives the
aim. Append to the three continuous-log sections as research proceeds,
each entry carrying an ISO timestamp and a context-level tag (the same
`[ctx=<level>]` convention episodes use), so a resumed session can read
what was decided and when. The log is append-only through Phase 0 and
continues to accept appends during Phase 1 design authoring (a
load-bearing decision surfaced while authoring `design.md` is appended
and re-triggers the adversarial gate — see `planning.md` §Tier
classification and `prompts/design-review.md`).

**Gate-record cadence.** The `## Adversarial gate record` section is the
single definition of the gate's durable verdict heading, referenced by every
consumer rather than re-spelled. The canonical heading shape is:

```
### Adversarial review of this log (<ISO>) — <PASS | NEEDS REVISION[: <counts>]>
```

The Phase-0→1 gate (`create-plan` §Step 4) appends one such heading per gate
iteration at each gate-clear or re-challenge, and the D15 review-hold batch
appends one per batch gate run. An entry is **open** when its heading reads
`NEEDS REVISION` with any unresolved blocker or should-fix; it is **resolved**
when the latest dated heading reads `PASS`. A looped (multi-iteration) gate
writes one heading per iteration, so a consumer checking gate state **matches
the latest dated entry**, not any earlier one. This is the single carrier the
freeze-order gate (S3) reads — `edit-design` §Step 4 and the Step-4b cold-read
block while the latest entry is open.

**Read-scope discipline (S2).** The log → carrier flow is strictly
one-way: the log is read for decision *content* in exactly two places: at
Step 4a/4b artifact authoring (to seed the carriers) and by the Phase-2
consistency review (as a cross-check). It is never cross-linked from the
artifacts it seeds. The `## Adversarial gate record` heading is a
verdict/status read, not a decision-content read, so the gate consumers
reading it add no third decision-content site. After a track absorbs a
decision as an inline Decision Record, the **track** is authoritative and the
log is historical provenance. The log is removed in the Phase 4 cleanup with the rest of
`_workflow/`, so any audit trail that must survive merge is folded into a
durable carrier (`adr.md`, or the `minimal`-tier PR-description summary),
not left in the log.

## How it works
<!-- roles=planner phases=0 summary="The user drives an exploration loop; the agent answers, explores code, and never starts planning early." -->

1. The user runs `/create-plan` (optionally with a directory name argument).
2. The agent reads workflow documents, then asks the user for the aim.
3. The user provides the aim. The agent writes the log's `## Initial
   request` and enters **research mode**.
4. In research mode, the agent:
   - Answers user questions about the codebase, architecture, and design
   - Explores code (reads files, searches for patterns, traces call chains)
   - Performs internet research when asked (web search, fetch documentation)
   - Presents findings and intermediate conclusions
   - Helps the user evaluate trade-offs and alternatives
   - Appends decisions, surprises, and open questions to the research log
     as they settle (§The research log)
   - Does **NOT** produce plan files, design documents, or track decompositions
5. The user drives the conversation — asking questions, requesting deeper
   investigation, or steering toward specific areas.
6. When the user is satisfied with the research and explicitly asks to
   create the plan, the agent transitions to Phase 1 (Planning).

## Transition to Phase 1
<!-- roles=planner phases=0,1 summary="On the user's go-ahead, summarize findings and carry every decision into Phase 1 planning." -->

When the user says to create the plan:

1. The agent confirms the research log captures the key findings and
   decisions from the conversation, appending any that were settled but
   not yet logged. The log is the durable seed for planning — it ensures
   the planning phase builds on what was decided, not just what the agent
   happens to remember after a context boundary.
2. Step 4 of `create-plan` reads the now-rich log to propose the change
   tier (`full` / `lite` / `minimal`) and runs the relocated adversarial
   review on the log as a gate before any Phase-1 artifact is authored
   (see `planning.md` §Tier classification).
3. The agent proceeds to Phase 1 (Planning) — producing the
   tier-appropriate artifacts (the aggregator plan and self-contained
   track files in every tier; a `design.md` in `full`). All decisions,
   surprises, and open questions in the research log **must** inform the
   artifacts:
   - Track Decision Records absorb the log's load-bearing decisions and
     their rejected alternatives
   - Architecture Notes build on codebase exploration findings
   - Track descriptions incorporate constraints discovered during research
   - In `full`, the `design.md` reflects the design choices discussed with
     the user

## Rules
<!-- roles=planner phases=0 summary="No premature planning, stay responsive, be thorough, surface trade-offs, record decisions, internet research allowed." -->

- **No premature planning.** Do not start writing plan files, track
  decompositions, or design documents until the user explicitly asks.
- **Stay responsive.** Answer what the user asks. Do not steer the
  conversation toward planning unless the user signals readiness.
- **Be thorough.** When exploring code, read the actual sources — do not
  guess based on class names or package structure alone.
- **Surface trade-offs.** When presenting findings, highlight alternatives
  and trade-offs so the user can make informed decisions.
- **Record decisions in the research log.** When the user makes a
  decision during research (e.g., "let's use approach X"), acknowledge it
  clearly and append it to the log's `## Decision Log` with its `**Why:**`
  and `**Alternatives rejected:**` fields. The log, not conversation
  memory, is what carries decisions across a `/clear` and into planning.
- **Internet research is allowed.** Use web search and web fetch when the
  user asks about external libraries, algorithms, standards, or prior art.

## Tooling for code research
<!-- roles=planner phases=0 summary="Reference-accuracy questions go through PSI via mcp-steroid, not grep; grep is fine only for orientation." -->

Research routinely produces conclusions that planning, decomposition, or
deletion decisions ride on later — "this method has no production
callers", "this slot has no consumer", "the field is touched only
inside its declaring class". Those are reference-accuracy questions and
must be answered through the IntelliJ PSI search via mcp-steroid when
the IDE is connected, not through grep. See
conventions.md:any:any `§1.4` *Tooling discipline* for the
full rule (preflight, fallback when unreachable, sub-agent
delegation). The session-start hook prints the `mcp-steroid: …` status
line — act on it before the first symbol audit. Grep is fine for
orientation (filename globs, unique string literals, "is there
anything called X anywhere") but the load-bearing answer must be
PSI-backed when the IDE is reachable.

When routing exploration to a sub-agent (Explore or any other),
explicitly instruct it to use mcp-steroid PSI find-usages for symbol
questions — sub-agents default to grep otherwise.

**Write-or-exempt for the Explore delegation.** Research/audit sub-agents
are a covered bulk-producer class under the review-file coverage rule
(`conventions-execution.md` `§2.5` → Coverage (S5)). For an Explore
delegation, apply the rule by the same in-session-consumption test the
Phase 1 cold-read uses: write a file (and return a summary the planner
pulls on demand) when the exploration output would otherwise accumulate
in this long-lived planning session; otherwise the delegation is
`exempt because…` its output is consumed in-session by the planner's own
conversation within the same research turn, never retained in an
accumulating orchestrator context — the same rationale that exempts the
Phase 1 cold-read. State whichever applies when delegating; do not leave
the asymmetry implicit.

For module-graph questions during research ("does `embedded` depend
on `server`?", "what depends on `lucene`?", "is the Maven module
boundary where the prose says it is?") load the
**`project-dependencies`** recipe in conventions.md:any:any `§1.4`
*Recipes* rather than reading several `pom.xml` files by hand.
