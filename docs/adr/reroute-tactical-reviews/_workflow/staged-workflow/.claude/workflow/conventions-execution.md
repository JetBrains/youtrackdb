# Conventions — Execution (Phase 3)

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §2.1 Plan File — Execution Additions | orchestrator,decomposer,implementer | 3A,3B,3C | Execution-time additions to the plan file: the 14-section track file shape, lifecycle, and risk/base-commit rules. |
| §Session state detection | orchestrator | 3A,3B,3C | Moved to the Startup Protocol — the only place where state detection is used. |
| §Track file content (`plan/track-N.md`) | orchestrator,decomposer | 3A,3B,3C | The 14-section track file template (12 ExecPlan sections plus Episodes and Base commit) and its continuous-log ordering. |
| §2.2 Episode Formats | orchestrator | 3A,3B,3C | The three episode types (step completion, step failed, track episode) and their field sets; full rules elsewhere. |
| §2.3 Ephemeral identifier rule — pointer | implementer,final-designer | 3B,3C,4 | Quick recap of the ephemeral-identifier rule plus the pre-commit gate regex; full rule in the dedicated file. |
| §2.4 Commit messages, code review, complexity, decomposition | orchestrator | 3A,3B,3C | On-demand pointers to commit conventions, the code-review protocol, complexity tiers, and decomposition rules. |
| §2.5 Review-file schema, count validation, and coverage | orchestrator,decomposer,implementer,reviewer-dim-step,reviewer-dim-track,reviewer-plan,reviewer-panel | 2,3A,3B,3C,4 | The canonical manifest-plus-sections review-file schema, the ID-anchored count-validation grep (S4/S6), the verdict-producer variant, and the bulk-producer coverage rule (S5). |

<!--Document index end-->

Execution-specific formats and rules. Loaded only during Phase 3
(`/execute-tracks`). For core conventions shared across all phases, see
conventions.md:any:any.

---

## 2.1 Plan File — Execution Additions
<!-- roles=orchestrator,decomposer,implementer phases=3A,3B,3C summary="Execution-time additions to the plan file: the 14-section track file shape, lifecycle, and risk/base-commit rules." -->

These subsections extend the plan file structure defined in
`conventions.md` `§1.2`.

The phase-specific plan-entry mutations (collapse-on-completion and
strategy-refresh-line append) live with their owning phase
documents — they are not loaded by every Phase 3 session:

- **Track-completion collapse** (Phase C): see
  track-code-review.md:orchestrator:3C § Track Completion
  step 4 — the "Always keep" / "Always drop" rule, the track-episode
  fields, and the final on-disk form.
- **Strategy refresh line** (State A — written by the Track Pre-Flight
  gate when Panel 1 is active): see
  track-review.md:orchestrator:3A § Track Pre-Flight step 6
  (Persist amendments + strategy-refresh line) — the line format for
  CONTINUE / ADJUST and the `[~]`-track variant.

### Session state detection
<!-- roles=orchestrator phases=3A,3B,3C summary="Moved to the Startup Protocol — the only place where state detection is used." -->

Moved to `workflow.md` §Startup Protocol — the only place where state
detection is used.

