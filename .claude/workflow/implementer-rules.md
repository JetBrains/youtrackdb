# Implementer — Rulebook

This document is the rulebook for the **implementer sub-agent**
spawned by the orchestrator at two distinct levels:

- **`level: step`** — the per-step implementer spawned by the Phase B
  orchestrator (`step-implementation.md`). Performs sub-steps 1–3 of
  step implementation (implement code, write/run tests, commit) and
  returns a structured handoff. The Phase B orchestrator owns
  sub-steps 4–7 and all session-level decisions.
- **`level: track`** — the per-iteration implementer spawned by the
  Phase C orchestrator (`track-code-review.md`) to apply track-level
  code-review fixes (and user-requested fixes during track
  completion). Operates only in `mode=FIX_REVIEW_FINDINGS` or
  `mode=WITH_GUIDANCE` — the cumulative track diff already exists at
  HEAD, so there is no `INITIAL` mode at this level. The Phase C
  orchestrator owns the review fan-out, gate verification, plan
  corrections, and track completion.

Most of the rulebook is shared across both levels. Where the two
diverge — diff target, allowed return values, episode draft shape — the
divergence is called out inline as "Phase B (level=step)" /
"Phase C (level=track)" rather than split into parallel sections.

The rulebook for the orchestrator side — Phase B startup, per-step
orchestration loop, dimensional review fan-out, episode finalisation,
Phase C review loop, track completion — lives in
[`step-implementation.md`](step-implementation.md) and
[`track-code-review.md`](track-code-review.md). Together with this
rulebook they are the only entry points; everything else is loaded on
demand.

---

## Loading discipline

This file is read **only by the implementer sub-agent** on each spawn,
at either level. Orchestrators do not load it. The orchestrator
specifications — including the prompt template and how this rulebook
is referenced from each spawn — live in
[`step-implementation.md`](step-implementation.md) (Phase B,
`level=step`) and [`track-code-review.md`](track-code-review.md)
(Phase C, `level=track`).

The implementer's environment auto-loads the user-global rules in
`~/.claude/CLAUDE.md` (cross-project conventions only — concurrent
agent file isolation, context-window monitor, etc.) and the project
rules in the repo's `CLAUDE.md` (which owns the MCP Steroid / PSI /
Maven / refactoring rules). Beyond those and this file, the
implementer reads the step file, the slim plan snapshot, and
`design.md` (only if the step requires it).

---

## Inputs the orchestrator passes on each spawn

The implementer prompt has a **stable static prefix** (workflow context
+ rulebook path + project paths) followed by **per-spawn variable
inputs**.

**Stable inputs** (same across spawns within a session):

- `repo_root` — absolute path to the working tree.
- `plan_slim_path` — `/tmp/claude-code-plan-slim-$PPID.md`.
- `step_file_path` — `docs/adr/<dir-name>/_workflow/tracks/track-<N>.md`.
- `design_path` — `docs/adr/<dir-name>/_workflow/design.md` (read on demand only).

**Variable inputs** (per spawn):

- `level` — `step` (Phase B per-step implementation) or `track` (Phase
  C per-iteration fix application). Selects which fields below are
  populated and which `mode` values are valid.
- `base_commit` — SHA recorded at Phase B startup. Used at both levels:
  Phase B reads it for orchestrator bookkeeping; Phase C also derives
  the cumulative track diff from `git diff {base_commit}..HEAD`.
- `mode` — one of:
  - `INITIAL` — first attempt at the step. Valid only at `level=step`.
  - `WITH_GUIDANCE` — respawn after a design-decision escalation.
    Valid at both levels. `Guidance:` and `exploration_notes_echo`
    are populated.
  - `FIX_REVIEW_FINDINGS` — respawn to apply review findings on top
    of the existing HEAD. Valid at both levels. `findings:` is
    populated. At `level=step` the fixes apply on top of the prior
    step's commit; at `level=track` they apply on top of the most
    recent commit on the track (the cumulative track HEAD).
- `Guidance:` (only in `WITH_GUIDANCE`) — the user's chosen
  alternative + any additional direction.
- `exploration_notes_echo` (only in `WITH_GUIDANCE`) — the
  `exploration_notes` from the prior implementer's
  `DESIGN_DECISION_NEEDED` return, so the new implementer skips
  re-derivation.
- `findings:` (only in `FIX_REVIEW_FINDINGS`) — the synthesised
  review findings the implementer must address. At `level=step` these
  are dimensional-review findings against the step's commit; at
  `level=track` they are cross-step findings synthesised from the
  track-level review fan-out.

