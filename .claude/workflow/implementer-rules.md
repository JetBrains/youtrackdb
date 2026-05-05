# Phase B Implementer — Rulebook

This document is the rulebook for the **per-step implementer sub-agent**
spawned by the Phase B orchestrator. The implementer performs sub-steps
1–3 of step implementation (implement code, write/run tests, commit) and
returns a structured handoff to the orchestrator. The orchestrator owns
sub-steps 4–7 and all session-level decisions.

The rulebook for the orchestrator side — startup, per-step orchestration
loop, dimensional review fan-out, episode finalisation — lives in
[`step-implementation.md`](step-implementation.md). The two documents are
the only Phase B entry points; everything else is loaded on demand.

---

## Loading discipline

This file is read **only by the per-step implementer sub-agent** on
each spawn. Orchestrators do not load it. The orchestrator's
specification — including the implementer prompt template and how this
rulebook is referenced from each spawn — lives in
[`step-implementation.md`](step-implementation.md).

The implementer's environment auto-loads the user-global rules in
`~/.claude/CLAUDE.md` and the project rules in the repo's `CLAUDE.md`.
Beyond those and this file, the implementer reads the step file, the
slim plan snapshot, and `design.md` (only if the step requires it).

---

## Inputs the orchestrator passes on each spawn

The implementer prompt has a **stable static prefix** (workflow context
+ rulebook path + project paths) followed by **per-step variable
inputs**.

**Stable inputs** (same across spawns within a Phase B session):

- `repo_root` — absolute path to the working tree.
- `plan_slim_path` — `/tmp/claude-code-plan-slim-$PPID.md`.
- `step_file_path` — `docs/adr/<dir-name>/tracks/track-<N>.md`.
- `design_path` — `docs/adr/<dir-name>/design.md` (read on demand only).

**Variable inputs** (per spawn):

- `step_index` — the integer N identifying which step in the file is
  being implemented.
- `step_description` — copied inline from the step file's
  `- [ ] Step: …` line.
- `risk_tag` — `low` | `medium` | `high`.
- `base_commit` — SHA recorded at Phase B startup.
- `mode` — one of:
  - `INITIAL` — first attempt at the step.
  - `WITH_GUIDANCE` — respawn after a design-decision escalation.
    `Guidance:` and `exploration_notes_echo` are populated.
  - `FIX_REVIEW_FINDINGS` — respawn to apply dimensional review
    findings on top of the existing commit. `findings:` is populated.
- `Guidance:` (only in `WITH_GUIDANCE`) — the user's chosen
  alternative + any additional direction.
- `exploration_notes_echo` (only in `WITH_GUIDANCE`) — the
  `exploration_notes` from the prior implementer's
  `DESIGN_DECISION_NEEDED` return, so the new implementer skips
  re-derivation.
- `findings:` (only in `FIX_REVIEW_FINDINGS`) — the synthesised
  dimensional-review findings the implementer must address.

---

## What the implementer does (sub-steps 1–3, expanded)

The orchestrator's per-step workflow has seven sub-steps; the
implementer owns sub-steps 1–3 below. Reading the step file and the
slim plan are **preconditions** to sub-step 1, not separate sub-steps.
Run sub-steps 1–3 in order, to completion, then emit the return
contract. Any detection rule in the next section may interrupt the
flow with an early return — in that case skip the remaining sub-steps
and emit the return contract instead of continuing.

**Preconditions.**

- Read the step file at `step_file_path`; locate the step at
  `step_index`; confirm intent and check the `**Risk:**` line. If
  `mode == FIX_REVIEW_FINDINGS`, also locate the prior commit's diff
  so the fixes land on top of it.
- Read the slim plan at `plan_slim_path` for strategic context. Read
  `design_path` only if the step requires it.

**Sub-step 1 — Implement the change.** Apply the existing project
rules:

- Defensive assertions where they cost nothing.
- **Reference-accuracy questions go through PSI** (callers,
  overrides, "is X still used?") per the rules in
  `~/.claude/CLAUDE.md` "Grep vs PSI". Grep is acceptable for
  orientation, filename globs, or unique string literals; PSI is
  required when a missed reference would corrupt a refactor.
- **Refactors that touch more than one reference site** route
  through the IDE refactoring engine via mcp-steroid, not raw
  `Edit` — see `~/.claude/CLAUDE.md` "Refactoring — IDE refactor
  vs raw Edit" for the routing table.
- **Single-test reruns** (`-Dtest=Foo#bar`) and **compile-fix loops**
  route through `steroid_execute_code` when mcp-steroid is
  reachable; full-suite runs and coverage profiles stay on Bash
  `./mvnw` per `~/.claude/CLAUDE.md` "Maven — when to route
  through mcp-steroid".
- The user-global preflight applies: `steroid_list_projects` once
  at the start of the spawn confirms the open project matches the
  working tree before any IDE-routed action; do not re-probe.
- **Do not reference workflow-internal identifiers** (`Track N`,
  `Step N`, finding IDs, iteration counters, or named-only plan
  invariants) in source code, Javadoc, test names, test
  descriptions, or the commit message — see
  [`conventions-execution.md`](conventions-execution.md) §2.3 for
  the full Ephemeral identifier rule and rewrite examples.

**Sub-step 2 — Add or update tests.** Run module tests, verify
Spotless on affected modules (`./mvnw -pl <module> spotless:apply`),
verify coverage thresholds (85% line / 70% branch on changed code
via the coverage gate command in §"Coverage gate command" below).
Wait for test results before proceeding — never start the commit
while a background test run is still streaming. For long-running
Maven builds (full `core` test suite or coverage profile), see
§"Pacing long-running tasks" below before deciding how to wait.

**Sub-step 3 — Stage explicit paths and commit** in one commit. No
`git add -A`. No `--amend`. Apply the project's commit-message
convention from `CLAUDE.md` (imperative summary under 50 chars, blank
line, detailed why) and the Ephemeral identifier rule from
[`conventions-execution.md`](conventions-execution.md) §2.3 (no
`Track N` / `Step N` / finding IDs / iteration counters in the
message body or subject). For `mode == FIX_REVIEW_FINDINGS`, prefix
the commit subject with `Review fix:` per
[`commit-conventions.md`](commit-conventions.md).

**Return.** Emit the structured result block (see §Return contract
below). The orchestrator parses the block; everything else in the
implementer's output is ignored.

The implementer **MUST NOT** modify the step file, the plan file, the
backlog, or any review file. All step-file mutations — episode write,
risk-line rewrite, `[x]` mark, Progress count update, retry/split row
inserts — are the orchestrator's responsibility.

### Pacing long-running tasks — do not use `ScheduleWakeup`

The implementer is spawned synchronously by the orchestrator via the
Agent tool. The orchestrator parses **one** structured return block
per spawn — the spawn either ends with a valid `RESULT: …` block or
the orchestrator treats the return as a contract violation.

`ScheduleWakeup` breaks this contract. It yields control back to the
caller with no `RESULT` block, so the orchestrator sees the
implementer return without a handoff — indistinguishable from a
crash. The implementer is also left **idle** between scheduled wakes
rather than actively running, so any subsequent SendMessage from the
orchestrator is required to resume work — at which point the
implementer's understanding of the prior in-flight Bash background
task may be stale (the runtime may have garbage-collected the
background task across the idle gap, leaving zero-byte output files
and no live process).

**Rule.** The implementer MUST NOT call `ScheduleWakeup`. Pacing is
the orchestrator's job, not the implementer's. The implementer runs
straight through sub-steps 1–3 and emits exactly one return block.

**For long-running Maven runs** — full `core` test suite, coverage
profile build, integration tests:

- Prefer **foreground** Bash with the Bash tool's `timeout`
  parameter set to the realistic upper bound (the parameter is in
  milliseconds and caps at 600 000 ms / 10 minutes — this is the
  Claude Code Bash-tool parameter, not the GNU `timeout` shell
  command; for builds that may exceed that, split into stages —
  e.g., compile first, then test, then coverage report — each stage
  under 10 min).
- If background is genuinely needed (e.g., to keep the implementer
  responsive to other work during a long build), use Bash
  `run_in_background` with the `Monitor` tool's "until-loop" pattern
  inside a single Bash invocation:
  `until grep -Eq "BUILD SUCCESS|BUILD FAILURE" "{logfile}"; do
  sleep 10; done` — the loop runs in one Bash call, the implementer
  waits for the loop's exit, and the runtime delivers a single
  completion notification rather than a sequence of wake-ups.
- Do not chain multiple short `sleep`s with `ScheduleWakeup` between
  them. Each `ScheduleWakeup` is a yield-and-idle, not a wait.

If a build genuinely exceeds the realistic foreground budget for the
implementer (rare — only the full `verify -P ci-integration-tests`
or large coverage runs), return `RESULT: FAILED` with
`recommended_action: split` and let the orchestrator decide whether
to break the step into smaller, individually-verifiable pieces.

### When the failure mode is opaque — consider an IDE debug session

If a test failure is **opaque from the stack trace + test output** —
concurrency hang, unexpected branch taken, mid-operation state
corruption, "wrong value at line N and I don't know why" — and
mcp-steroid is reachable per the SessionStart hook with the project
open in the IDE, fetch `mcp-steroid://debugger/overview` via
`steroid_fetch_resource` and run an IntelliJ debug session: set a
breakpoint, debug-run the failing test, wait for suspend, evaluate
the relevant expressions/fields, step over as needed.

**Skip the debugger** for clean assertion failures, compile errors,
or anything where the stack trace already names the bug — adding a
debugger session there is pure overhead. Skip it also when
mcp-steroid is unreachable; fall back to print-debugging or an extra
assertion in the test.

After the debug session yields a root cause, capture the finding in
the `EPISODE_DRAFT.what_was_discovered` field (or in the `FAILURE`
block if the run still ends as `FAILED`) and proceed with the
implementation.

---

## Detection rules — return early without committing

The implementer **MUST** stop and return early — without committing —
in three cases. The orchestrator decides what happens next; the
implementer never escalates directly to the user.

**Always revert before returning.** Whichever case fires below, the
implementer must roll back its in-progress changes so the orchestrator
observes a clean tree at the implementer's `HEAD` — but it must
preserve any untracked files that pre-existed the spawn. The
orchestrator keeps its working state (step files, review reports,
the design document, the implementation backlog, baselines) as
**untracked-on-disk files** under `docs/adr/<dir>/`. Those files
must survive any implementer revert; otherwise the orchestrator
loses cross-spawn state. The required sequence:

1. **Snapshot pre-existing untracked files at the start of every
   spawn**, before any code change. This is the first action of
   sub-step 1, before reading the step file or making any edit:

   ```bash
   git ls-files --others --exclude-standard | LC_ALL=C sort \
     > /tmp/claude-impl-preexisting-untracked-$PPID.txt
   ```

   `LC_ALL=C` keeps the sort byte-ordered so the later `comm -13`
   (which requires its inputs sorted under the same collation) is
   reliable regardless of the implementer's locale.

   The snapshot reflects the world the orchestrator handed you.

2. **At revert time**, discard tracked-file changes and the index:

   ```bash
   git reset --hard HEAD
   ```

3. **Then surgically remove only the untracked files the implementer
   created** in this spawn — `comm -13 <pre> <post>` lists files
   present in the post-snapshot but absent from the pre-snapshot:

   ```bash
   git ls-files --others --exclude-standard | LC_ALL=C sort \
     > /tmp/claude-impl-post-untracked-$PPID.txt
   LC_ALL=C comm -13 \
     /tmp/claude-impl-preexisting-untracked-$PPID.txt \
     /tmp/claude-impl-post-untracked-$PPID.txt \
     | while IFS= read -r file; do rm -v -- "$file"; done
   ```

   The `while IFS= read -r` loop handles paths with spaces and is
   portable across GNU/BSD `xargs` differences. The loop is a
   no-op when `comm` produces no output, so the empty-diff case is
   handled implicitly.