### Track file content (`plan/track-N.md`)
<!-- roles=orchestrator,decomposer phases=3A,3B,3C summary="The 14-section track file template (12 ExecPlan sections plus Episodes and Base commit) and its continuous-log ordering." -->

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
<!-- roles=orchestrator,decomposer phases=3A,3B,3C summary="The 14 track-file sections in order, each with its Phase 1 seed content and per-phase writer." -->

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
   `risk:` tag, an optional `size:` clause, and a status checkbox.
   **No nested blockquote** — per D9 the per-step episode lives in
   `## Episodes` below, not here. The roster is immutable after Phase A
   except for the status checkbox flip (`[ ]` → `[x]` / `[!]`) and the
   optional `commit: <SHA>` annotation Phase B appends after the
   checkbox (`commit: <SHA>` is appended after any `— size:` clause). The optional
   inline `— size: ~N files; <reason>` clause is written at Phase A and
   appears only on a `low`/`medium` step whose planned footprint lands
   below the `~12` fill target, naming a closed-set reason it is not
   maximized (rule in `track-review.md` §"Step Decomposition" →
   Under-fill justification); a maximized step omits it.

   ```markdown
   ## Concrete Steps
   1. <Step description> — `risk: low`  [ ]
   2. <Step description> — `risk: high`  [ ]
   3. <Step description> — `risk: medium`  [ ]
   4. <Step description> — `risk: low` — size: ~N files; <closed-set reason>  [ ]
   5. <Step description> — `risk: low` — size: ~N files; <closed-set reason>  [x] commit: <SHA>
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
    at the two reader sites — step-implementation.md:orchestrator:3B
    §Phase B Startup step 1 and track-code-review.md:orchestrator:3C
    §Phase C Startup step 2 — and a stale recompute appends a
    discrepancy note without overwriting the original SHA).

#### Two placeholder kinds coexist on a Phase-1-written track file
<!-- roles=reviewer-plan,orchestrator phases=2,3A summary="Phase A placeholders vs sibling-Move placeholders; structural review treats both as non-defects." -->

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
<!-- roles=orchestrator,decomposer phases=3A,3B,3C summary="Per-section writer/reader matrix across Phase 1/A/B/C for each of the 14 track-file sections." -->

| Section | Phase 1 writer | Phase A writer | Phase B writer | Phase C writer | Readers |
|---|---|---|---|---|---|
| `## Purpose / Big Picture` | BLUF + intro paragraph + Move 2 placeholder | — | — | — | Phase 2 reviews; Phase A/B/C orchestration; Phase 4 aggregation |
| `## Progress` | four pre-seeded phase-checkpoint entries (`- [ ] Review + decomposition`, `- [ ] Step implementation`, `- [ ] Track-level code review`, `- [ ] Track completion`) — State C resume reads these as phase markers | decomposition-complete entry **(D12 statusline read → `[ctx=<level>]`; falls back to `unknown` when /tmp/claude-code-context-usage-$PPID.txt is missing)** | per-step entry **(sub-step 7; same D12 read)** | per-iteration entry + track-completion entry **(same D12 read)** | resume-readers (most-recent entry = current phase); Phase 4 |
| `## Surprises & Discoveries` | (empty) | (rare — Pre-Flight clarification surfaces a cross-cutting fact) | promotion from per-step episode at sub-step 7 (when cross-cutting) | promotion from review iteration findings (when cross-cutting) | Phase A Pre-Flight Panel 1; Phase 4 |
| `## Decision Log` | Move 1 placeholder | — | promotion from per-step episode at sub-step 7 (when decision-worthy) | gate-override / inline-replan entries | Phase A reviews; Phase 4 |
| `## Outcomes & Retrospective` | (empty) | Phase A review iteration entries (prefix: `Technical:` / `Risk:` / `Adversarial:`) | (occasional — dimensional review iteration entries) | review iteration entries + track completion summary (prefix: `Track-level code review iteration N…` / `Track complete`) | Phase A reviews; Phase 4 |
| `## Context and Orientation` | "what's there today" prose | Pre-Flight clarifications (appended as `### Clarifications` subsection) | — | — | Phase A/B/C orchestration; Phase 4 |
| `## Plan of Work` | "what we'll change" prose | per-step sequencing summary referencing Concrete Steps | — | — | Phase A/B/C orchestration; Phase 4 |
| `## Concrete Steps` | Phase A placeholder | thin numbered roster (description + `risk:` tag + optional `size:` clause on under-filled `low`/`medium` steps + `[ ]` checkbox per step) | status checkbox flip + optional `commit:` annotation | — | Phase A reviews; Phase B sub-step 4 (risk tag); Phase C track review; Phase 4 |
| `## Episodes` | (empty) | (empty — Phase A does not populate) | one block per completed step at sub-step 7 **(D12 statusline read → `[ctx=<level>]` on block header; falls back to `unknown` when /tmp/claude-code-context-usage-$PPID.txt is missing)** | — | Phase A Pre-Flight Panel 1 strategy assessment; Phase C track-completion compile-episode; Phase 4 |
| `## Validation and Acceptance` | track-level acceptance + Phase A placeholder for per-step lines + Move 3 placeholder | per-step EARS/Gherkin lines (when decomposition surfaces them) | — | — | Phase B implementer; Phase C track review; Phase 4 |
| `## Idempotence and Recovery` | Phase A placeholder | per-step recovery paths | — | — | Phase B/C orchestration; Phase 4 |
| `## Artifacts and Notes` | (empty) | (rare) | (rare — cross-step artifact reference) | review-iteration logs that span multiple steps | Phase C track review; Phase 4 |
| `## Interfaces and Dependencies` | file boundaries + inter-track deps + signatures | — | — | — | Phase B implementer; Phase C track review; Phase 4 |
| `## Base commit` | (empty) | (empty) | SHA at session start | discrepancy note on stale-recompute | Phase B implementer; Phase C track review |