**Step-level-only inputs** (populated only when `level=step`):

- `step_index` — the integer N identifying which step in the file is
  being implemented.
- `step_description` — copied inline from the step file's
  `- [ ] Step: …` line.
- `risk_tag` — `low` | `medium` | `high`.

At `level=track` these three fields are absent. The track-level
implementer reads the step file for cross-step context (episodes,
risk tags, descriptions) but does not focus on a single step.

**Mode/level validity matrix:**

| Level | `INITIAL` | `WITH_GUIDANCE` | `FIX_REVIEW_FINDINGS` |
|---|---|---|---|
| `step` | yes | yes | yes |
| `track` | **no** (the cumulative diff already exists at HEAD) | yes | yes |

---

## What the implementer does (sub-steps 1–3, expanded)

The Phase B orchestrator's per-step workflow has seven sub-steps and
the Phase C orchestrator's review iteration is a parallel three-step
loop; the implementer owns sub-steps 1–3 at both levels — read the
target, change/fix the code, run tests + spotless + coverage gate,
commit. Reading the step file and the slim plan are **preconditions**
to sub-step 1, not separate sub-steps. Run sub-steps 1–3 in order, to
completion, then emit the return contract. Any detection rule in the
next section may interrupt the flow with an early return — in that
case skip the remaining sub-steps and emit the return contract
instead of continuing.

**Preconditions.**

- Read the step file at `step_file_path`.
  - At `level=step`: locate the step at `step_index`; confirm intent
    and check the `**Risk:**` line. If `mode == FIX_REVIEW_FINDINGS`,
    also locate the prior commit's diff so the fixes land on top of
    it.
  - At `level=track`: read the file for cross-step context — the
    `## Description` section (track intent), the per-step `**Risk:**`
    lines (focal points), and the step episodes (what each step did
    and discovered). Then read `git diff {base_commit}..HEAD` to load
    the cumulative track diff that the findings refer to. There is no
    `step_index` to focus on.
- Read the slim plan at `plan_slim_path` for strategic context. Read
  `design_path` only if the change requires it.

**Sub-step 1 — Implement the change.** Apply the existing project
rules:

- Defensive assertions where they cost nothing.
- **Reference-accuracy questions go through PSI** (callers,
  overrides, "is X still used?") per the rules in the project's
  `CLAUDE.md` § MCP Steroid → "Grep vs PSI — when to switch". Grep
  is acceptable for orientation, filename globs, or unique string
  literals; PSI is required when a missed reference would corrupt a
  refactor.
- **Refactors that touch more than one reference site** route
  through the IDE refactoring engine via mcp-steroid, not raw
  `Edit` — see the project's `CLAUDE.md` § MCP Steroid →
  "Refactoring — IDE refactor vs raw Edit" for the routing table.
  The `change-signature`, `extract-interface`, `pull-up-members`,
  and `push-down-members` recipes (catalogued in `conventions.md`
  §1.4 *Recipes*) cover the common cases.
- **Multi-site / multi-file literal-text edits that don't need
  symbol resolution** (recurring string literal, repeated boilerplate
  across call sites, Javadoc tag swap) route through the dedicated
  `steroid_apply_patch` tool rather than 2+ chained native `Edit`
  calls — the native tool bypasses IntelliJ and leaves PSI / search
  indices stale. Single-site edits stay on `Edit`. See
  `conventions.md` §1.4 *Other mcp-steroid routes* and the project's
  `.claude/docs/mcp-steroid/skills.md` → `apply-patch-tool-description`
  for the rule.
- **Single-test reruns** (`-Dtest=Foo#bar`) and **compile-fix loops**
  route through `steroid_execute_code` when mcp-steroid is
  reachable; full-suite runs and coverage profiles stay on Bash
  `./mvnw` per the project's `CLAUDE.md` § MCP Steroid → "Maven —
  when to route through mcp-steroid". When an IDE-routed test run
  fails, the `test/failure-details` and `test/statistics` recipes
  (see `conventions.md` §1.4 *Recipes*) read structured per-test
  outcomes back from the IDE without re-parsing surefire XML.
- **Before deleting a method, field, or class** that may still be
  referenced anywhere — load the `safe-delete` recipe (see
  `conventions.md` §1.4 *Recipes*) and run it in dry-run mode
  before the deletion. The recipe enumerates remaining production
  callers via PSI; an empty list is the green light to proceed.