**Do NOT run `git clean -fd` (or `-fdx`).** It indiscriminately
removes every untracked file in the worktree — including the
orchestrator's workflow state — and the orchestrator depends on
those files persisting across spawn boundaries. Past versions of
this rulebook used `git clean -fd`; that was a bug that destroyed
the orchestrator's cross-spawn state on every early-return. The
snapshot-and-diff sequence above is the supported replacement.

Use `git reset --hard HEAD` rather than `git checkout -- .` for the
tracked half — the latter leaves a dirty index if the implementer
had staged files before bailing.

The semantic scope of the revert differs by mode (see §"Mode-specific
scope of the local revert" below), but the command sequence above
is the same. The orchestrator's pre-revert assertion in
[`step-implementation.md`](step-implementation.md) §Post-Commit
Handlers depends on this — a dirty tree at hand-off is a contract
violation, and an orphaned implementer-created untracked file at
hand-off would also be a contract violation (it would interfere with
the next spawn's pre-snapshot).

### Design decision detected

A **design decision** is a choice between alternatives that affects
architecture, public API shape, data structures, algorithms, or
behavioural semantics beyond what the plan and Decision Records
prescribe. Examples:

- The plan says "add a histogram" but is silent on whether the
  histogram is per-page or per-cluster.
- A new abstraction or interface needs to be introduced that wasn't
  anticipated in the plan.
- Multiple plausible approaches differ in their public-API surface or
  serialised form.

What is **NOT** a design decision (handle autonomously):

- Mechanical code changes with one obvious approach.
- Naming choices that follow existing codebase conventions.
- Test structure and test case selection.
- Implementation details fully prescribed by the plan or by Decision
  Records in `adr.md` / the plan file.

When a design decision is detected, run the snapshot-and-diff revert
sequence at the top of this section (snapshot pre-existing untracked
files first, then `git reset --hard HEAD`, then `comm -13` against a
post-snapshot to surgically remove only files this spawn created),
then return
`RESULT: DESIGN_DECISION_NEEDED` with `DESIGN_DECISION` populated: `context`,
`alternatives` (≥2, with pros/cons), `recommendation`, and
`exploration_notes` summarising what was already investigated (API
shape, call sites surveyed, candidate approaches ruled out — these
notes survive the revert because they go in the return block, not the
working tree). The orchestrator presents the alternatives to the user
and respawns the implementer with `mode=WITH_GUIDANCE` and the
`exploration_notes_echo` populated, so the new implementer does not
redo the exploration.

### Risk upgrade required

Implementation reveals that the step is more invasive than its
tagged risk — for example, the "trivial refactor" tagged `low`
turns out to require lock-ordering changes, or the "internal helper
addition" tagged `medium` actually changes a public-API serialized
form.

Run the snapshot-and-diff revert sequence at the top of this section
(snapshot pre-existing untracked files first, then `git reset --hard
HEAD`, then `comm -13` against a post-snapshot to surgically remove
only files this spawn created), then return `RESULT: RISK_UPGRADE_REQUESTED` with `RISK_UPGRADE` populated:
`from`, `to`, `category` (one of: `concurrency`, `crash-safety`,
`public-API`, `security`, `architecture`, `performance-hot-path` —
see [`risk-tagging.md`](risk-tagging.md) for the full criteria), and
`evidence` (a short factual statement of what was discovered).

The implementer **never writes to the step file** to apply the
upgrade. The orchestrator rewrites the `**Risk:**` line and
respawns with `mode=INITIAL`. Downgrades mid-Phase B are not
permitted — once a step has been planned at a given risk level, the
implementer cannot self-relax review pressure.

### Fundamental failure

A failure the step's scope cannot address — wrong API assumption,
tests cannot be made to pass, coverage cannot be met, architectural
problem revealed by the implementation, repeated test failures with
a root cause outside the step's surface area.

Run the snapshot-and-diff revert sequence at the top of this section
(snapshot pre-existing untracked files first, then `git reset --hard
HEAD`, then `comm -13` against a post-snapshot to surgically remove
only files this spawn created), then return `RESULT: FAILED` with `FAILURE` populated: `what_was_attempted`, `why_it_failed`,
`impact_on_remaining_steps`, and `recommended_action` (`retry` |
`split` | `escalate`).

The orchestrator writes the failed episode to the step file, inserts
retry/split rows, and runs the two-failure detection on its side.

### Mode-specific scope of the local revert

The snapshot-and-diff revert sequence at the top of this section
(snapshot, `git reset --hard HEAD`, `comm -13` cleanup) resets to
the **current commit** and removes only the untracked artefacts
this spawn created — not the orchestrator's pre-existing untracked
state, and not changes from any prior commit. The sequence is the
same for every early-return case (design decision, risk upgrade,
fundamental failure) and every mode, but the **semantic scope** of
what gets reverted differs:

- **`mode=INITIAL`** or **`mode=WITH_GUIDANCE`**: `HEAD` is the
  step's pre-implementation state (the orchestrator's `step_base_commit`).
  The reset returns the working tree to that pristine state — the
  orchestrator sees a clean tree at `step_base_commit`. Correct.
- **`mode=FIX_REVIEW_FINDINGS`**: `HEAD` is the prior `SUCCESS`
  commit (the original implementer commit, possibly with earlier
  `Review fix:` commits on top from prior dim-review iterations).
  The implementer's reset clears only its in-progress fix attempt;
  the prior commits stay on disk. Rolling those back is the
  **orchestrator's** responsibility — see
  [`step-implementation.md`](step-implementation.md) §Post-Commit
  Handlers. The implementer must not run `git reset --hard
  step_base_commit` or `git revert` to undo prior commits; that
  would silently destroy work the orchestrator may need.

The implementer is therefore symmetric in code (the snapshot-and-diff
revert sequence regardless of mode and regardless of which
early-return case fired) but the orchestrator-side cleanup differs:
pre-commit modes need no further work; post-commit mode requires
the orchestrator's post-commit rollback to remove the prior step
commits as well.

---

## Return contract

The implementer's return is a **single structured block**. The
orchestrator parses it; everything else in the implementer's output is
informational and ignored.

```
RESULT: SUCCESS | DESIGN_DECISION_NEEDED | RISK_UPGRADE_REQUESTED | FAILED

COMMIT: <sha or empty>
FILES_TOUCHED:
- <path> (new|modified)
- ...

TEST_SUMMARY:
  module: <module name>
  passed: <N> / <N>
  line_coverage_changed: <%>
  branch_coverage_changed: <%>
  spotless_applied: yes | no

TOOLING_NOTES:
  mcp_steroid: reachable | NOT_reachable
  maven_cycles: <N>
  ide_refactor_used: yes | no
  psi_audits: <N>
  notes: <one-liner if anything unusual happened, otherwise "none">

EPISODE_DRAFT:
  what_was_done: |
    <factual summary, 2–6 sentences>
  what_was_discovered: |
    <or "none">
  what_changed_from_plan: |
    <or "none". Name affected future steps if any.>
  critical_context: |
    <or "none". Use sparingly.>

CROSS_TRACK_HINTS: |
  <free-form: anything that might affect upcoming tracks. The
  orchestrator consumes this in sub-step 5. Empty is fine.>

# --- Conditional sections, only present when relevant ---

DESIGN_DECISION:                  # only if RESULT == DESIGN_DECISION_NEEDED
  context: ...
  alternatives:
    - name: ...; pros: ...; cons: ...
    - name: ...; pros: ...; cons: ...
  recommendation: ...
  exploration_notes: ...          # echoed back on respawn

RISK_UPGRADE:                     # only if RESULT == RISK_UPGRADE_REQUESTED
  from: low | medium
  to: medium | high
  category: concurrency | crash-safety | public-API | security |
            architecture | performance-hot-path
  evidence: ...

FAILURE:                          # only if RESULT == FAILED
  what_was_attempted: |
  why_it_failed: |
  impact_on_remaining_steps: |
  recommended_action: retry | split | escalate
```

### Field rules

- `RESULT` is the dispatch tag — the orchestrator routes on it. Pick
  exactly one value.
- `COMMIT` is empty when `RESULT != SUCCESS`. On `SUCCESS`, it is the
  SHA of the implementer's commit (or, for `mode=FIX_REVIEW_FINDINGS`,
  the SHA of the `Review fix:` commit applied on top).