Phase B writes to `## Concrete Steps` (status flip), `## Episodes`
(new block), and `## Progress` (new entry) in a single commit at
sub-step 7; the canonical write order is statusline read → Episodes
block + Concrete Steps roster checkbox flip (one atomic edit per
step-implementation.md:orchestrator:3B sub-step 7.1 and
episode-format-reference.md:orchestrator:3B,3C
sub-step 1) → Progress entry → optional Surprises promotion →
optional Decision Log promotion. Inline replanning (see
inline-replanning.md:orchestrator:3A,3C) may
rewrite `## Concrete Steps`, `## Plan of Work`, and `## Validation and
Acceptance` mid-execution; otherwise plan-at-start sections are stable
after Phase A.

The Track Pre-Flight gate (see track-review.md:orchestrator:3A
§Track Pre-Flight) may amend `## Purpose / Big Picture`,
`## Context and Orientation`, and `## Plan of Work` in place and append
clarifications as a `### Clarifications` subsection to
`## Context and Orientation` when the gate captures any
(clarifications are user-supplied current-state notes — the
C&O-as-current-state idiom from `design-final.md` §"Section mapping —
old shape to new" keeps them with the rest of the current-state
framing).

**Track files live under `docs/adr/<dir-name>/_workflow/plan/`** —
tracked in git during the branch lifetime so changes are pushed to the
draft PR for team visibility and disk-loss backup, and removed
alongside the rest of `_workflow/` in the Phase 4 cleanup commit before
merge (see `conventions.md` `§1.2` for the full lifecycle and
`workflow.md` § Final Artifacts for the cleanup procedure).

#### Review-file lifecycle
<!-- roles=orchestrator,reviewer-dim-step,reviewer-dim-track,reviewer-plan,reviewer-panel phases=2,3A,3B,3C,4 summary="Review files are plan-directory artifacts written and committed at reviewer-return under plan/track-N/reviews/, swept by the Phase 4 cleanup; the thin episode-block shape and the plan/* glob caution." -->

Review files (the manifest-plus-sections files defined in `§2.5`) are
**plan-directory artifacts**, not `.claude/` files, so they never touch
the staged mirror — they live in the real `_workflow/`. Each lives under
`docs/adr/<dir-name>/_workflow/plan/track-N/reviews/`, a directory beside
the `plan/track-N.md` file. A producer `mkdir -p`s its own `reviews/`
directory (idempotent) and writes a per-fan-out-unique filename so
concurrent reviewers in one parallel fan-out never share a path.

**Committed at reviewer-return.** A review file is written **and
committed** at reviewer-return, as a Workflow-update commit. Committing
(not merely writing) is the resume precondition (D10): a crash before the
commit would lose the file and force the re-spawn the schema avoids. On a
`/clear` after a completed review pass whose file is committed, resume
reads the committed file (manifests for routing, `## Findings` for
strategic) rather than re-spawning that reviewer. This does **not**
override the Phase A re-run rule for an *interrupted* iteration:
`track-review.md` gates Phase A resume on the `## Outcomes &
Retrospective` checkboxes and re-runs an interrupted review type from
iteration 1, because mid-iteration findings are not trustworthy once a
partial fix has landed. The persistence payoff is the completed-review
boundary, where the committed file is the durable record.

**Thin episode block.** The episode block that records a review pass
points at the review files and records the manifest counts only — it
carries no finding bodies. The block sits inside the canonical episode
shape (`§2.2`).