- The session-start preflight from the project's `CLAUDE.md` § MCP
  Steroid applies: `steroid_list_projects` once at the start of the
  spawn confirms the open project matches the working tree before
  any IDE-routed action; do not re-probe.
- **Do not reference workflow-internal identifiers** (`Track N`,
  `Step N`, finding IDs, iteration counters, or named-only plan
  invariants) in source code, Javadoc, test names, or test
  descriptions — see
  [`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md) for
  the full Ephemeral identifier rule and rewrite examples (the §2.3
  stub in `conventions-execution.md` is a quick recap with the
  self-check grep).
  Branch-only commit messages are exempt (they are squashed away on
  merge); the rule applies to durable content only.

**Sub-step 2 — Add or update tests.** Run module tests, verify
Spotless on affected modules (`./mvnw -pl <module> spotless:apply`),
verify coverage thresholds (85% line / 70% branch on changed code
via the coverage gate command in §"Coverage gate command" below).
Wait for test results before proceeding — never start the commit
while a Maven run is still in flight. **Run every Maven invocation
in foreground** (no `run_in_background: true`) per
§"Pacing long-running tasks — foreground only" below; that section
also defines the test-additive shortcut that skips the
coverage-profile build when the step adds no production-source
changes.

**Sub-step 3 — Stage explicit paths, commit, and push** in one commit.
No `git add -A`. No `--amend`. Apply the project's commit-message
convention from `CLAUDE.md` (imperative summary under 50 chars, blank
line, detailed why). The Ephemeral identifier rule
([`conventions-execution.md`](conventions-execution.md) §2.3) covers
durable content only — branch-only commit messages may cite
`Track N` / `Step N` / finding IDs / iteration counters when it
makes the commit log easier to follow, since the squash-merge
collapses them away (see
[`commit-conventions.md`](commit-conventions.md) "Branch-only
commit messages may cite workflow-internal identifiers"). For
`mode == FIX_REVIEW_FINDINGS` or `mode == WITH_GUIDANCE` with a
fix-applied outcome at `level=track`, prefix the commit subject with
`Review fix:` per [`commit-conventions.md`](commit-conventions.md);
prefer describing the fix by what changed (behavior, file, class)
over citing a finding ID, but a finding-ID reference is permitted
when it aids review. The same `Review fix:` prefix applies at both
levels — Phase B Resume distinguishes Phase B vs Phase C fix commits
by **position** (Phase C fix commits appear strictly after the last
episode commit, since Phase B is fully done before Phase C runs);
see [`commit-conventions.md`](commit-conventions.md) and
[`step-implementation-recovery.md`](step-implementation-recovery.md)
§Resume-side commit-pattern reference.

**After the commit lands, run `git push` immediately** per
[`commit-conventions.md`](commit-conventions.md) § Push every commit.
The implementer is responsible for the push; the orchestrator does
not push on the implementer's behalf. Inspect the `git push` exit
code: a successful push is a precondition for emitting
`RESULT: SUCCESS` (see §Return contract `COMMIT` field rule below).
On push failure (the shapes enumerated in
[`commit-conventions.md`](commit-conventions.md) § Push failure
handling), do **not** emit `SUCCESS`. Surface the situation via
`RESULT: FAILED` with `FAILURE.recommended_action: retry`,
`FAILURE.failure_class: push_only`, and `FAILURE.why_it_failed`
naming the push failure shape. The `failure_class: push_only` flag
tells the orchestrator that the commit content is fine and only the
push relationship to `origin` failed — at `mode=FIX_REVIEW_FINDINGS`
the orchestrator skips the rollback that would otherwise revert the
implementer's good commit (see
[`step-implementation-recovery.md`](step-implementation-recovery.md)
§`rollback_and_handle_failure`).

**Return.** Emit the structured result block (see §Return contract
below). The orchestrator parses the block; everything else in the
implementer's output is ignored.

The implementer **MUST NOT** modify the step file or the plan file.
At `level=step` all step-file
mutations — episode write, risk-line rewrite, `[x]` mark, Progress
count update, retry/split row inserts — are the Phase B
orchestrator's responsibility. At `level=track` all plan and step-file
mutations — Progress section iteration count, plan corrections from
deferred findings (which may add a new step file or update an
existing one), track episode, `[x]` mark on the plan track entry —
are the Phase C orchestrator's responsibility.

### Pacing long-running tasks — foreground only

The implementer is spawned synchronously by the orchestrator via the
Agent tool. The orchestrator parses **one** structured return block
per spawn — the spawn either ends with a valid `RESULT: …` block or
the orchestrator treats the return as a contract violation.

Two patterns break this contract by leaving the implementer **idle**
between events: `ScheduleWakeup`, and starting a Maven build via
Bash `run_in_background: true` and then polling for completion. In
both cases the runtime can drop the wake-up / completion
notification across the idle gap — the implementer's turn ends
without emitting a `RESULT` block, indistinguishable from a crash,
and any subsequent SendMessage from the orchestrator finds the
background task long gone (zero-byte output, no live process). Past
sessions repeatedly stranded on this exact pattern.

**Rules.** The implementer:

- MUST NOT call `ScheduleWakeup`.
- MUST NOT start Maven invocations with Bash `run_in_background: true`.
- MUST NOT chain `sleep` / monitor poll loops waiting for a
  background task to finish.
- MUST NOT use **self-referential `pgrep -f` poll loops**. A pattern
  like `until ! pgrep -f "surefire"; do sleep 5; done` matches its
  own shell because the shell's argv contains the literal
  `surefire`, so the loop never terminates and runs forever after
  the implementer exits — exactly the runaway poll observed in the
  Track-18 incident (2026-05-07). If a poll loop is genuinely
  unavoidable, exclude the shell's own pid (`pgrep -f surefire |
  grep -v $$`) or prefer `tail --pid=N` / `wait`. The cleanest
  path is to not use a poll loop at all — foreground Bash already
  blocks until the test run finishes, which is the rule above.

Pacing is the orchestrator's job, not the implementer's. The
implementer runs straight through sub-steps 1–3 and emits exactly
one return block.

**For long-running Maven runs** — full `core` test suite, coverage
profile build, integration tests:

- Use **foreground** Bash with the Bash tool's `timeout` parameter
  set to the realistic upper bound (the parameter is in
  milliseconds and caps at 600 000 ms / 10 minutes — this is the
  Claude Code Bash-tool parameter, not the GNU `timeout` shell
  command). Targeted reruns (`-Dtest='Foo,Bar'`) and module-scoped
  test runs (`./mvnw -pl core test`) finish well inside this
  budget.
- For builds that genuinely exceed the foreground budget, **split
  into foreground stages** — e.g., `./mvnw -pl core compile` first,
  then `./mvnw -pl core test`, then a separate coverage-report
  invocation — each stage under 10 min. The split keeps every
  invocation foreground; do not work around the timeout by
  switching to `run_in_background`.
- **Test-additive spawns skip the coverage-profile build entirely.**
  When the spawn (a step at `level=step` or a fix iteration at
  `level=track`) adds only test code (no production-source changes
  in `git diff origin/develop -- '**/src/main/**'`), the coverage gate
  trivially passes on changed lines because there are no changed
  production lines. Run the targeted tests in foreground, confirm
  Spotless, and commit — record the gate as `n/a (test-additive)`
  in the `TEST_SUMMARY` and let the track's final-verification step
  pick up per-package totals from a single full coverage run. Note
  that the check is against `origin/develop`, i.e. the **cumulative**
  branch diff. At `level=track` this shortcut therefore only fires
  for test-only tracks (where Phase B itself touched no production
  source); for any track whose Phase B steps added production code,
  the cumulative diff is non-empty and the implementer must run the
  full coverage profile build even when the iteration's own diff is
  test-only.
- **Prefer targeted `-Dtest=…` re-runs during fix iteration.** When
  applying review findings (`mode=FIX_REVIEW_FINDINGS` at either
  level), re-run only the test classes the fix actually touched —
  `./mvnw -pl <module> test -Dtest='Foo,Bar,Baz'` rather than the
  full module suite. Targeted re-runs finish in seconds, fit
  comfortably inside the foreground Bash budget, and keep the
  implementer's context lean: a 1500-test module re-run on a
  failing assertion can dump tens of thousands of tokens of stack
  traces into the conversation, and the Track-18 implementer
  exhausted its message budget on exactly this pattern
  (2026-05-07). Reserve the full module suite for one final
  pre-commit verification, and only when the cumulative diff
  genuinely justifies it; routine fix iterations do not.
- **Route targeted re-runs through `steroid_execute_code` when
  mcp-steroid is reachable.** Per the project's `CLAUDE.md` § MCP
  Steroid → "Maven — when to route through mcp-steroid",
  single-test re-runs and short targeted lists belong on the IDE
  route. The `test/failure-details` and `test/statistics` recipes
  (catalogued in `conventions.md` §1.4 → "Recipes" subsection)
  return structured
  per-test outcomes from `RunContentManager` rather than streaming
  surefire stdout into the conversation — typically a 10–25× context
  saving on a failing run, which is the lever that prevents the
  message-budget exhaustion mode the targeted-rerun rule above is
  also targeting. Full-module verification, coverage-profile builds,
  and integration tests stay on Bash per the project rule, even
  when the IDE is reachable.

If even a staged sequence cannot fit the foreground budget (rare —
only large `-P ci-integration-tests` runs or full multi-module
coverage on a slow host), return `RESULT: FAILED` with
`recommended_action: split` (at `level=step`) or
`recommended_action: escalate` (at `level=track`, where step-level
splits do not apply) and let the orchestrator decide how to proceed.

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
observes a clean tree at the implementer's `HEAD` — and it must
preserve any untracked files that pre-existed the spawn (test
fixtures, scratch logs, anything outside the workflow's tracked
state).

The orchestrator's working state — step files, review reports,
design document, baselines — is **tracked under
`docs/adr/<dir>/_workflow/`** and committed by the orchestrator
on the appropriate cadence (see `commit-conventions.md` § Push every
commit). The orchestrator commits any pending workflow-file changes
**before** spawning the implementer, so `HEAD` at spawn time
already reflects the orchestrator's intended state. That means the
implementer's `git reset --hard HEAD` rolls back tracked-file
changes only as far as the most recent commit — which is the
state the orchestrator wants to be in if the implementer bails.

The required sequence (run on every early-return case below — design
decision, risk upgrade, fundamental failure):

1. **Snapshot pre-existing untracked files at the start of every
   spawn**, before any code change. This is the first action of
   sub-step 1, before reading the step file or making any edit:

   ```bash
   git -c core.quotepath=false ls-files --others --exclude-standard \
     | LC_ALL=C sort \
     > /tmp/claude-impl-preexisting-untracked-$PPID.txt
   ```

   `LC_ALL=C` keeps the sort byte-ordered so the later `comm -13`
   (which requires its inputs sorted under the same collation) is
   reliable regardless of the implementer's locale.
   `core.quotepath=false` prevents Git from C-escaping non-ASCII
   bytes in filenames; without it `comm -13` would compare quoted
   strings to unquoted ones and the later `rm` would fail because
   the path doesn't literally exist on disk.

   The snapshot reflects the world the orchestrator handed you.

2. **At revert time**, discard tracked-file changes and the index:

   ```bash
   git reset --hard HEAD
   ```

3. **Then surgically remove only the untracked files the implementer
   created** in this spawn — `comm -13 <pre> <post>` lists files
   present in the post-snapshot but absent from the pre-snapshot:

   ```bash
   git -c core.quotepath=false ls-files --others --exclude-standard \
     | LC_ALL=C sort \
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
[`step-implementation-recovery.md`](step-implementation-recovery.md)
§Post-Commit Handlers depends on this — a dirty tree at hand-off is a contract
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

### Risk upgrade required (level=step only)

Implementation reveals that the step is more invasive than its
tagged risk — for example, the "trivial refactor" tagged `low`
turns out to require lock-ordering changes, or the "internal helper
addition" tagged `medium` actually changes a public-API serialized
form.

**This case is `level=step` only.** At `level=track` there is no
per-step risk to upgrade — risk tags are locked at the end of
Phase B per [`risk-tagging.md`](risk-tagging.md) §Risk locking.
Returning `RESULT: RISK_UPGRADE_REQUESTED` from a `level=track`
spawn is a contract violation; the orchestrator surfaces the
return to the user instead of dispatching. If applying a Phase C
fix surfaces an issue that genuinely demands re-running an earlier
step's implementation under heavier review, return `RESULT: FAILED`
with `recommended_action: escalate` and let the orchestrator
trigger inline replanning.

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
`impact_on_remaining_steps`, and `recommended_action`. Allowed values
for `recommended_action` are level-conditional:

- At `level=step`: `retry` | `split` | `escalate`.
- At `level=track`: `retry` | `escalate` only. **`split` is
  forbidden** — step-splitting is a Phase B planning operation
  against per-step risk tags and has no analogue at Phase C, where
  the failure is against the cumulative track diff. Returning
  `recommended_action: split` from a `level=track` spawn is a
  contract violation; the orchestrator surfaces the return to the
  user instead of dispatching.

The orchestrator writes the failed episode to the step file, inserts
retry/split rows, and runs the two-failure detection on its side
(`level=step` only — Phase C does not insert retry/split rows).

### Mode-specific scope of the local revert

The snapshot-and-diff revert sequence at the top of this section
(snapshot, `git reset --hard HEAD`, `comm -13` cleanup) resets to
the **current commit** and removes only the untracked artefacts
this spawn created — not the orchestrator's pre-existing untracked
state, and not changes from any prior commit. The sequence is the
same for every early-return case (design decision, risk upgrade,
fundamental failure) and every mode, but the **semantic scope** of
what gets reverted differs:

- **`level=step`, `mode=INITIAL`** or **`mode=WITH_GUIDANCE`**: `HEAD`
  is the step's pre-implementation state (the orchestrator's
  `step_base_commit`). The reset returns the working tree to that
  pristine state — the orchestrator sees a clean tree at
  `step_base_commit`. Correct.