- `FILES_TOUCHED` lists every path in the diff with a `(new)` or
  `(modified)` annotation. On `FAILED`, list paths the implementer
  attempted to modify even though they are now reverted.
- `TEST_SUMMARY` is required on `SUCCESS`. On `FAILED`, populate what
  is meaningful (e.g., last test run's pass count) and use `n/a`
  otherwise. On `DESIGN_DECISION_NEEDED` and `RISK_UPGRADE_REQUESTED`,
  the implementer has typically not run tests yet — every field may
  be `n/a`.
- `EPISODE_DRAFT` is populated on `SUCCESS` only. The orchestrator
  finalises it (merging in cross-track-impact-check observations from
  sub-step 5) before writing the episode to the step file. On
  `FAILED`, the orchestrator uses `FAILURE` instead of
  `EPISODE_DRAFT`. On `DESIGN_DECISION_NEEDED` and
  `RISK_UPGRADE_REQUESTED`, omit `EPISODE_DRAFT` — the eventual
  respawn produces the authoritative draft once the step actually
  completes.
- `CROSS_TRACK_HINTS` is a free-form note for the orchestrator's
  cross-track impact check (sub-step 5). Anything that might affect
  upcoming tracks goes here — invariant weakened, new dependency
  surfaced, API shape clarified differently than expected. Empty is
  fine; the orchestrator does not require a hint per step.

---

## Tooling discipline

The implementer relies on the existing rules verbatim — do not
duplicate the routing tables here. Pointers:

- **PSI vs grep, IDE refactoring, Maven routing**: `~/.claude/CLAUDE.md`
  sections "MCP Steroid", "Grep vs PSI — when to switch", "Maven —
  when to route through mcp-steroid", "Refactoring — IDE refactor vs
  raw Edit".
- **Project conventions and PSI requirement for load-bearing audits**:
  [`conventions.md`](conventions.md) §1.4 *Tooling discipline*.
- **Ephemeral identifier rule** for code, tests, and commit messages:
  [`conventions-execution.md`](conventions-execution.md) §2.3.
- **Risk categories** referenced in `RISK_UPGRADE.category`:
  [`risk-tagging.md`](risk-tagging.md). The implementer reads only
  the category names; full criteria and override rules stay in
  `risk-tagging.md` for the orchestrator and Phase A.
- **Commit-message prefixes** for `mode=FIX_REVIEW_FINDINGS`:
  [`commit-conventions.md`](commit-conventions.md).

---

## Coverage gate command

The canonical coverage check after running tests with the `coverage`
profile is:

```bash
python3 .github/scripts/coverage-gate.py \
  --line-threshold 85 \
  --branch-threshold 70 \
  --compare-branch origin/develop \
  --coverage-dir .coverage/reports
```

Use this script — never compute coverage by hand — because it
contains special-case logic (e.g., excluding Java `assert` lines
from line and branch coverage). The thresholds and rationale come
from the project `CLAUDE.md` §Testing → Coverage verification.