**Phase 4 sweep.** Review files need no dedicated cleanup step. The Phase
4 cleanup is a blanket recursive `git rm -r _workflow/` (in `workflow.md`
§ Final Artifacts and `create-final-design.md`), which removes
`plan/track-N/reviews/` along with the rest of `_workflow/`
automatically, the same way it sweeps `handoff-*.md`.

**`plan/*` glob caution.** The `plan/track-N.md` file and the
`plan/track-N/` directory coexist. Any consumer that walks `plan/` must
not glob bare `plan/*` expecting files only — it would pick up the
`track-N/` directory. The current consumers are safe: they glob
`plan/track-*.md` (the `.md` suffix excludes a `track-N/` directory) or
`.md`-filter a non-recursive `listdir`, and the Phase 4 cleanup is the
blanket recursive `rm` above. This caution is a forward guard for any
future consumer.

#### Risk tag, base commit, and the strategic-guide stance
<!-- roles=orchestrator,decomposer,implementer phases=3A,3B,3C summary="The risk: tag lifecycle, the mandatory [ctx=<level>] field, and the plan-as-strategic-guide stance." -->

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
risk-tagging.md:decomposer,orchestrator:3A,3B.

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
<!-- roles=orchestrator phases=3A,3B,3C summary="The three episode types (step completion, step failed, track episode) and their field sets; full rules elsewhere." -->

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
to cross-track impact. The labeled-bold-paragraph template is one of the
template-bound shapes exempt from the house-style soft section length cap —
see `house-style.md` § Structural rules
"Section length cap exception" for the exemption clause and the
padding-based finding criterion that applies to these blocks.

**Full format, rules, and examples:**
episode-format-reference.md:orchestrator:3B,3C

---

## 2.3 Ephemeral identifier rule — pointer
<!-- roles=implementer,final-designer phases=3B,3C,4 summary="Quick recap of the ephemeral-identifier rule plus the pre-commit gate regex; full rule in the dedicated file." -->

