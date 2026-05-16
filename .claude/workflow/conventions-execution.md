# Conventions — Execution (Phase 3)

Execution-specific formats and rules. Loaded only during Phase 3
(`/execute-tracks`). For core conventions shared across all phases, see
[`conventions.md`](conventions.md).

---

## 2.1 Plan File — Execution Additions

These subsections extend the plan file structure defined in
`conventions.md` §1.2.

The phase-specific plan-entry mutations (collapse-on-completion and
strategy-refresh-line append) live with their owning phase
documents — they are not loaded by every Phase 3 session:

- **Track-completion collapse** (Phase C): see
  [`track-code-review.md`](track-code-review.md) § Track Completion
  step 4 — the "Always keep" / "Always drop" rule, the track-episode
  fields, and the final on-disk form.
- **Strategy refresh line** (State A — written by the Track Pre-Flight
  gate when Panel 1 is active): see
  [`track-review.md`](track-review.md) § Track Pre-Flight step 6
  (Persist amendments + strategy-refresh line) — the line format for
  CONTINUE / ADJUST and the `[~]`-track variant.

### Session state detection

Moved to `workflow.md` §Startup Protocol — the only place where state
detection is used.

### Track file content (`plan/track-N.md`)

Each track file uses the 12-section OpenAI ExecPlan template (verbatim
section names and order) plus two workflow-specific siblings:
`## Episodes` (per-step blocks) and `## Base commit` (Phase B → C
housekeeping). 14 sections total. The continuous-log sections — Progress,
Surprises & Discoveries, Decision Log, Outcomes & Retrospective — come
first so a restart-from-cold reader sees current state before static
plan; Episodes sits adjacent to its plan-at-start partner (Concrete
Steps) so per-step roster and per-step result are physically co-located.

**Authoritative template:** the verbatim Markdown `/create-plan` writes
at Phase 1 lives in `design.md` §"New per-track file shape". The block
below is a section-by-section summary plus one realistic example body
per section; consult the design doc for the full template with all
placeholder comments. Each section's writer/reader split across phases
is tabulated in the *Section lifecycle* subsection below.

#### Sections in order

1. **`## Purpose / Big Picture`** — Phase 1 writes a one-line BLUF
   stating the user-visible behavior gained after this track lands,
   followed by the intro paragraph from the plan checklist entry. The
   slot under the BLUF is reserved for Move 2's ADDED/MODIFIED/REMOVED
   triad (empty `<!-- Reserved -->` placeholder until that Move lands).

2. **`## Progress`** — continuous-log of phase transitions. Highest
   cadence of all continuous-log sections (per phase event + per step +
   per review iteration + track completion). Every entry carries a
   mandatory `[ctx=<level>]` field per D12 (orchestrator reads
   `/tmp/claude-code-context-usage-$PPID.txt` immediately before each
   write; `unknown` if the file is missing).

   ```markdown
   ## Progress
   - [x] 2026-05-15T11:50Z [ctx=safe] Review + decomposition done
   - [x] 2026-05-15T12:30Z [ctx=safe] Step 3 complete (commit abc123)
   - [ ] 2026-05-15T12:45Z [ctx=info] Step 4 in progress
   ```

3. **`## Surprises & Discoveries`** — continuous-log of cross-cutting
   findings promoted by the orchestrator from per-step episodes when
   the finding affects future steps or other tracks. Empty at Phase 1.

4. **`## Decision Log`** — continuous-log of execution-time decisions
   (inline-replan choices, scope-downs, dependency reveals, gate
   overrides). Move 1 (YTDB-814) will later land per-track inlined
   Decision Records here. Slot below the running log is reserved for
   Move 1 (empty placeholder until that Move lands).

5. **`## Outcomes & Retrospective`** — continuous-log absorbing today's
   `## Reviews completed`. Each Phase A/B/C review iteration appends one
   timestamped entry; Phase C track completion appends the final summary.

