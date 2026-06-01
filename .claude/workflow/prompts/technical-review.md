## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: reviewer-technical.
Your phase: 3A.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Workflow Context | reviewer-technical | 3A | Phase A terminology (track, step, episode, Decision Record) and where the track's detail lives during decomposition. |
| §Semi-Formal Reasoning Protocol | reviewer-technical | 3A | Every codebase claim needs a documented evidence certificate; no assertion without reading the actual code. |
| §Certificate requirements | reviewer-technical | 3A | Premise, edge-case, and integration certificate templates each track claim is verified against. |
| §Rules for certificates | reviewer-technical | 3A | Search every premise, follow calls interprocedurally, trace edge cases to completion, document negative results. |
| §Output Format | reviewer-technical | 3A | Two-part output: the evidence certificates first, then findings derived from them. |
| §Part 1: Evidence Certificates | reviewer-technical | 3A | The evidence base: all premise, edge-case, and integration certificates in review-criteria order. |
| §Part 2: Findings | reviewer-technical | 3A | Findings derived from non-confirmed certificates; each cites the certificate that produced it. |

<!--Document index end-->

You are reviewing ONE TRACK of an implementation plan for technical soundness.
You MUST read the codebase to validate this track's assumptions.

Prose produced by this file follows the project house-style at `.claude/output-styles/house-style.md`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the canonical workflow-level anchor and tier mapping; the four banned-section heading slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`.

## Workflow Context
<!-- roles=reviewer-technical phases=3A summary="Phase A terminology (track, step, episode, Decision Record) and where the track's detail lives during decomposition." -->

You are a sub-agent spawned during **Phase A (Review + Decomposition)** of
the execution workflow. The overall workflow has five phases: Phase 0
(research), Phase 1 (planning) — together these produced the plan you are
reviewing, Phase 2 (consistency & structural review of the plan — already
passed), Phase 3 (execution — tracks implemented one at a time, each going
through Phase A → Phase B → Phase C), and Phase 4 (final artifacts).

**Key terminology:**
- **Track**: A coherent stream of related work within the plan. Contains
  steps (decomposed later in this Phase A, after your review). Max ~5-7
  steps per track.
- **Step**: A single atomic change = one commit. Fully tested. Step
  decomposition has not happened yet — only scope indicators exist.
- **Episode**: A structured record of what happened during a step or track
  implementation. Track episodes (in the plan file under completed tracks)
  summarize strategic outcomes; step episodes (in track files) contain
  implementation details. Episodes from completed tracks are your evidence
  of what actually happened vs. what was planned.
- **Scope indicator**: A rough sketch of expected work in a track
  (`> **Scope:** ~N steps covering X, Y, Z`). Strategic signal, not a binding contract.
- **Decision Records**: Design choices in the plan's Architecture Notes
  section. Each has alternatives, rationale, risks, and track references.
  They are immutable during execution — changes require formal replanning.
- **Component Map**: Mermaid diagram + annotated bullet list showing which
  system components this plan touches and what changes in each.
- **Invariants**: Conditions that must remain true before/after the change.
  Can be ENFORCED (code already guarantees them), ASPIRATIONAL (tracks need
  to implement them), or VIOLATED (current code contradicts them). Each must
  map to a testable assertion in the relevant step.
- **Integration Points**: How new code connects to existing code — entry
  points, SPIs, callbacks, event flows.
- **Non-Goals**: Explicit scope exclusions to prevent scope creep during
  execution.

**Your role:** Validate this track's approach before implementation begins.
Your findings may lead to plan adjustments, decomposition guidance, or
(if severity is `skip`) a recommendation to skip the entire track. After
your review, the main agent decomposes the track into concrete steps.

**Where things live during Phase A:** The track's detailed description
lives in the track file at
`docs/adr/<dir-name>/_workflow/plan/track-N.md`, split across five
sections: `## Purpose / Big Picture` (intro paragraph + BLUF),
`## Context and Orientation` (what's there today, plus any track-level
component diagram), `## Plan of Work` (what we'll change),
`## Decision Log` (any track-scoped Decision Records, once Move 1
inlines them), and `## Interfaces and Dependencies` (file boundaries,
inter-track deps). All five are seeded at Phase 1 by `/create-plan`
and read (and optionally amended via the Track Pre-Flight gate) by
Phase A. The plan
file carries strategic context (Architecture Notes, Decision Records,
Component Map) and track-level status + episodic memory.

---

Inputs:
- Plan file: {plan_path} (strategic context — Architecture Notes,
  Decision Records, Component Map)
- Track file: {step_file_path} — authoritative source for the track's
  what/how/constraints/interactions and any track-level diagram, split
  across `## Purpose / Big Picture`, `## Context and Orientation`,
  `## Plan of Work`, `## Decision Log`, and `## Interfaces and
  Dependencies`.
- Track to review: {track_name}
- Codebase root: {codebase_path}
- Episodes from completed tracks: {prior_episodes}
- Previous findings: {previous_findings}

Start by reading the track file's `## Purpose / Big Picture`,
`## Context and Orientation`, `## Plan of Work`, `## Decision Log`,
and `## Interfaces and Dependencies` sections (plus any track-level
component diagram those sections carry). Read the relevant Decision
Records from the plan (and from the track file's `## Decision Log`
when Move 1 has inlined them). Then explore the parts of the codebase
this track touches.

**Tooling — PSI is required for symbol audits.** Reference-accuracy
questions about Java symbols in this codebase (callers/overrides/usages
of a method, field, class, or annotation; whether a slot has any
consumer; whether a reference is confined to one component; class
hierarchies) MUST be answered using mcp-steroid PSI find-usages, not
grep, when the mcp-steroid MCP server is reachable. Grep silently
misses polymorphic call sites, generic dispatch, identifiers inside
Javadoc/comments/string literals, and recently-renamed symbols —
exactly the cases where a Phase A "no callers" or "interface has these
implementers" claim ends up wrong. Use grep only for filename globs,
unique string literals, and orientation reads. If mcp-steroid is
unreachable in this session, fall back to grep and add an explicit
reference-accuracy caveat to any finding that depends on a symbol
search.

The reference-accuracy questions and grep-miss cases listed above are
**illustrative, not exhaustive**. The operative criterion is
reference accuracy — would a missed or spurious match make a Phase A
premise, edge-case trace, or integration finding wrong? When in
doubt, route through PSI. `CLAUDE.md` § MCP Steroid → "Grep vs PSI — when to switch" is the last
authoritative source for edge cases.

**How to invoke:**
- The MCP server is `mcp-steroid`. Its tools are deferred, so load their schemas via ToolSearch first.
- Call `steroid_list_projects` once at session start to confirm the IDE has the right project open and matches the working tree.
- Run PSI queries (find-usages, find-implementations, type-hierarchy) via `steroid_execute_code`, which evaluates a Kotlin snippet against the PSI tree — there is no dedicated `find_usages` tool.
- For Kotlin recipes, fetch the `coding-with-intellij-psi` skill via `steroid_fetch_resource`.

Use episodes from completed tracks to inform your review — they may
reveal codebase realities that the original plan didn't anticipate.

Review against these criteria:

COMPONENT MAP ACCURACY (for this track)
- Do the components referenced actually exist (or are clearly marked new)?
- Are the relationships (calls, depends-on, extends) accurate?
- Are there components this track misses that will be affected?

NAMED REFERENCES IN STEP FILE
- For every production class named in the track file's
  `## Purpose / Big Picture`, `## Context and Orientation`,
  `## Plan of Work`, `## Decision Log`, or `## Interfaces and
  Dependencies` sections, verify the name resolves via PSI find-class
  (`steroid_execute_code` with
  `JavaPsiFacade.findClass(fqn, GlobalSearchScope.allScope(project))`).
  Pattern-inducing class names from precedent (V1 → V2/V3) is a known
  trap: generic-extraction refactors often collapse version-suffixed
  classes into a single generic class. The v3 single- and multi-value
  B-tree engines, for instance, share one generic
  `com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree`
  rather than separate `CellBTreeSingleValueV3` / `CellBTreeMultiValueV2`
  classes.
- Each verified name produces a Premise certificate (see §Certificate
  requirements). A name that does NOT resolve is a `blocker` finding
  UNLESS the track's `## Purpose / Big Picture` / `## Context and
  Orientation` / `## Plan of Work` / `## Decision Log` / `## Interfaces
  and Dependencies` sections explicitly mark it as a class this track
  creates (e.g.,
  "we will introduce `Foo`"). In the
  planned-class case, log the Premise with verdict CONFIRMED and note
  "planned by this track" in `Detail`. Read the description carefully
  before flagging — references to existing code and references to
  code this track creates look identical at the name level.
- If mcp-steroid is unreachable, fall back to
  `find . -name '<ClassName>.java'`. If the result is unambiguous
  (single match whose package matches the reconstructed FQN), the
  Premise's `Verdict` is CONFIRMED — record the fallback tool in
  `Search performed` and add a reference-accuracy caveat to `Detail`.
  If the result is ambiguous (zero matches, multiple matches across
  different packages, or a package mismatch), the Premise's `Verdict`
  is NOT FOUND and the finding stays a `blocker` under the
  planned-class rule above.

DESIGN FEASIBILITY
- Does the described approach work given the current code structure?
- Are there APIs, interfaces, or contracts the track assumes but that don't
  exist or work differently?
- Are there simpler approaches the planning phase missed?
- Does anything learned from prior tracks invalidate this track's approach?

EDGE CASES & ERROR PATHS
- What happens on failure (exceptions, timeouts, partial state)?
- Does the track handle concurrent access where relevant?
- What happens during recovery (crash, restart) for durable state changes?

INTEGRATION POINTS
- Do documented integration points match actual code?
- Will changes break existing callers or consumers?

INVARIANT VALIDITY
- Are stated invariants enforceable given the codebase?
- Do prior track changes affect invariants assumed here?

BACKWARD COMPATIBILITY
- Will existing data/formats still work?
- Are migrations needed that the plan doesn't mention?

## Semi-Formal Reasoning Protocol
<!-- roles=reviewer-technical phases=3A summary="Every codebase claim needs a documented evidence certificate; no assertion without reading the actual code." -->

This review requires **structured evidence certificates** for every claim
about the codebase. You must not assert that an API exists, a component
works as described, or an approach is feasible without documented evidence
from reading the actual code. This prevents assumptions like "this
interface probably has that method" and catches subtle mismatches.

### Certificate requirements
<!-- roles=reviewer-technical phases=3A summary="Premise, edge-case, and integration certificate templates each track claim is verified against." -->

**For every component/API assumption verified**, produce:

```markdown
#### Premise: <what the track assumes>
- **Track claim**: <quote or paraphrase from the track description>
- **Search performed**: <PSI find-class / find-usages /
  find-implementations / type-hierarchy query when the IDE is
  reachable; Grep/Glob query otherwise. Record which tool was used so
  the certificate's reference-accuracy is auditable. For NAMED
  REFERENCES IN STEP FILE premises, this is `findClass` against the
  reconstructed FQN.>
- **Code location**: <file:line, or "NOT FOUND">
- **Actual behavior**: <what the code actually shows — copy relevant
  declaration, method signature, or excerpt>
- **Verdict**: CONFIRMED | WRONG | PARTIAL | NOT FOUND
- **Detail**: <if not CONFIRMED — what specifically differs>
```

**For every edge case / error path analyzed**, produce:

```markdown
#### Edge case: <scenario description>
- **Trigger**: <specific condition — e.g., "null index name", "concurrent
  WAL flush during histogram read">
- **Code path trace**:
  1. Entry: <method(args)> @ <file:line>
  2. <next call> @ <file:line> — <behavior with this input>
  3. ... (trace until outcome)
- **Outcome**: <what happens — exception type, partial state, silent
  corruption, correct handling>
- **Track coverage**: <does the track description address this? yes/no>
```

**For every integration point verified**, produce:

```markdown
#### Integration: <integration point name>
- **Plan claim**: <what the plan says about how new code connects>
- **Actual entry point**: <file:line of the real integration surface>
- **Caller analysis**: <who calls this today — list callers found via
  PSI find-usages when the IDE is reachable, otherwise via Grep with a
  reference-accuracy caveat. Polymorphic dispatch, generics, and
  Javadoc references are the common grep miss cases here.>
- **Breaking change risk**: <will the track's changes break existing callers?>
- **Verdict**: MATCHES | MISMATCHES | CALLERS AT RISK
```

### Rules for certificates
<!-- roles=reviewer-technical phases=3A summary="Search every premise, follow calls interprocedurally, trace edge cases to completion, document negative results." -->

- **Every premise requires a search.** Do not confirm an API exists
  because its name is plausible — and do not confirm a class exists
  because its name follows a sibling-version pattern (the V1 → V2/V3
  trap). Search and read the actual code. For Java symbols, use
  mcp-steroid PSI find-class (for NAMED REFERENCES existence checks),
  find-usages, or find-implementations when the IDE is reachable;
  only fall back to grep for filename globs, unique string literals,
  or when mcp-steroid is unreachable.
- **Follow calls interprocedurally.** When checking feasibility of an
  approach, trace the actual call chain. A method may delegate, throw,
  or behave differently than its name suggests.
- **Trace edge cases to completion.** Do not stop at "an exception is
  thrown." Trace what catches it, whether state is left inconsistent,
  and whether the track accounts for this.
- **Document negative results.** If a component is NOT FOUND or an API
  works differently than assumed, that is a finding.
- **Prior track episodes are evidence.** When a prior track's episode
  reveals a codebase reality (renamed class, changed API), use it as
  a premise and verify it still holds.

---

## Output Format
<!-- roles=reviewer-technical phases=3A summary="Two-part output: the evidence certificates first, then findings derived from them." -->

### Part 1: Evidence Certificates
<!-- roles=reviewer-technical phases=3A summary="The evidence base: all premise, edge-case, and integration certificates in review-criteria order." -->

Include all certificate entries (Premise, Edge case, Integration) in
order of review criteria. This is the evidence base.

### Part 2: Findings
<!-- roles=reviewer-technical phases=3A summary="Findings derived from non-confirmed certificates; each cites the certificate that produced it." -->

Derived from certificates. Each finding must reference the certificate
entry that produced it.

For each issue found, produce a finding:

```markdown
### Finding T<N> [blocker|should-fix|suggestion]
**Certificate**: <Premise/Edge case/Integration entry that produced this>
**Location**: <where in the track + relevant source file(s)>
**Issue**: <what's wrong, with evidence from the codebase>
**Proposed fix**: <concrete change — may include modifying steps,
  updating the track description, adding decision records, etc.>
```

Severity guide:
- blocker: Track will fail during execution (wrong API, missing component)
- should-fix: Track will produce fragile or incomplete results
- suggestion: Improvement based on codebase knowledge
- skip: Track is no longer needed (functionality already exists, prior track
  made it redundant, etc.). Recommend SKIP with rationale.
