# Conventions

Shared formats, rules, and glossary used by all phases of the workflow.

For execution-specific conventions (episodes, commit format, code review,
complexity tiers, decomposition rules), see
[`conventions-execution.md`](conventions-execution.md) — loaded only
during Phase 3 execution.

---

## 1.1 Glossary

| Term | Definition |
|---|---|
| **Track** | A coherent stream of related work within a plan. Contains steps. Max ~5-7 steps. If larger, split into dependent tracks during planning. |
| **Step** | A single atomic change = one commit. Fully tested. |
| **Episode** | Structured record of what happened during a step or track. |
| **Scope indicator** | Rough sketch of expected work in a track. |
| **Risk tag** | Per-step `low` / `medium` / `high` label assigned by the Phase A decomposer. Gates whether Phase B runs step-level dimensional review (`high` only) and signals focal points to Phase C track-level review (`medium` and `high`). Locked once the step is implemented. Criteria, override rules, and lifecycle live in `risk-tagging.md`; sub-step gating reads only the tag value, not the criteria. |
| **Research** | Phase 0 — interactive exploration before planning. The agent answers questions, explores code, and does internet research. Completes only when the user explicitly asks to create the plan. Same session as Phase 1. |
| **Session** | One invocation of `/execute-tracks`. Handles one sub-phase (A, B, or C) of one track. Sessions are separated by context clearing. Episodes bridge context across sessions. The only exception: the Track Pre-Flight gate + Phase A share a single session. |
| **Sub-agent** | A spawned agent for self-contained tasks — review (technical/risk/adversarial, dimensional code review, test quality review) where fresh perspective matters, or implementation (Phase B per-step implementer) where context absorption matters. The orchestrator retains session-level state. |
| **Orchestrator** | The session-level agent driving `/execute-tracks`. In Phase B owns sub-steps 4–7 of step implementation and all session-level decisions (cross-track impact, escalation, episode synthesis, context-level session-end gate). Distinct from the implementer. |
| **Implementer** | A fresh sub-agent spawned per step in Phase B that performs sub-steps 1–3 of step implementation (implement, test, commit) and returns a structured handoff to the orchestrator. See [`implementer-rules.md`](implementer-rules.md). |
| **Track file** | `plan/track-N.md` — the per-track ExecPlan working file. Created during Phase 1 alongside `implementation-plan.md` with the four Phase 1 track-level sections populated (`## Purpose / Big Picture`, `## Context and Orientation`, `## Plan of Work`, `## Interfaces and Dependencies`) plus any track-level Mermaid diagram; the remaining sections are filled by Phase A → C. See `conventions-execution.md` §2.1 *Track file content* for the full 14-section ExecPlan shape and the workflow-specific `## Base commit` sibling. Lives under `_workflow/plan/` (tracked on the branch for backup and team visibility, removed in Phase 4 cleanup before merge). |
| **Mid-phase handoff** | An on-disk file `_workflow/handoff-*.md` written when a session pauses with un-derivable mid-phase state (research notes, verbatim re-present text, partial reviews). Distinct from the implementer-return "handoff" — see [`mid-phase-handoff.md`](mid-phase-handoff.md) for the protocol. Resolved and deleted on resume; otherwise removed by the Phase 4 cleanup commit. |
| **Workflow-SHA stamp** | The HTML comment `<!-- workflow-sha: <40-char SHA> -->` written on line 1 of each `_workflow/**` artifact, recording the workflow-format commit reachable from HEAD at the moment of artifact creation. Drift detection and migration replay both read it; the H1 title starts on line 2. Full rule, canonical parser idioms, range definition, and unstamped-artifact protocol live in §1.6. |
| **Workflow drift** | A mismatch between the branch's `_workflow/**` artifact shape and the workflow format encoded in commits reachable from HEAD (section names, mandatory artifacts, step-file schema). Surfaces when workflow-format commits land on `develop` while a branch runs. Detected at session-start of `/create-plan` (D9) and in turn 1 of `/execute-tracks` by the gate at [`workflow-drift-check.md`](workflow-drift-check.md); the migration itself is owned by the `/migrate-workflow` skill. |

---

## 1.2 Plan File Structure

All workflow phases reference this structure.
`<dir-name>` is the plan directory name — provided explicitly by the user, or
defaulting to the current git branch name.

```
docs/adr/<dir-name>/
  ## Ephemeral working files (tracked under _workflow/ during the branch
  ## lifetime; removed in Phase 4 cleanup before merge)
  _workflow/
    implementation-plan.md        <- strategic: goals, architecture, tracks,
                                     track-level episodic summaries (thin
                                     checklist — per-track detailed content
                                     lives in plan/track-N.md, sectioned per
                                     conventions-execution.md §2.1)
    design.md                     <- narrative: concept-first Overview
                                     (first content), Core Concepts vocabulary
                                     primer (when doc has Parts or ≥3 new
                                     domain terms), class diagrams, workflow
                                     diagrams, per-section TL;DR + mechanism
                                     overview + edge cases + References
                                     footer. Every modification goes through
                                     the mutation action defined in
                                     design-document-rules.md § Mutation
                                     discipline. Frozen between Phase 1 end
                                     and Phase 4 start.
    design-mechanics.md           <- (optional, length-triggered) long-form
                                     derivations, file:line citations,
                                     edit-list subsections, full state-
                                     machine tables. Created when design.md
                                     exceeds ~2,000 lines / ~50K tokens.
                                     Section names match design.md so
                                     `**Full design**` refs in the plan
                                     resolve in either file.
    design-mutations.md           <- append-only log of every design.md
                                     mutation: diff summary, mechanical-check
                                     result, cold-read verdict, iteration
                                     count. Read by `edit-design`'s
                                     `design-sync` step to find the last
                                     sync point.
    plan/
      track-1.md                  <- per-track ExecPlan; 14 sections per
                                     conventions-execution.md §2.1
      track-2.md
      ...
    handoff-*.md                  <- (optional, transient) mid-phase handoff
                                     written when a session pauses with un-
                                     derivable in-flight state; deleted on
                                     resume, otherwise removed in the Phase 4
                                     cleanup commit. See
                                     `mid-phase-handoff.md` for the protocol.

  ## Final artifacts (committed in Phase 4 — the only files that survive
  ## merge into develop)
  design-final.md                 <- post-implementation design reflecting
                                     what was actually built; same shape as
                                     design.md (mutation discipline applies)
  adr.md                          <- architecture decision record with actual
                                     outcomes, aggregated from all episodes
```