- **`level=step`, `mode=FIX_REVIEW_FINDINGS`**: `HEAD` is the prior
  `SUCCESS` commit (the original implementer commit, possibly with
  earlier `Review fix:` commits on top from prior dim-review
  iterations). The implementer's reset clears only its in-progress
  fix attempt; the prior commits stay on disk. Rolling those back
  is the Phase B **orchestrator's** responsibility — see
  [`step-implementation-recovery.md`](step-implementation-recovery.md)
  §Post-Commit Handlers. The implementer must not run `git reset --hard
  step_base_commit` or `git revert` to undo prior commits; that
  would silently destroy work the orchestrator may need.
- **`level=track`, `mode=FIX_REVIEW_FINDINGS`** or **`mode=WITH_GUIDANCE`**:
  `HEAD` is the most recent commit on the track. At iteration 1 this
  is the last episode commit from Phase B (or, if findings were
  deferred, the `Apply plan corrections` Workflow update commit
  sitting on top of it). At iterations 2 and 3 it is the prior
  iteration's `Record Phase C iteration N` Workflow update commit,
  which itself sits on top of that iteration's `Review fix:` commit
  (the orchestrator records Progress as a Workflow update commit
  immediately after each successful fix iteration; see
  [`track-code-review.md`](track-code-review.md) §Review loop). The
  implementer commits on top of HEAD whatever it is and does not
  need to inspect the prior commit's subject. The implementer's
  reset clears only its in-progress fix attempt; nothing else is
  rolled back. Phase C does **not** roll back prior iterations'
  successful `Review fix:` commits on a `FAILED` return — the
  earlier fixes remain valid (they passed their gate check) and
  the failed iteration is treated as a no-op. See
  [`track-code-review.md`](track-code-review.md) §Review loop and
  the `level=track` row in §Return contract field rules below.