6. **`## Context and Orientation`** — plan-at-start. Phase 1 writes
   what state the codebase is in at the start of this track (files,
   modules, non-obvious terminology). Folds in today's `**What**:`
   subsection.

7. **`## Plan of Work`** — plan-at-start. Phase 1 writes the prose
   sequence of edits and additions (folds in today's `**How**:`
   subsection); Phase A appends a per-step sequencing summary that
   references the Concrete Steps roster below.

8. **`## Concrete Steps`** — plan-at-start. Phase A decomposition
   writes a thin numbered roster: one entry per step with description,
   `risk:` tag, and a status checkbox. **No nested blockquote** — per
   D9 the per-step episode lives in `## Episodes` below, not here. The
   roster is immutable after Phase A except for the status checkbox
   flip (`[ ]` → `[x]` / `[!]`) and the optional `commit: <SHA>`
   annotation Phase B appends.

   ```markdown
   ## Concrete Steps
   1. <Step description> — `risk: low`  [ ]
   2. <Step description> — `risk: high`  [ ]
   3. <Step description> — `risk: medium`  [ ]
   ```

9. **`## Episodes`** — continuous-log, workflow-specific sibling (D11).
   Phase B sub-step 7 appends one block per completed step, identified
   by step number + commit SHA. Never re-edited. The block header
   carries the mandatory `[ctx=<level>]` field per D12. Fields:
   What was done / What was discovered / What changed from the plan /
   Key files / Critical context.

   ```markdown
   ## Episodes

   ### Step 3 — commit abc1234, 2026-05-15T12:30Z [ctx=safe]
   **What was done:** Renamed the `_workflow/tracks/` directory to
   `_workflow/plan/` and swept ~85 references across `.claude/workflow/`
   and `.claude/skills/`.

   **What was discovered:** none

   **What changed from the plan:** none

   **Key files:**
   - `.claude/workflow/step-implementation.md` (modified)
   - `.claude/skills/create-plan/SKILL.md` (modified)

   **Critical context:** none
   ```

   Failed steps follow the same shape with header `### Step N — FAILED,
   <ISO> [ctx=<level>]` and field set `What was attempted / Why it
   failed / Impact on remaining steps / Key files`.

10. **`## Validation and Acceptance`** — plan-at-start. Phase 1 writes
    track-level behavioral acceptance criteria; per-step EARS/Gherkin
    lines are deferred to Phase A (placeholder until decomposition
    runs). The trailing slot is reserved for Move 3's EARS/Gherkin
    acceptance lines (empty placeholder until that Move lands).

11. **`## Idempotence and Recovery`** — plan-at-start, **Phase A**
    populated. Names specific steps and per-step recovery paths; cannot
    be authored before decomposition. Phase 1 leaves a Phase A
    placeholder comment per D10.

12. **`## Artifacts and Notes`** — continuous-log (rare), cross-step
    content only per D11. Focused transcripts, snippets, and artifact
    references that don't belong to one specific step (e.g., a review
    iteration log that spans multiple steps, captured terminal output
    from a cross-cutting debug session, links to external dashboards or
    PR threads). Per-step content lives in `## Episodes` above.