The `_workflow/` subtree is **tracked** in git during the branch lifetime —
each session commits and pushes its workflow-file changes alongside its code
commits, so the branch on GitHub always reflects the latest progress (useful
for team visibility on a draft PR, and as a backup against local disk loss).
At Phase 4, after `design-final.md` and `adr.md` are committed, the entire
`_workflow/` directory is removed in a single cleanup commit so only the two
durable artifacts survive the squash-merge into `develop`. See
`workflow.md` § Final Artifacts for the cleanup procedure.

Every ephemeral `_workflow/**` artifact in the listing above (`implementation-plan.md`,
`design.md`, optional `design-mechanics.md`, and each `plan/track-*.md`)
carries a line-1 workflow-SHA stamp recording the workflow-format commit
reachable from HEAD at creation time. The stamp format, canonical parser
idioms, SHA computation rule, stamp range definition, unstamped-artifact
protocol, and the positive list of stamped artifact types (plus the Phase 4
final-artifact and `design-mutations.md` exclusions) live in [§1.6](#16-workflow-sha-stamps-on-_workflow-artifacts);
the §1.1 glossary row "Workflow-SHA stamp" gives the one-line definition.

The on-disk shape of `_workflow/**` may shift between sessions when
workflow-format commits land on `develop` while the branch runs. The
session-start gate at [`workflow-drift-check.md`](workflow-drift-check.md)
detects such drift at every `/create-plan` (D9) and `/execute-tracks`
startup and routes the user through the `/migrate-workflow` skill to
realign.

### Plan file content (`implementation-plan.md`)

```markdown
# <Feature Name>

## Design Document
[design.md](design.md)

## High-level plan

### Goals
<what this feature achieves and why>

### Constraints
<technical, performance, compatibility, or process constraints>

### Architecture Notes
<follow the Architecture Notes rules — see planning.md>

## Checklist
- [ ] Track 1: <title>
  > <intro paragraph — high-level context; detailed description in plan/track-1.md>
  > **Scope:** ~N steps covering X, Y, Z
- [ ] Track 2: <title>
  > <intro paragraph — high-level context; detailed description in plan/track-2.md>
  > **Scope:** ~N steps covering X, Y, Z
  > **Depends on:** Track 1 (when applicable)

## Plan Review
- [ ] Plan review (consistency + structural) — autonomous; runs as the first phase of `/execute-tracks`

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
```

The `## Plan Review` section is the State 0 marker the
`/execute-tracks` startup protocol reads (see
[`workflow.md`](workflow.md) §Startup Protocol). When the entry is
`[ ]` (or the section is missing entirely on a pre-existing plan),
`/execute-tracks` loads `implementation-review.md` and runs the
autonomous plan review before any track work begins. After the
review passes, the section is overwritten with the audit summary
(see [`implementation-review.md`](implementation-review.md) §Audit
trail for the format) and the entry becomes `[x]`. A user may
manually re-set the entry to `[ ]` (or invoke `/review-plan`) after
inline replanning to force a re-validation.

**Planning rule:** If a track would need more than ~5-7 steps or internal
phasing, split it into separate dependent tracks. Track sequencing and
episode propagation between dependent tracks is handled by the session
workflow — this gives the same "informed decomposition" benefit without
extra complexity.

### Section budgets

`implementation-plan.md` is loaded at every `/execute-tracks` startup,
so each section of the plan file obeys a length budget. Targets:
plan-file total ~1,500 lines / ~30K tokens; DR ≤ ~30 lines; invariant
≤ ~5; integration-point bullet ≤ ~3; component intent bullet ≤ ~5.
See [`planning.md`](planning.md) § Architecture Notes format for the
per-section budgets and rationale, and
[`structural-review.md`](structural-review.md) § Bloat checks for how
the structural review enforces them.

### Track file content (`plan/track-N.md`)

Created during Phase 1 alongside `implementation-plan.md` — one file per
planned track. The full file shape — the 12 OpenAI-style ExecPlan
sections (`## Purpose / Big Picture`, `## Progress`,
`## Surprises & Discoveries`, `## Decision Log`,
`## Outcomes & Retrospective`, `## Context and Orientation`,
`## Plan of Work`, `## Concrete Steps`, `## Validation and Acceptance`,
`## Idempotence and Recovery`, `## Artifacts and Notes`,
`## Interfaces and Dependencies`) plus the workflow-specific siblings
`## Episodes` and `## Base commit`, together with any optional
track-level Mermaid diagram — is defined in `conventions-execution.md`
§2.1 *Track file content*. That doc is also where Phase A → C
subsequent population is documented.

`/execute-tracks` startup loads only `implementation-plan.md` — track
files are read on demand: by Phase 2 reviews (which read the track files
for pending tracks) and by Phase A/B/C of the active track.

### Status markers

| Marker | Meaning | Used in |
|---|---|---|
| `[ ]` | Not started | Tracks, Phase 4 |
| `[>]` | In progress | Phase 4 |
| `[x]` | Completed | Tracks, Phase 4 |
| `[~]` | Skipped | Tracks only (recommended by track review or the orchestrator) |

### Scope indicators (required)

Every track must include a **Scope** line in its description block: a rough
sketch of the expected work — approximate step count and a brief list of
what they'd cover. Scope indicators are strategic signals, not tactical
commitments. Phase A always does full step decomposition at execution
time regardless.

Format: `> **Scope:** ~N steps covering X, Y, Z`

Scope indicators serve three purposes:
1. **Structural review** can catch sizing issues (a track claiming ~2 steps
   but describing 8 distinct changes) and ordering problems (scope of
   Track B implies a dependency on Track A's output).
2. **Human reviewers** can quickly gauge relative effort across tracks.
3. **Execution planning** — Phase A uses scope indicators as a starting
   point for just-in-time step decomposition, not as a binding contract.

**Rules:**
- The planner should focus energy on track descriptions, architecture notes,
  and inter-track dependencies — not premature step decomposition.
- Scope indicators are estimates. "~3-5 steps" is fine; exact counts are
  not required.
- The brief list (covering X, Y, Z) names the major pieces of work, not
  individual commits. Think "what" not "how."
- Do NOT include full step descriptions, `- [ ] Step:` items, or
  *(provisional)* markers. Steps are decomposed during Phase 3 execution.

---

## 1.3 Review Iteration Protocol

Shared by structural review, track pre-execution reviews, and track-level
code review. Severity levels: **blocker** / **should-fix** / **suggestion**
/ **skip** (track reviews only — recommends skipping the entire track).

**Full protocol** (iteration limits, finding ID prefixes, finding format,
gate verification output): [`review-iteration.md`](review-iteration.md) —
load when running a review loop.

---

## 1.4 Tooling discipline — prefer mcp-steroid PSI for Java symbol audits

The project's `CLAUDE.md` § MCP Steroid (sub-sections "Session-start
preflight" and "Grep vs PSI — when to switch") is the single
authoritative source for when to route through the IntelliJ IDE via
mcp-steroid versus when to fall back to `grep`/`rg`/Bash. This
subsection is a workflow-level reminder that those rules apply to
**every phase of the workflow** —
research, planning, Phase A review, Phase B implementation, Phase C
code review, Phase 4 final artifacts — and to **every sub-agent** the
workflow spawns.

### Session-start preflight

The SessionStart hook prints an `mcp-steroid: …` status line. Trust it
as the canonical IDE state for the session, do not re-probe, and act on
its three outcomes:

- **`mcp-steroid: reachable`** — call `steroid_list_projects` once
  before the first symbol-related action to confirm the open project
  matches the working tree, then route every reference-accuracy
  question about Java symbols through PSI for the rest of the session.
- **`mcp-steroid: NOT reachable`** — IDE control is unavailable. Symbol
  audits must use `grep`/`rg` and every audit conclusion that depends
  on a symbol search must explicitly note the reference-accuracy caveat
  ("grep-based; may miss polymorphic call sites / Javadoc / generics").
- **cwd mismatch** (`steroid_list_projects` reports a different open
  project than the working tree) — pause and ask the user to switch
  the open project before running any load-bearing symbol audit. Do
  not silently fall back to grep.

### When PSI is required (not optional)

When mcp-steroid is reachable, **load-bearing audits MUST use PSI** —
grep is not acceptable for the search that drives the action. A search
is load-bearing if a missed or spurious reference would corrupt the
result: deletions, renames, signature changes, "no production callers"
claims, "field is referenced only inside its declaring class" claims,
"this slot has no consumer" claims, etc. The cost of a missed
polymorphic call site is "tests pass at the deletion commit but
production breaks at runtime" — exactly the failure mode PSI exists to
prevent.

The examples listed above are **illustrative, not exhaustive**. The
operative test is the criterion ("would a missed or spurious reference
corrupt the result?"), not the example set. When a case isn't listed,
apply the criterion; when in doubt, treat the audit as load-bearing
and route through PSI. The project's `CLAUDE.md` § MCP Steroid
(sub-sections "Session-start preflight" and "Grep vs PSI — when to
switch") is the last authoritative source for edge cases.

This rule applies to design and research sessions too. Design
conclusions often hinge on reference-accuracy facts that grep can
silently miss — so research, Phase 1 planning, and Phase A track
reviews are not exempt.

### Sub-agent delegation

Delegating a symbol-usage question to a sub-agent (Explore, Phase A
review prompts, code review prompts, Phase 4 final-artifact prompts)
**does not bypass the PSI requirement.** Sub-agents default to
Bash/grep, so an unannotated delegation routes through grep regardless
of the question's shape. When passing a reference-accuracy question
to any sub-agent, the prompt MUST explicitly say *"use mcp-steroid PSI
find-usages, not grep, for these reference-accuracy questions"*. The
canonical review prompts under `prompts/` already embed this
instruction; custom delegations and on-the-fly prompts must do the
same.

### Other mcp-steroid routes (Maven, refactoring, multi-site edits)

The project's `CLAUDE.md` § MCP Steroid also covers when to route
Maven runs, Java refactors, and multi-site text edits through the
IDE. Three project-relevant defaults:

- **Single-test reruns** during step implementation (e.g. `-Dtest=Foo#bar`
  after a focused fix) and **compile-fix loops** benefit from
  `steroid_execute_code` — the IDE returns parsed test results and
  filtered compiler output. Full-suite runs, coverage profiles, JMH
  benchmarks, and integration-test suites stay on Bash `./mvnw` per the
  Maven-routing rule in the project's `CLAUDE.md` § MCP Steroid.
- **Renames, moves, signature changes, extract-method, pull-up/push-down,
  and any refactor that touches more than one reference site** route
  through the IDE refactoring engine via mcp-steroid, not raw `Edit`.
  Pure single-file edits and changes that don't move references stay on
  `Edit`. After an IDE refactor, run Spotless on the affected modules
  and re-run the relevant tests — the engine doesn't enforce project
  formatting.
- **Multi-site / multi-file literal-text edits that don't need symbol
  resolution** (e.g. updating a recurring string literal, switching a
  Javadoc tag across many files, fixing the same boilerplate at several
  call sites) route through the dedicated **`steroid_apply_patch`** tool
  rather than 2+ chained native `Edit` calls. The native tool bypasses
  IntelliJ — VFS, PSI, and search indices go stale until something
  forces a refresh; `steroid_apply_patch` is atomic, all-or-nothing
  pre-flight, and fits the ~60 s MCP timeout even on large patches. Use
  the dedicated tool, not `steroid_execute_code`'s `applyPatch { }`
  DSL — the latter requires kotlinc compile and risks blowing the
  timeout. Single-site edits stay on `Edit`.

The three examples above are **illustrative, not exhaustive** — they
name the project-relevant defaults, not the full set of cases where
mcp-steroid is the right route. The full Maven, refactoring, and
multi-site-edit routing tables live in the project's `CLAUDE.md`
§ MCP Steroid (sub-sections "Maven — when to route through
mcp-steroid", "Refactoring — IDE refactor vs raw Edit", and the
`apply-patch-tool-description` load-on-demand entry); when a
situation isn't covered here, that file is the last authoritative
source.

All three routes require the same `steroid_list_projects` preflight as
PSI audits.

### Recipes — load on demand for specific operations

The project's `CLAUDE.md` § MCP Steroid → Recipes lists the catalogue
of pre-built scripts that automate common IDE-routed operations
through `steroid_execute_code`; the full trigger list and use cases
live in `.claude/docs/mcp-steroid/recipes.md`. Fetch the named
`mcp-steroid://` resource via `steroid_fetch_resource` and adapt the
script template — do not rederive the IntelliJ API calls from memory.

Recipes most relevant to the workflow:

| Workflow situation | Recipe(s) to load |
|---|---|
| About to delete a method / field / class — confirm no remaining production callers (`mode=FIX_REVIEW_FINDINGS` cleanup, deprecated-API removal, internal-helper pruning) | `mcp-steroid://ide/safe-delete` |
| Phase A track review needs the full implementer / override map of an SPI before a contract change | `mcp-steroid://ide/hierarchy-search` |
| Phase A track review or inline replanning needs the upward-caller tree for a low-level signature, not just immediate callers | `mcp-steroid://ide/call-hierarchy` |
| IDE-routed Maven test failed — read structured per-test details / statistics instead of parsing surefire XML | `mcp-steroid://test/failure-details`, `mcp-steroid://test/statistics`, `mcp-steroid://test/find-recent-test-run` |
| Phase C pre-PR pass — surface semantic issues Spotless and the coverage gate won't catch (redundant casts, atomic-on-volatile, format-string mismatches) on the cumulative track diff | `mcp-steroid://ide/inspect-and-fix`, `mcp-steroid://lsp/code-action` |
| Module-graph question during research / planning ("does X depend on Y?", "what depends on lucene?") | `mcp-steroid://ide/project-dependencies` |
| Class-shape refactor — extract interface, pull-up / push-down members, formalize an SPI contract | `mcp-steroid://ide/extract-interface`, `mcp-steroid://ide/pull-up-members`, `mcp-steroid://ide/push-down-members` |
| Add / remove / reorder a parameter on a method with many overrides | `mcp-steroid://ide/change-signature` |
| Implementer hits an opaque test failure (concurrency hang, mid-operation state corruption) and the stack trace doesn't explain it | `mcp-steroid://debugger/overview` (then per-step recipes — `add-breakpoint`, `debug-run-configuration`, `wait-for-suspend`, `evaluate-expression`, `step-over`) |

The recipe table is **illustrative, not exhaustive** — load only the
recipe(s) the current step / iteration needs, never pre-load the
whole catalogue. `.claude/docs/mcp-steroid/recipes.md` is the last
authoritative source for the trigger list and use cases.

---

## 1.5 Writing style for Markdown and prose artifacts

Every prose surface in this repo follows the rules in
[`.claude/output-styles/house-style.md`](../output-styles/house-style.md).
That file is the single declarative source: BLUF lead, banned
vocabulary, banned sentence patterns, banned analysis patterns,
punctuation and typography, structural rules, and document-shape rules
for design / ADR artifacts. Every cross-reference from a workflow
prompt, review agent, implementer file, or orchestrator file resolves
to that file by repo-relative path.

Rules apply at two tiers. Markdown gets the full rule set; Java and
Kotlin source gets the AI-tell subset that applies at code-comment
scale. Other extensions stay silent.

| Surface | Tier | Sections that apply |
|---|---|---|
| All `*.md` files (design docs, ADRs, plans, track files, reviews, issue and PR bodies, status updates) | Full house-style | Every section of `house-style.md` |
| PR titles and descriptions, commit message bodies, YouTrack issue bodies | Full house-style | Every section of `house-style.md` |
| `*.java`, `*.kt` source (code comments, Javadoc rationale) | AI-tell subset | `§ Banned vocabulary`, `§ Banned sentence patterns`, `§ Banned analysis patterns`, `§ Em-dash discipline` (H3 nested under `§ Punctuation and typography`) |
| Other extensions | Silent | n/a |

The four Tier-B section names are stable headings after YTDB-836; a
future rename in `house-style.md` requires updating every pointer in
the same commit. Run `grep -rn 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md` to enumerate pointer sites before renaming.

---

## 1.6 Workflow-SHA stamps on `_workflow/**` artifacts

Every ephemeral `_workflow/**` artifact carries a line-1
`<!-- workflow-sha: <40-char SHA> -->` stamp. The stamp records the
workflow-format commit the artifact was written against; the drift
check and the per-branch migration both resolve their reference point
from this stamp. This section is the single source of truth for the
format, the canonical parser idioms, the SHA computation rule at
creation, the stamp range, the unstamped-artifact protocol, the
non-rule against silent auto-computed defaults, the positive
enumeration of stamped artifact types, the active-plan scope, and the
shared Phase 1 walk bash block. Every downstream writer (skill
template, drift check, migration, validator) resolves to this section
rather than restating the rule locally.

### (a) Format definition and writer-side position contract

The stamp is `<!-- workflow-sha: <40-char SHA> -->` on line 1; the H1
title is on line 2. Any tool or skill that edits a stamped artifact
MUST preserve the stamp's line-1 position. `edit-design` mutations
(`section-add`, `section-move`, `structural-rewrite`, `content-edit`,
and every other shape) treat line 1 as immutable. The
position-preservation rule is the runtime complement to I4 (the
structural invariant that every stamped artifact begins with the stamp
at line 1); together they keep the stamp parseable by the canonical
regex in (a1) after any mutation sequence.

### (a1) Canonical parser idioms

Two regex forms, both quoted byte-for-byte by Tracks 3, 4a, and 4b:

- Value extraction (drift check, range derivation):
  ```bash
  head -1 file.md | grep -oE 'workflow-sha: [0-9a-f]{40}' | grep -oE '[0-9a-f]{40}$'
  ```
  The `workflow-sha:` anchor rejects 40-hex sequences in H1 titles
  like `# Backport of <SHA>: <description>` (verified by reproduction
  during Phase A adversarial review).
- Presence check (lockstep advance, validators):
  ```bash
  head -1 file.md | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'
  ```

Explicitly NOT canonical: `head -1 | grep -oE '[0-9a-f]{40}'` without
the `workflow-sha:` anchor returns false positives on any H1 that
happens to contain a 40-hex run.

### (b) SHA computation at artifact-creation time

Writers compute the stamp value from a path-scoped log over the
workflow tooling itself:

```bash
WORKFLOW_SHA="$(git log -1 --format=%H HEAD -- .claude/workflow .claude/skills)"
[ -z "$WORKFLOW_SHA" ] && WORKFLOW_SHA="$(git rev-parse HEAD)"
```

When the path-scoped log returns the empty string (no commit in HEAD's
ancestry has touched `.claude/workflow/` or `.claude/skills/`), the
writer falls back to `git rev-parse HEAD`. Every downstream writer
(create-plan, edit-design phase1-creation, edit-design length-trigger,
migration final-batch) copies the paired idiom above verbatim so the
fallback behaves identically across writer sites. In every repo where
workflow tooling exists in any reachable commit, the fallback never
fires; documenting it keeps the rule portable to fresh repos and to
repos where workflow paths have been moved. Both commands returning
empty is a precondition violation (a repo with no HEAD commits) — the
skill halts rather than writes a malformed stamp.

### (c) Stamp range definition

The drift / migration range is `BASE_SHA..HEAD`, where `BASE_SHA` is
the oldest stamp reachable from HEAD via a pairwise `git merge-base`
fold across the set of stamps gathered by the Phase 1 walk in (h).
HEAD is the upper bound because the branch is a self-contained capsule
(workflow-format commits enter only via explicit rebase or merge of
`develop`; see plan D10).

A fatal `git merge-base` failure (a stamp pointing at a `git
gc`-pruned commit, or two stamps with no reachable common ancestor in
the local repo) routes to the unstamped-artifact bootstrap prompt in
(d). The pairwise fold may surface multiple failures across one
session — each failing `git merge-base` call contributes its
participating stamps to the affected set. Every stamp in that set is
treated as unstamped for the rest of the session, and the bootstrap
prompt in (d) covers the full set in a single user prompt (matching
the batch semantics for genuinely-unstamped artifacts). The recovery
path keeps the gate usable when stamps drift onto commits the local
repo no longer has.

### (d) Unstamped-artifact protocol

When any artifact in the active plan's `_workflow/` is unstamped, the
drift check signals drift unconditionally (no fold, no range
computation). The migration prompts the user once for a base SHA
covering the unstamped set and validates the input:

```bash
git rev-parse --verify "$SHA^{commit}" && git merge-base --is-ancestor "$SHA" HEAD
```

The `^{commit}` peel rejects tag and ref names; only commit SHAs pass.
Short prefixes are accepted as input, but the writer canonicalizes the
value to the 40-char form via the `rev-parse` stdout before storing it
in any stamp file (the stamp regex `[0-9a-f]{40}` rejects shorter
values on subsequent parse).

On validation failure (either subcommand returns non-zero), the
migration re-prompts the user with the failure cause. After three
failed attempts the migration halts with an error and the user
`/clear`s the session to abandon the migration. The bounded retry
count keeps a typo recoverable without trapping the session in an
unbounded loop.

The prompt records the user's best guess; the per-commit replay loop's
halt-on-ambiguity is a partial safety net, not a guarantee. A too-old
SHA silently bloats the replay range; a too-new SHA silently skips
needed migrations. Both failure modes are documented here so debug
sessions have a starting point.

### (e) Non-rule: no silent auto-computed default

No auto-computed reference (fork-point with `develop`, HEAD itself,
`git merge-base origin/develop HEAD`, or any other variant) is a
silent default for unstamped artifacts. Under rebase any such
reference shifts forward, marking unstamped artifacts as
already-current and silently skipping the migration; the gate's "no
drift" report would then mask data loss the user cannot detect. The
rationale lives in plan D8; the rule is restated here because the
non-rule is as load-bearing as the positive rules above.

### (f) Stamped artifact types and exclusions

Positive enumeration of stamped artifacts:

- `_workflow/implementation-plan.md`
- `_workflow/design.md`
- `_workflow/design-mechanics.md` (when the length trigger has fired and the file exists)
- Every `_workflow/plan/track-*.md`

Explicitly NOT stamped:

- Phase 4 final artifacts (`design-final.md`, `design-mechanics-final.md`, `adr.md`). These survive
  the merge into `develop` where per-branch migration never applies,
  so a stamp would be both stale on first commit and meaningless once
  the branch is squashed.
- `_workflow/design-mutations.md`. The file is an append-only log
  whose stamp would always equal `design.md`'s; the append-only
  contract makes the file replay-immune by construction.

### (g) Active-plan scope

Both the drift check and the migration operate on the plan dir the
caller resolved at startup: `/create-plan <dir>`, `/execute-tracks
<dir>`, or `/migrate-workflow`'s zero / one / many ladder over
`docs/adr/*/_workflow/`. See §1.2 *Plan File Structure* (the
`<dir-name>` default to current git branch name) for the resolution
rule.

Cross-plan folding is out of scope (plan D13): each plan migrates
independently, and folding across plans would over-include older
commits the active plan was always synced past. This scope rests on
the one-plan-per-branch project convention, not a correctness
invariant; a future change allowing multiple plans per branch would
force a rethink of this section.

### (h) Phase 1 walk bash block

The shared enumerate-and-classify block is the durable single source
for the Phase 1 walk. Tracks 3 and 4a copy this block byte-for-byte so
the coordinated-edit cost on a future format change stays bounded to
the writer sites enumerated in `## Interfaces and Dependencies` of
each track. The block survives Phase 4 (`design.md` is removed in the
cleanup commit; `conventions.md` is not):

```bash
PLAN_DIR="docs/adr/<resolved-dir-name>"
STAMPED_SHAS=""
UNSTAMPED_FILES=""
# design-mechanics.md is optional; absent until the length trigger fires.
# The ls 2>/dev/null swallows the stderr for any artifact kind that is not
# yet present on disk, so missing files do not abort the walk.
for f in $(ls "$PLAN_DIR/_workflow/implementation-plan.md" \
              "$PLAN_DIR/_workflow/design.md" \
              "$PLAN_DIR/_workflow/design-mechanics.md" \
              "$PLAN_DIR/_workflow/plan/"track-*.md 2>/dev/null); do
    SHA="$(head -1 "$f" | grep -oE 'workflow-sha: [0-9a-f]{40}' | grep -oE '[0-9a-f]{40}$')"
    if [ -n "$SHA" ]; then
        STAMPED_SHAS="$STAMPED_SHAS $SHA"
    else
        UNSTAMPED_FILES="$UNSTAMPED_FILES $f"
    fi
done
```

The `<resolved-dir-name>` placeholder is the active plan dir resolved
per (g) above. Downstream writers replace it at invocation time; the
literal form lands here so the block stays copy-paste-ready.

When both `STAMPED_SHAS` and `UNSTAMPED_FILES` are empty after the
walk, the active plan has no stampable artifacts on disk (for example,
a freshly-created `_workflow/` directory that holds only a transient
`handoff-*.md`). Callers treat this as a no-op: the drift check exits
successfully with no drift to report, and the migration skill refuses
with `no artifacts to migrate`. The empty-input case is distinct from
the all-stamped case (`STAMPED_SHAS` non-empty, `UNSTAMPED_FILES`
empty) and from the partially-stamped case (`UNSTAMPED_FILES`
non-empty, which routes to the bootstrap prompt in (d) regardless of
`STAMPED_SHAS` content).

The walk's glob set deliberately omits `$PLAN_DIR/_workflow/staged-workflow/`
on workflow-modifying plans (see §1.7 below). The staged subtree mirrors
live `.claude/workflow/**` and `.claude/skills/**` paths under a
plan-scoped prefix; those files are not `_workflow/**` artifacts in the
stamping sense and do not carry workflow-SHA stamps. Adding the staged
prefix to the walk would produce spurious `UNSTAMPED_FILES` entries on
every workflow-modifying plan and route them through the bootstrap
prompt in (d), which is the wrong recovery path for an authoring buffer
that gets promoted into the live tree at Phase 4.

## 1.7 Staging for workflow-modifying branches

Workflow-modifying branches accumulate every edit to `.claude/workflow/**`
and `.claude/skills/**` under a plan-scoped staging subtree during
execution, and a single Phase 4 promotion commit copies the staged
content into the live paths just before final artifacts land. The live
workflow files in the branch's checkout stay at develop's state for the
duration of Phase B and Phase C, so plan citations resolve against a
stable surface, reviewers see one consistent rule body, and the drift
gate at the next session start does not flag the branch's own authoring
as drift on itself.

This section is the single source of truth for the staged-subtree path
layout, the canonical workflow-modifying marker spelling, the detection
rule split between the implementer enforcement gate and the Phase 4
promotion guard, the implementer write-routing rule, the reads-precedence
rule, the copy-then-edit-on-first-touch rule, the rebase-precedes-promotion
rule, and the precise wording of the staging invariant. Every downstream
writer resolves to this section rather than restating any clause
locally: the `implementer-rules.md` path-mapping rule, the
`step-implementation.md` prompt-template reference, `workflow.md`
§ Final Artifacts, the Phase 4 prompt at `prompts/create-final-design.md`,
the `workflow-drift-check.md` pathspec defensive comment, and the
`mid-phase-handoff.md` § Resume protocol acknowledgment.

### (a) Staged-subtree path layout

The staged subtree lives under the active plan's `_workflow/` tree at a
fixed two-level prefix:

```
docs/adr/<plan-dir>/_workflow/staged-workflow/.claude/workflow/...
docs/adr/<plan-dir>/_workflow/staged-workflow/.claude/skills/...
```

Each staged file mirrors its live counterpart's relative path under the
`.claude/` prefix byte-for-byte. A staged copy of
`.claude/workflow/implementer-rules.md` lives at
`docs/adr/<plan-dir>/_workflow/staged-workflow/.claude/workflow/implementer-rules.md`;
a staged copy of `.claude/skills/edit-design/SKILL.md` lives at
`docs/adr/<plan-dir>/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md`.
No other prefixes participate: workflow files outside `.claude/workflow/`
and `.claude/skills/` are not stageable under this convention; the
staging path layout does not extend to files this convention does not
govern.

The `<plan-dir>` placeholder is the active plan's directory resolved
per §1.6 (g) *Active-plan scope*. The staged subtree is plain filesystem
state under the existing `_workflow/` tree, which the Phase 4 cleanup
commit removes in one sweep alongside the rest of `_workflow/`; the
post-merge `develop` history carries only the live promoted files plus
`design-final.md` and `adr.md`.

### (b) Canonical workflow-modifying marker

A plan declares itself workflow-modifying through a fixed sentence in
the `### Constraints` section of `implementation-plan.md`:

```
This plan is workflow-modifying: it edits .claude/workflow/** or .claude/skills/**.
```

The sentence appears as a standalone bullet or paragraph inside
`### Constraints`. The exact wording is the single string every
downstream consumer matches against — `implementer-rules.md`'s
enforcement gate, the Phase 4 prompt, and any future reviewer
checklist all key off this literal sentence rather than re-deriving
the predicate from the plan's body. Two planners writing the same
sentence two different ways is the failure mode this canonical spelling
prevents.

Plans that touch `.claude/workflow/**` or `.claude/skills/**` but
omit the marker forfeit the staging mechanism: the implementer
enforcement gate stays inactive, writes land on live paths, and the
drift gate flags the branch's own authoring. Planners who recognise
their plan as workflow-modifying are responsible for adding the marker
before Phase A review; reviewers verify the marker's presence on any
plan whose Plan-of-Work references `.claude/workflow/**` or
`.claude/skills/**` paths.

### (c) Detection rule — two signals, two consumers

The convention runs on two independent detection signals, each serving
a distinct consumer:

- **Constraints declaration** drives the implementer enforcement gate.
  Per-spawn, the implementer reads `implementation-plan.md`'s
  `### Constraints` section and checks for the marker sentence in
  (b). When the marker is present, the path-mapping rule in (e) and
  the pre-commit gate in `implementer-rules.md` activate; when the
  marker is absent, the implementer writes to live paths normally.
  This signal works from the very first step of execution, before any
  staged content exists.
- **`<plan-dir>/_workflow/staged-workflow/` directory presence** drives
  the Phase 4 promotion guard. The Phase 4 prompt at
  `prompts/create-final-design.md` checks for the directory and skips
  the promotion commit when it is absent. This signal answers the
  operationally distinct question "is there staged content to promote
  into the live tree" rather than "is the implementer enforcement
  gate active."

The two signals serve distinct purposes by construction. Constraints
declaring workflow-modifying without any staged content is the legitimate
shape of a dry-run plan or an abandoned-work plan: the gate stays
active throughout execution; Phase 4 produces a no-op promotion that
the directory-presence guard skips silently; Phase 4's two-commit shape
(final-artifacts + cleanup) remains intact. The inverse case, staged
content without a Constraints declaration, is unreachable when the
implementer enforcement gate works as designed, because an undeclared
plan has no gate to route writes into the staged subtree.

### (d) Reads precedence — staged copy authoritative when present

During execution of a workflow-modifying plan, the implementer's read
side resolves every `.claude/workflow/**` and `.claude/skills/**` path
through a staging-aware check:

```
if a staged copy exists, read staged; else read live.
```

The check fires on every read. For a file with no staged copy, the
implementer reads the live path unchanged. For a file the current plan
or a prior step in the same track has already staged, the implementer
reads the staged copy, so a step that authors rule X in
`staged-workflow/.claude/workflow/conventions.md` is visible to every
subsequent step that cites or extends rule X in the same plan.

The reads-precedence rule resolves the multi-step authoring case that
a "reads always hit live" rule would break: step N adds rule X to
staged `conventions.md`; step N+M cites rule X. Without staged-first
reads, step N+M either reads stale content before its own write or
cannot cite the rule it just authored. The chosen rule keeps step N+M
working against current state.

Consumers outside the implementer keep reading live paths unchanged:
the drift gate, the plan-slim renderer, sibling-track plan citations,
and reviewers loading a workflow file from the worktree. None of those
consumers has a staged copy to read; the precedence rule applies to
the implementer's per-spawn read site only.

### (e) Write routing and copy-then-edit on first touch

When the workflow-modifying marker is present, the implementer routes
every write whose target path begins with `.claude/workflow/` or
`.claude/skills/` to the corresponding staged path under
`<plan-dir>/_workflow/staged-workflow/`. The routing covers every
write surface (`Edit`, `Write`, `steroid_apply_patch`,
`steroid_execute_code` file writes, and `Bash` redirections) by
rewriting the target path before the tool call lands.

**Copy-then-edit on first touch.** When the implementer's first write
to a given live path under `.claude/workflow/**` or `.claude/skills/**`
finds no staged copy at the mapped staged path, the implementer first
copies the live file to the staged path verbatim, then applies the
edit to the staged copy. Subsequent writes to the same file in the
same plan edit the staged copy directly. The copy-then-edit step
preserves develop's state as the staged copy's baseline so the eventual
Phase 4 `cp -r` overwrites the live tree with a coherent target rather
than an empty-file shape that loses every part of the original the
plan did not explicitly rewrite.

The pre-commit gate in `implementer-rules.md` enforces the write
routing at commit time: any commit on a workflow-modifying plan whose
staged diff contains live `.claude/workflow/**` or `.claude/skills/**`
matches outside the Phase 4 promotion commit is refused. Authoring
discipline at write time keeps the gate quiet; the gate exists to
catch the bypass shapes (an absolute live path passed to a tool, a
`Bash` redirection that the implementer forgot to rewrite).

### (f) Rebase-precedes-promotion

A workflow-modifying branch that has not rebased onto the current
`develop` HEAD must do so before Phase 4 promotion runs. The Phase 4
prompt's pre-promotion divergence sanity check computes
`$(git merge-base origin/develop HEAD)..origin/develop` on the live
`.claude/workflow` and `.claude/skills` paths after a `git fetch origin
develop`; a non-empty diff halts with a manual-reconciliation
instruction. The rebase is the manual-reconciliation path: rebasing
onto current `origin/develop` brings the branch's working tree up to a
state where `origin/develop` no longer carries workflow commits the
branch has not absorbed, so the only live workflow content remaining
for Phase 4 to write is the staged subtree's authoring against that
current base.

Promotion before rebase risks `cp -r` overwriting a live file that
moved forward on `develop` after the staging copy was taken, silently
losing the develop-side change. The pre-promotion check shifts the
recovery from a post-promotion forensic diff to a pre-promotion halt
that the branch resolves by rebasing.

### (g) The I6 invariant

The staging convention rests on one invariant that holds across every
workflow-modifying plan from the first staged write to Phase 4
promotion:

> Promotion at Phase 4 is the only intra-branch authoring transition;
> rebase-merge from develop excluded by scope.

The wording is precise. "Intra-branch authoring transition" names the
single moment at which the branch's own authoring of `.claude/workflow/**`
or `.claude/skills/**` content moves from the staged subtree into the
live tree. Phase 4's promotion commit is that moment; no other commit
on the branch carries live-path writes when the convention is
followed. "Rebase-merge from develop excluded by scope" carves out
the rebase case: when the branch rebases onto current `develop`,
`develop`'s commits enter the branch carrying live-path changes those
commits already authored, which is not the branch's own authoring and
falls outside this invariant's scope. The drift-check pathspec exists
specifically to handle the rebase case; this invariant governs the
staging case.

### (h) Forward-applicable to future workflow-modifying branches

The convention applies forward only. The plan that introduces this
section is itself workflow-modifying but does not stage its own edits,
because no prior version of the convention existed during the
execution of its earlier work; every earlier track of that plan wrote
to live `.claude/workflow/**` paths under the existing in-place model,
and the track that lands this section plus the supporting rules does
so through the same in-place model. The first workflow-modifying
branch that opens a plan after this section reaches `develop` is the
first branch that exercises the staging path end-to-end.

The forward-applicable carve-out is a one-time observation about the
plan that introduces the convention. It is not a permanent rule; every
workflow-modifying branch that opens after this section lands is bound
by the full convention.

### (i) Worked example — on-disk shape

A workflow-modifying plan extending `.claude/workflow/X.md` ends Phase B
with the following shape on disk (other artifacts elided):

```
.claude/
  workflow/
    X.md                          # develop's state, unchanged
docs/
  adr/
    <plan-dir>/
      _workflow/
        implementation-plan.md    # Constraints declares workflow-modifying
        plan/
          track-1.md
        staged-workflow/
          .claude/
            workflow/
              X.md                # branch's authored content
```

The live `.claude/workflow/X.md` stays at develop's state for the
duration of Phase B and Phase C; every read of `X.md` from the
implementer hits the staged copy per (d); every write to `X.md`
routes to the staged copy per (e). At Phase 4 promotion, the
promote-staged-workflow commit copies
`staged-workflow/.claude/workflow/X.md` over the live
`.claude/workflow/X.md`; the subsequent cleanup commit removes the
entire `_workflow/` tree (the staged subtree included). After merge
to `develop`, the history carries the live promoted `X.md` plus the
durable artifacts (`design-final.md`, `adr.md`) and nothing else from
this branch's staging machinery.

### (j) Aborted-promotion resume semantics

Phase 4 on a workflow-modifying plan opens a pause window between the
promote-staged-workflow commit (Step 4 of
`prompts/create-final-design.md`) and the subsequent final-artifacts
commit (Step 5). If the session ends inside that window — manual
interruption, context exhaustion, host loss — the next session
re-enters Phase 4 from the top.

On re-entry, the staged subtree is still on disk (cleanup runs in
Step 6, not Step 4), so the `[ -d "$STAGED_DIR/.claude" ]` guard
evaluates true and the bash re-enters the guarded block. The
corrected divergence check per (f) compares against `origin/develop`
forward of the merge-base, which is neutral on the branch's own
promote commit; the check passes without false-positive on a clean
resume. The `cp -r` re-copies identical content over the already-
promoted live tree; `git add` produces an empty index for the
already-committed paths; the `git diff --cached --quiet || git
commit` short-circuit in the Step 4 bash means the no-op resume
produces no second promote commit, and `git push` is a no-op when
nothing changed locally.

For plans without the staged subtree, the directory-presence guard
evaluates false and the existing State D resume from `workflow.md`
§ *Startup Protocol* covers the path; this sub-anchor governs
workflow-modifying plans only.