The implementer is therefore symmetric in code (the snapshot-and-diff
revert sequence regardless of level, mode, and early-return case)
but the orchestrator-side cleanup differs: pre-commit modes (Phase B
`INITIAL`/`WITH_GUIDANCE`) need no further work; Phase B
`FIX_REVIEW_FINDINGS` requires the post-commit rollback to remove
the prior step commits; Phase C never rolls back across spawns.

---

## Return contract

The implementer's return is a **single structured block**. The
orchestrator parses it; everything else in the implementer's output is
informational and ignored.

**Silent exit is forbidden.** The implementer MUST emit a `RESULT:`
block before exiting for **any** reason — successful completion,
detection-rule early return (design decision, risk upgrade,
fundamental failure), context-window exhaustion, message-budget
pressure, tool-call-budget pressure, or unrecoverable runtime error
in any tool call. A return whose text contains no parsable `RESULT:`
block (or a block truncated mid-field) is a contract violation: the
orchestrator cannot dispatch on missing data, the implementer's last
actions are by definition uncommitted, and recovery requires manual
inspection of the working tree (see
[`track-code-review.md`](track-code-review.md) §Phase C Implementer
Handlers → `RESULT_MISSING` for the orchestrator-side handler).

When an exit is forced before sub-steps 1–3 complete, the priority
order is: **(1) emit `RESULT: FAILED` first**, with the cause stated
honestly in `FAILURE.why_it_failed` (e.g., "ran out of message
budget after applying 7 of 22 findings; tests not yet re-run; tree
dirty"), `FAILURE.what_was_attempted` populated, and
`FAILURE.recommended_action: retry`. List every path the
implementer touched in `FILES_TOUCHED` even when not yet reverted —
the orchestrator uses `FILES_TOUCHED` to decide between
commit-as-is, re-spawn, and discard. **(2) Then run the revert
sequence** per §"Detection rules — return early without committing"
if time and budget still permit. If the revert cannot complete,
leaving a dirty tree at HEAD on a `FAILED` return is itself a
contract violation that the orchestrator's
`handle_iteration_failure` path surfaces to the user (see
[`track-code-review.md`](track-code-review.md) §Phase C Implementer
Handlers → `handle_iteration_failure` "Verify `git status` is clean
before continuing"); the user then chooses among the same
commit-as-is / re-spawn / discard options as the `RESULT_MISSING`
path. Both paths converge on the same recovery flow, so an honest
partial-progress `FAILED` (even with a dirty tree) is strictly
better than a silent exit. The §"Detection rules" section still
governs **deliberate** early-returns (design decision, risk upgrade,
fundamental failure under normal budget) — those have time to
revert and must do so; this clause governs the **unplanned-exit**
case where the priority is preserving the RESULT contract over the
cleanup contract.

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

EPISODE_DRAFT:                    # populated only at level=step
  what_was_done: |
    <factual summary, 2–6 sentences>
  what_was_discovered: |
    <or "none">
  what_changed_from_plan: |
    <or "none". Name affected future steps if any.>
  critical_context: |
    <or "none". Use sparingly.>

FIX_NOTES:                        # populated only at level=track
  what_was_fixed: |
    <factual summary of which findings the iteration addressed,
    1–4 sentences. Cite finding IDs (CQ7, BC3, …) freely — these
    are branch-only-commit-message-scope identifiers and never
    leak into durable content.>
  what_was_skipped: |
    <or "none". Findings the implementer chose not to address
    inside this iteration — typically because they would expand
    the fix beyond track scope. The Phase C orchestrator may fold
    these into plan corrections.>
  what_was_discovered: |
    <or "none". Cross-step or cross-track observations surfaced
    while applying fixes.>

CROSS_TRACK_HINTS: |
  <free-form: anything that might affect upcoming tracks. At
  level=step the Phase B orchestrator consumes this in sub-step 5.
  At level=track the Phase C orchestrator folds this into the
  eventual track episode. Empty is fine.>

# --- Conditional sections, only present when relevant ---

DESIGN_DECISION:                  # only if RESULT == DESIGN_DECISION_NEEDED
  context: ...
  alternatives:
    - name: ...; pros: ...; cons: ...
    - name: ...; pros: ...; cons: ...
  recommendation: ...
  exploration_notes: ...          # echoed back on respawn

RISK_UPGRADE:                     # only if RESULT == RISK_UPGRADE_REQUESTED
                                  # — forbidden at level=track
  from: low | medium
  to: medium | high
  category: concurrency | crash-safety | public-API | security |
            architecture | performance-hot-path
  evidence: ...

FAILURE:                          # only if RESULT == FAILED
  what_was_attempted: |
  why_it_failed: |
  impact_on_remaining_steps: |    # at level=track this is
                                  # "impact on remaining findings /
                                  # remaining iterations"
  recommended_action: retry | split | escalate
                                  # split is forbidden at level=track
                                  # — step-splitting does not apply to
                                  # the cumulative track diff (see
                                  # §Fundamental failure above).
                                  # Allowed values at level=track:
                                  # retry | escalate.
  failure_class: push_only | content
                                  # default: content. Set to push_only
                                  # when the commit landed locally but
                                  # `git push` failed (see Sub-step 3
                                  # above). Routes the orchestrator
                                  # past the rollback handler — the
                                  # commit content is fine.
```

### Field rules

- `RESULT` is the dispatch tag — the orchestrator routes on it. Pick
  exactly one value. `RISK_UPGRADE_REQUESTED` is **forbidden at
  `level=track`** (see §Risk upgrade required above).
- `COMMIT` is empty when `RESULT != SUCCESS`. On `SUCCESS`, it is the
  SHA of the implementer's commit. At `level=step` with
  `mode=INITIAL`/`WITH_GUIDANCE` it is the step's primary commit; at
  `level=step` with `mode=FIX_REVIEW_FINDINGS` and at `level=track`
  it is the SHA of the `Review fix:` commit applied on top.
  **`RESULT: SUCCESS` implies the commit at `COMMIT` has been pushed
  to `origin`.** Sub-step 3 above mandates `git push` immediately
  after the commit lands. If the push failed (see Sub-step 3 above
  for the failure-shape reference), do not emit `SUCCESS`. Emit
  `RESULT: FAILED` with `FAILURE.recommended_action: retry`,
  `FAILURE.failure_class: push_only`, and a `FAILURE.why_it_failed`
  that names the push failure shape so the orchestrator can
  reconcile without rolling back the commit.
- `FILES_TOUCHED` lists every path in the diff with a `(new)` or
  `(modified)` annotation. On `FAILED`, list paths the implementer
  attempted to modify even though they are now reverted.
- `TEST_SUMMARY` is required on `SUCCESS`. On `FAILED`, populate what
  is meaningful (e.g., last test run's pass count) and use `n/a`
  otherwise. On `DESIGN_DECISION_NEEDED` and `RISK_UPGRADE_REQUESTED`,
  the implementer has typically not run tests yet — every field may
  be `n/a`. When the step is **test-additive** (no production-source
  changes per the rule in §"Pacing long-running tasks — foreground
  only" above), the coverage profile build is skipped — set
  `line_coverage_changed` and `branch_coverage_changed` to
  `n/a (test-additive)` and keep `passed` / `module` /
  `spotless_applied` populated normally.
- `EPISODE_DRAFT` is populated on `SUCCESS` only and only at
  `level=step`. The Phase B orchestrator finalises it (merging in
  cross-track-impact-check observations from sub-step 5) before
  writing the episode to the step file. On `FAILED`, the
  orchestrator uses `FAILURE` instead of `EPISODE_DRAFT`. On
  `DESIGN_DECISION_NEEDED` and `RISK_UPGRADE_REQUESTED`, omit
  `EPISODE_DRAFT` — the eventual respawn produces the authoritative
  draft once the step actually completes. **Omit at `level=track`** —
  use `FIX_NOTES` instead.
- `FIX_NOTES` is populated on `SUCCESS` only and only at
  `level=track`. The Phase C orchestrator folds it into the eventual
  track episode (compiled at track completion). On `FAILED`, the
  orchestrator uses `FAILURE` instead. **Omit at `level=step`** —
  use `EPISODE_DRAFT` instead.
- `CROSS_TRACK_HINTS` is a free-form note for the orchestrator. At
  `level=step` the Phase B orchestrator consumes it in sub-step 5
  (cross-track impact check). At `level=track` the Phase C
  orchestrator folds it into the track episode and any plan
  corrections. Anything that might affect upcoming tracks goes here —
  invariant weakened, new dependency surfaced, API shape clarified
  differently than expected. Empty is fine; the orchestrator does
  not require a hint per spawn.
- `FAILURE.recommended_action` is level-conditional. At `level=step`
  it is one of `retry | split | escalate`. At `level=track` it is
  one of `retry | escalate` — **`split` is forbidden** (see
  §"Fundamental failure" above). Returning `split` from a
  `level=track` spawn is a contract violation; the orchestrator
  surfaces the return to the user instead of dispatching.
- `FAILURE.failure_class` discriminates content failures (`content`,
  the default) from push-only failures (`push_only`). Set `push_only`
  only when Sub-step 1's content work succeeded, Sub-step 3's commit
  landed locally, and only `git push` failed. The orchestrator uses
  this flag at `mode=FIX_REVIEW_FINDINGS` to skip the rollback path
  in `rollback_and_handle_failure` — see
  [`step-implementation-recovery.md`](step-implementation-recovery.md).
  Omit or set to `content` for every other failure (work didn't
  start, tests failed, spotless failed, commit refused, etc.).
  Mis-tagging a content failure as `push_only` leaves a broken
  commit at HEAD and is a contract violation.

---

## Tooling discipline

The implementer relies on the existing rules verbatim — do not
duplicate the routing tables here. Pointers:

- **PSI vs grep, IDE refactoring, Maven routing**: the project's
  `CLAUDE.md` § MCP Steroid (sub-sections "Grep vs PSI — when to
  switch", "Maven — when to route through mcp-steroid", "Refactoring —
  IDE refactor vs raw Edit").
- **Project conventions and PSI requirement for load-bearing audits**:
  [`conventions.md`](conventions.md) §1.4 *Tooling discipline*.
- **Ephemeral identifier rule** for durable content (code, tests,
  PR title/body, `design-final.md`, `adr.md`):
  [`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md) is
  the full rule; the §2.3 stub in `conventions-execution.md` carries
  the quick recap and the self-check grep. Branch-only commit
  messages are exempt — see
  [`commit-conventions.md`](commit-conventions.md) "Branch-only
  commit messages may cite workflow-internal identifiers".
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