The full rule (forbidden categories with examples, allowed list,
rewrite examples, branch-only-commit exemption) lives in
ephemeral-identifier-rule.md:implementer,final-designer:3B,3C,4. Load
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
implementer-rules.md:implementer:3B,3C (§"Pre-commit gate,
ephemeral-identifier check") before every `git commit` that
touches paths outside `_workflow/` and `.claude/workflow/`; the
same gate is mirrored in
commit-conventions.md:orchestrator,implementer:3A,3B,3C §"Ephemeral-identifier
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
<!-- roles=orchestrator phases=3A,3B,3C summary="On-demand pointers to commit conventions, the code-review protocol, complexity tiers, and decomposition rules." -->

These rules are needed only when their specific phase or action runs — not
at session startup. Load on demand:

- **Commit message format** — follow the project's `CLAUDE.md` commit
  conventions. Both code changes and workflow-file changes (under
  `_workflow/`) are committed during the branch lifetime; the
  `_workflow/` directory is removed in the Phase 4 cleanup commit
  before merge (see `§1.2` in `conventions.md` and `workflow.md`
  § Final Artifacts). Every commit is pushed immediately to the
  branch's draft PR for team visibility; see
  commit-conventions.md:orchestrator,implementer:3A,3B,3C for the push rule
  and the execution-specific prefixes (`Review fix:`) used during
  session resume. The Ephemeral identifier rule (`§2.3` stub above; full
  rule in ephemeral-identifier-rule.md:implementer,final-designer:3B,3C,4)
  applies to durable content — branch-only commit messages are exempt.
- **Two-tier dimensional code review** (step-level and track-level
  sub-agent reviews; the reviewer pool across both tiers is 4 baseline
  + up to 6 conditional + up to 6 workflow-review, max 3 iterations).
  The per-tier baseline selection differs — the step tier launches a
  subset (`review-bugs-concurrency` only), the track tier all four;
  see code-review-protocol.md:orchestrator:3B,3C.
- **Complexity tiers** (which pre-execution reviews to run for Simple /
  Moderate / Complex tracks): covered in
  track-review.md:orchestrator:3A §Complexity Assessment.
- **Checklist decomposition rules** (step sizing, cross-cutting concerns,
  parallel step annotation): covered in
  track-review.md:orchestrator,decomposer:3A §Step Decomposition.

---

## 2.5 Review-file schema, count validation, and coverage
<!-- roles=orchestrator,decomposer,implementer,reviewer-dim-step,reviewer-dim-track,reviewer-plan,reviewer-panel phases=2,3A,3B,3C,4 summary="The canonical manifest-plus-sections review-file schema, the ID-anchored count-validation grep (S4/S6), the verdict-producer variant, and the bulk-producer coverage rule (S5)." -->

This subsection is the **single source of truth** for the review-file
schema every bulk-producing sub-agent writes. Other documents — the
strategic and dimensional reviewer prompts, the orchestrator routing,
the implementer's anchor-read — cite this subsection rather than
restating the schema.

### Manifest-plus-sections file

A bulk-producing sub-agent persists its structured output to a file at a
spawn-supplied path and returns only the manifest. The file opens with an
HTML-comment `MANIFEST` block, then segregated body sections:

```markdown
<!-- MANIFEST
findings: 12   severity: {blocker: 2, should-fix: 5, suggestion: 5}
index:
  - {id: BC1, sev: blocker,    loc: Foo.java:142, anchor: "### BC1 ", cert: C3, basis: "TOCTOU on shared cache map; concrete interleaving traced"}
  - {id: BC2, sev: should-fix, loc: Bar.java:88,  anchor: "### BC2 ", cert: C7, basis: "missing null check on nullable return"}
evidence_base: {section: "## Evidence base", certs: 20, matches: 16}
cert_index:
  - {id: C3, verdict: WRONG, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings
### BC1 [blocker] ...
### BC2 [should-fix] ...

## Evidence base
#### C1 ... MATCHES
#### C3 ... WRONG
```

The manifest is the same block the sub-agent returns, echoed verbatim.
Persisting to a file rather than returning the same structure inline is
the load-bearing choice: an inline structured return tidies the message
shape but leaves nothing on disk, so a mid-review `/clear` still
re-spawns the panel. The file is what makes resume cheap. The manifest
comment reuses the HTML-comment-plus-regex idiom the workflow-sha stamps
already rely on, so it parses stably.

**Manifest index fields — mandatory vs downstream.** Each `index` entry
carries six fields. Three are **mandatory** on every producer's manifest:

- `id` — the per-reviewer finding ID (`BC1`, `T1`); the anchor key and
  bucketing dimension proxy. Never renumbered.
- `sev` — the finding's severity (`blocker` / `should-fix` /
  `suggestion`, or the producer's native scale).
- `anchor` — the stable heading anchor (`### BC1 `) the body is reached
  by.

Three are **consumed downstream** — populated by the producer, read by
the tactical-routing consumers that arrive in a later track:

- `loc` — the `file:line` location, used for `loc`-collapse across
  dimensions.
- `cert` — the certificate or evidence-trail anchor, used for
  contested-finding drill-down.
- `basis` — the one-line impact statement, used by the orchestrator's
  upgrade-only severity backstop.

A producer fills all six; the mandatory/downstream split tells a reader
which fields it may rely on being present versus which a particular
consumer keys off. `evidence_base`, `cert_index`, and `flags` are
manifest-level (not per-finding): `evidence_base` summarises the
`## Evidence base` section, `cert_index` lists the certificate verdicts,
and `flags` carries `CONTRACT_OK` or `CONTRACT_VIOLATION`.

### Anchored addressing and count validation (S4/S6)

Addressing keys on **stable heading anchors** (`^### BC1 `, `^#### C3 `),
which survive minor format drift; a line offset, when present, is an
optional fast-path hint only.

The `### <PREFIX><N> ` three-hash heading shape is **reserved file-wide**
for finding anchors. A producer puts no other `### ` heading anywhere in
the file: all reasoning prose lives in `## Evidence base` (`#### `
entries, four-hash, no collision) or inside a finding body using `####`
or bold sub-structure, never a `### <CAPS><digit>` heading. The
file-wide reservation (not only under `## Findings`) keeps the count grep
honest no matter where a stray heading might appear.

Validation is an anchor-header grep, never a body read. A reader confirms
the manifest's claimed `findings` count matches the canonical regex over
the file before trusting the index (S4):

```bash
grep -cE '^### [A-Z]+[0-9]+ ' <file>
```

The regex is ID-anchored on purpose. A bare `^### ` count would also
catch any non-finding `### ` heading — the same false-positive trap the
workflow-sha parser avoids by anchoring on its `workflow-sha:` literal
rather than a bare 40-hex match. The character class is `[A-Z]+`
(one-or-more uppercase), **not** `[A-Z]{2,}`: the strategic reviewers use
single-letter prefixes (`T` technical, `R` risk, `A` adversarial, `S`
structural) while the dimensional reviewers use two-letter prefixes
(`BC`, `CQ`, …), and `[A-Z]{2,}` would return zero for a single-letter
strategic file (`### T1 ` → 0) and raise a spurious `CONTRACT_VIOLATION`.
`[A-Z]+` matches both prefix shapes; the trailing space after `[0-9]+`
excludes the four-hash `#### <cert>` evidence entries from the count.

Because the grep counts heading lines only, a reader validates without
ingesting any body, so the no-bodies invariant holds through validation
(S6).

**CONTRACT_VIOLATION fallback.** On a count mismatch — the manifest
`findings` count differs from the grep count — the manifest carries a
`CONTRACT_VIOLATION` flag and the reader falls back to a whole-section
read. The fallback owner differs by routing class: the implementer for
tactical reviews, the orchestrator or planner for strategic reviews. The
orchestrator therefore never falls back to a tactical body read; a
violated tactical file routes the whole-section fallback to the
implementer, preserving the no-bodies invariant on the orchestrator.

A zero-finding reviewer writes an empty `## Findings` and a `findings: 0`
manifest; the count grep returns 0, validation passes, and the
orchestrator routes on counts without spawning an implementer.

### Verdict-producer manifest variant

A **gate-verifying** strategic reviewer (the plan/decomposition
gate-verification prompts — `consistency-gate-verification.md`,
`structural-gate-verification.md`, `review-gate-verification.md`) does not
emit a fresh severity-graded finding set. It emits per-prior-finding
verdicts (`VERIFIED` / `STILL OPEN` / `REJECTED`) plus an overall
`PASS` / `FAIL`. The finding-shaped manifest fields do not map cleanly to
that output, so a verdict producer uses a variant:

- Its `findings` count and `## Findings` anchors cover only its **new**
  findings (a verification pass may surface a fresh issue), so the S4
  count grep still validates the new-finding anchors.
- It conveys the per-prior-finding verdicts through a distinct `verdicts`
  manifest block, separate from `index`:

```markdown
<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: T7, sev: should-fix, loc: ..., anchor: "### T7 ", cert: ..., basis: ...}
verdicts:
  - {id: T3, verdict: VERIFIED}
  - {id: T5, verdict: STILL OPEN}
overall: PASS
flags: [CONTRACT_OK]
-->
```

A pure-verdict pass with no new finding writes `findings: 0` and an empty
`## Findings`; the count grep returns 0 and validation passes.

### Coverage (S5)

Every bulk-producing sub-agent class either follows the file-plus-manifest
rule above or carries an explicit `exempt because…` annotation stating
why the rule does not apply. The rule plus the `exempt because…` hatch is
itself an invariant: a **new** bulk-producing agent class must declare one
or the other. The covered classes are the strategic panel reviewers, the
dimensional reviewers, the gate-check reviewers, the plan reviewers, and
the research/audit sub-agents; the documented exemptions are the four
pure-standalone review agents (output consumed by the user in the same
turn) and `review-mode.md`'s `FIX_FINDING` path (user-sourced triples,
already in the orchestrator's conversation context).

**Enforcement status.** S5 is a **documented contract checked by the
decomposer and reviewer**, not a mechanical gate. Nothing enumerates the
bulk-producing classes and asserts each carries file-plus-manifest or an
`exempt because…` annotation. A mechanical coverage check over the full
producer set is deferred: the dimensional reviewer agents do not carry
their annotations yet (that lands with the dimensional producers), so an
assertion of universal compliance would fail until then. This is the
distinction from S4/S6, which are mechanical and carry a test under
`.claude/scripts/tests/`. A reviewer or decomposer encountering a new
bulk producer enforces S5 by inspection.