13. **`## Interfaces and Dependencies`** — plan-at-start. Phase 1
    writes file-scope and contract boundaries (in-scope / out-of-scope
    file lists), inter-track dependencies (which tracks supply
    prerequisites; which downstream tracks consume this one's output),
    and library / function signatures relevant to this track. Folds in
    today's `**Constraints**:` and `**Interactions**:` subsections.

14. **`## Base commit`** — workflow housekeeping, not part of the
    12-section ExecPlan. Phase B writes the SHA of `HEAD` once at
    session start; Phase C reads it to compute the cumulative track
    diff. Readers must verify the recorded SHA is a HEAD-ancestor
    before use (the canonical preflight-and-recompute procedure lives
    at the two reader sites — [`step-implementation.md`](step-implementation.md)
    §Phase B Startup step 1 and [`track-code-review.md`](track-code-review.md)
    §Phase C Startup step 2 — and a stale recompute appends a
    discrepancy note without overwriting the original SHA).

#### Two placeholder kinds coexist on a Phase-1-written track file

Before Phase A runs, a freshly-written track file carries two distinct
placeholder kinds. `structural-review.md` treats both as non-defects
per D6 and D10:

- **Phase A placeholders** in `## Idempotence and Recovery` (always),
  in `## Concrete Steps` (the roster is empty until decomposition),
  and in the step-referencing prose inside `## Plan of Work` and the
  per-step lines in `## Validation and Acceptance`. Cleared when Phase
  A runs.
- **Sibling Move placeholders** in `## Purpose / Big Picture` (Move 2
  ADDED/MODIFIED/REMOVED triad slot), in `## Decision Log` (Move 1
  per-track inlined Decision Records slot), and in `## Validation and
  Acceptance` (Move 3 EARS/Gherkin acceptance slot). Cleared when the
  corresponding Move lands.

#### Section lifecycle

| Section | Phase 1 writer | Phase A writer | Phase B writer | Phase C writer | Readers |
|---|---|---|---|---|---|
| `## Purpose / Big Picture` | BLUF + intro paragraph + Move 2 placeholder | — | — | — | Phase 2 reviews; Phase A/B/C orchestration; Phase 4 aggregation |
| `## Progress` | (empty) | decomposition-complete entry **(D12 statusline read → `[ctx=<level>]`; falls back to `unknown` when /tmp/claude-code-context-usage-$PPID.txt is missing)** | per-step entry **(sub-step 7; same D12 read)** | per-iteration entry + track-completion entry **(same D12 read)** | resume-readers (most-recent entry = current phase); Phase 4 |
| `## Surprises & Discoveries` | (empty) | (rare — Pre-Flight clarification surfaces a cross-cutting fact) | promotion from per-step episode at sub-step 7 (when cross-cutting) | promotion from review iteration findings (when cross-cutting) | Phase A Pre-Flight Panel 1; Phase 4 |
| `## Decision Log` | Move 1 placeholder | Pre-Flight clarifications (when decision-worthy) | promotion from per-step episode at sub-step 7 (when decision-worthy) | gate-override / inline-replan entries | Phase A reviews; Phase 4 |
| `## Outcomes & Retrospective` | (empty) | Phase A review iteration entries | (occasional — dimensional review iteration entries) | review iteration entries + track completion summary | Phase A reviews; Phase 4 |
| `## Context and Orientation` | "what's there today" prose | — | — | — | Phase A/B/C orchestration; Phase 4 |
| `## Plan of Work` | "what we'll change" prose | per-step sequencing summary referencing Concrete Steps | — | — | Phase A/B/C orchestration; Phase 4 |
| `## Concrete Steps` | Phase A placeholder | thin numbered roster (description + `risk:` tag + `[ ]` checkbox per step) | status checkbox flip + optional `commit:` annotation | — | Phase A reviews; Phase B sub-step 4 (risk tag); Phase C track review; Phase 4 |
| `## Episodes` | (empty) | (empty — Phase A does not populate) | one block per completed step at sub-step 7 **(D12 statusline read → `[ctx=<level>]` on block header; falls back to `unknown` when /tmp/claude-code-context-usage-$PPID.txt is missing)** | — | Phase A Pre-Flight Panel 1 strategy assessment; Phase C track-completion compile-episode; Phase 4 |
| `## Validation and Acceptance` | track-level acceptance + Phase A placeholder for per-step lines + Move 3 placeholder | per-step EARS/Gherkin lines (when decomposition surfaces them) | — | — | Phase B implementer; Phase C track review; Phase 4 |
| `## Idempotence and Recovery` | Phase A placeholder | per-step recovery paths | — | — | Phase B/C orchestration; Phase 4 |
| `## Artifacts and Notes` | (empty) | (rare) | (rare — cross-step artifact reference) | review-iteration logs that span multiple steps | Phase C track review; Phase 4 |
| `## Interfaces and Dependencies` | file boundaries + inter-track deps + signatures | — | — | — | Phase B implementer; Phase C track review; Phase 4 |
| `## Base commit` | (empty) | (empty) | SHA at session start | discrepancy note on stale-recompute | Phase B implementer; Phase C track review |

Phase B writes to `## Concrete Steps` (status flip), `## Episodes`
(new block), and `## Progress` (new entry) in a single commit at
sub-step 7; the canonical write order is statusline read → Episodes
block → Concrete Steps checkbox flip → Progress entry. Inline
replanning (see [`inline-replanning.md`](inline-replanning.md)) may
rewrite `## Concrete Steps`, `## Plan of Work`, and `## Validation and
Acceptance` mid-execution; otherwise plan-at-start sections are stable
after Phase A.

The Track Pre-Flight gate (see [`track-review.md`](track-review.md)
§Track Pre-Flight) may amend `## Purpose / Big Picture`,
`## Context and Orientation`, and `## Plan of Work` in place and append
clarifications to `## Decision Log` when the gate captures any.

**Track files live under `docs/adr/<dir-name>/_workflow/plan/`** —
tracked in git during the branch lifetime so changes are pushed to the
draft PR for team visibility and disk-loss backup, and removed
alongside the rest of `_workflow/` in the Phase 4 cleanup commit before
merge (see `conventions.md` §1.2 for the full lifecycle and
`workflow.md` § Final Artifacts for the cleanup procedure).

#### Risk tag, base commit, and the strategic-guide stance

The **`risk:` tag** on each `## Concrete Steps` roster line names the
step's risk level (`low`, `medium`, or `high`) and the triggering
category (or `default` / `override: <reason>`). Phase A's decomposer
writes it; the user reviews it before the track file is approved.
Phase B reads it at sub-step 4 — the dimensional review loop runs only
when the tag is `high`; for `medium` and `low` steps Phase B skips
directly to sub-step 5. Phase B may upgrade the tag in place (recording
the upgrade in the same line) but never downgrade. After the step is
committed and the episode written, the risk tag is locked. Phase C
reads the locked tags and treats `medium` and `high` step ranges as
focal points for the cumulative track diff review. Full criteria,
override rules, and lifecycle live in
[`risk-tagging.md`](risk-tagging.md).

The **`[ctx=<level>]` field** on every `## Progress` entry and every
`## Episodes` block header (per D12) reflects the orchestrator's
context-window state at write time, not the implementer sub-agent's.
The orchestrator reads `/tmp/claude-code-context-usage-$PPID.txt`
immediately before the write and inlines the parsed `level=` value
(`safe` / `info` / `warning` / `critical`, or `unknown` if the file is
missing). The field is mandatory — a write that omits it is a contract
violation — and write-time only. No post-factum audit at Phase C or
structural review backfills missing fields; the actual `ctx` at write
time is unrecoverable. See `design.md` §"Continuous-log discipline"
subsection *Mandatory `[ctx=<level>]` field*.

Track files are created during Phase 1 (planning), one per planned
track. They do not exist before Phase 1.

**The plan is a strategic guide, not a rigid task graph.** Track
descriptions, architecture notes, and inter-track dependencies are the
load-bearing parts. Step-level detail is tactical and should emerge
just-in-time during execution when the agent has maximum codebase
context. Phase A always has freedom to adapt step-level decomposition
without formal replanning — only track-level or decision-level changes
require escalation.

---

## 2.2 Episode Formats

Three episode types: **step completion** (`[x]`), **step failed** (`[!]`),
and **track episode** (in plan file after user approval).

Step completion fields: **What was done** (always), **What was discovered**
(when applicable — fill whenever anything unexpected is found),
**What changed from the plan** (when applicable — name affected future
steps), **Key files** (always), **Critical context** (rare).

Step failed fields: **What was attempted**, **Why it failed**, **Impact on
remaining steps**, **Key files**.

Episodes are immutable once written. Code is committed first, then the
episode is written to the track file on disk. Episode length is proportional
to cross-track impact.

**Full format, rules, and examples:**
[`episode-format-reference.md`](episode-format-reference.md)

---

## 2.3 Ephemeral identifier rule — pointer

The full rule (forbidden categories with examples, allowed list,
rewrite examples, branch-only-commit exemption) lives in
[`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md). Load
that file when about to author durable content — source code, tests,
Javadoc, PR title/body, `design-final.md`, or `adr.md`.

**Quick recap (do not rely on this list alone for borderline cases —
read the full rule):**

Forbidden in durable content:
- Track / Step labels (`Track N`, `Step M`, `Track N Step M`)
- Review finding IDs / prefixes (`CQ33`, `F-12`, `R-4`, …)
- Review-loop iteration counters (`iteration 1`, `round 2`)
- Named invariants cited by label only ("Single-authority invariant", …)
- Plan-file Decision Record IDs not restated in `adr.md`

Allowed: file paths, class/method/field names, commit SHAs,
`adr.md`-defined DR IDs, issue tracker IDs (e.g. `YTDB-123`).

**The pre-commit gate is not a passive self-check.** The implementer
sub-agent enforces this rule as a **hard gate** in sub-step 3 of
[`implementer-rules.md`](implementer-rules.md) (§"Pre-commit gate,
ephemeral-identifier check") before every `git commit` that
touches paths outside `_workflow/` and `.claude/workflow/`; the
same gate is mirrored in
[`commit-conventions.md`](commit-conventions.md) §"Ephemeral-identifier
pre-commit gate" for ad-hoc commits outside `/execute-tracks`. Both
sites carry the canonical procedure (regex, inspect-then-rewrite
loop, contract-violation language); this stub does not duplicate
them. For convenience, the regex is:

```bash
git diff --cached -- ':(exclude)docs/adr/*/_workflow/**' ':(exclude).claude/workflow/**' | grep -nE '^\+.*\b(Track|Step)[ ]?[0-9]+|^\+.*\b[A-Z]{1,3}-?[0-9]+\b'
```

If it returns matches that aren't allowed exceptions (issue
tracker IDs, class names that happen to match the pattern), load
the full rule and consult its "How to rewrite a forbidden
reference" section before issuing `git commit`. The `^\+`-anchored
form narrows to additions so the gate stays fast on large refactor
diffs.

**Branch-only commit messages are exempt.** Individual commit messages
on the development branch may cite Track / Step / finding labels —
they are squashed away on merge.

---

## 2.4 Commit messages, code review, complexity, decomposition

These rules are needed only when their specific phase or action runs — not
at session startup. Load on demand:

- **Commit message format** — follow the project's `CLAUDE.md` commit
  conventions. Both code changes and workflow-file changes (under
  `_workflow/`) are committed during the branch lifetime; the
  `_workflow/` directory is removed in the Phase 4 cleanup commit
  before merge (see §1.2 in `conventions.md` and `workflow.md`
  § Final Artifacts). Every commit is pushed immediately to the
  branch's draft PR for team visibility; see
  [`commit-conventions.md`](commit-conventions.md) for the push rule
  and the execution-specific prefixes (`Review fix:`) used during
  session resume. The Ephemeral identifier rule (§2.3 stub above; full
  rule in [`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md))
  applies to durable content — branch-only commit messages are exempt.
- **Two-tier dimensional code review** (step-level and track-level
  sub-agent reviews, 4 baseline + up to 6 conditional + up to 6
  workflow-review, max 3 iterations):
  [`code-review-protocol.md`](code-review-protocol.md).
- **Complexity tiers** (which pre-execution reviews to run for Simple /
  Moderate / Complex tracks): covered in
  [`track-review.md`](track-review.md) §Complexity Assessment.
- **Checklist decomposition rules** (step sizing, cross-cutting concerns,
  parallel step annotation): covered in
  [`track-review.md`](track-review.md) §Step Decomposition.
