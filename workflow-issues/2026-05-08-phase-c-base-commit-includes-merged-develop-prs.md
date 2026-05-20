---
severity: high
phase: phase-c
source-session: 2026-05-08 /execute-tracks read-cache-concurrency-bug
---

# Phase C base commit can include merged-in develop PRs, polluting the cumulative review diff

## Symptom

On `read-cache-concurrency-bug`, the Phase B base commit recorded in
`tracks/track-1.md` was `7319340d3078b9855d4a43c94d5bc746d9ed08b6` — the
SHA captured by Phase B startup just before the first implementer spawn.
The plan commit (`87d1a3f8a3 YTDB-669: Add design + plan for read-cache
concurrency bug`) landed AFTER the base capture, and between those two
points, 10 develop PRs (#1036–#1045 — workflow-infra updates, statusline
fix, etc.) were merged into the branch via `git pull origin develop`.
As a result, `git diff 7319340d..HEAD` returned 68 changed files / 6483
additions / 748 deletions — of which 49 files were merged-in develop
work, not Track 1's contribution. Track 1's actual contribution scoped to
`core/` is 19 files / 2666 additions / 48 deletions.

The Phase C orchestrator had to (a) manually re-scope the diff to
`core/` for every one of the 9 review sub-agents and the 6 gate-check
sub-agents, (b) explicitly inline a "the cumulative diff also contains
merged develop PRs (#1036–#1045 — workflow infra) that are NOT Track 1
work; do not review them" caveat in every prompt, and (c) generate a
separate scoped diff file (`/tmp/claude-code-track1-diff-$PPID.diff`)
to avoid 9× duplication of the 160KB diff in the orchestrator's
tool-call history.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/step-implementation.md` §Phase B Startup item 1
    (records `git rev-parse HEAD` as the base, no rule about merge state)
  - `.claude/workflow/track-code-review.md` §Phase C Startup item 1
    (reads the recorded base verbatim and §Sub-agents context template
    says "Reviewing commit range: `{base_commit}..HEAD`" / "git diff
    {base_commit}..HEAD")
- Tool / sub-agent involved (if any): all Phase C dimensional and
  gate-check review sub-agents (`review-code-quality`,
  `review-bugs-concurrency`, `review-crash-safety`, `review-performance`,
  `review-security`, `review-test-behavior`, `review-test-completeness`,
  `review-test-concurrency`, `review-test-crash-safety`,
  `review-test-structure`)
- ADR directory at the time: `docs/adr/read-cache-concurrency-bug/`
- Trigger condition: any branch where develop has been merged in
  between Phase B startup's `git rev-parse HEAD` and Phase C's review
  fan-out. This happens routinely on long-lived branches that pull
  develop to stay current — i.e., most multi-track plans.

## Why it's a problem

Each Phase C sub-agent gets a polluted review target — the cumulative
diff overstates the scope by 3–4×. Three concrete failure modes:

1. **Wasted budget.** Reviewers spend message and context budget reading
   merged-in develop work that is already on `develop` and was reviewed
   on its own PR.
2. **False findings.** A reviewer may flag a code-quality / bug /
   concurrency issue on develop-merged code that is genuinely
   pre-existing; the orchestrator then has to attribute every finding
   to "Track 1 work vs. pre-existing" before it can synthesise the
   in-scope list. In this session 17 of 18 IntelliJ inspection findings
   were on lines blamed to commits older than the Track 1 base — only
   1 finding was Track-1-attributable. Without manual blame triage the
   orchestrator would have routed the other 17 to the implementer.
3. **Per-spawn overhead.** The orchestrator has to inline a 5–10-line
   "ignore these merged-in PRs" caveat in every sub-agent prompt
   (9 dimensional × ~6 gate-check = 15 prompts per Phase C session),
   re-explaining the same scope rule each time.

The current Phase C pattern of "manually scope the diff to `core/` plus
write a caveat in every prompt" is a workaround, not a fix — a future
agent on a different ADR with different scoped paths would have to
re-derive the scoping rule.

## Proposed fix

Two options. Either or both should resolve the symptom.

### Option A — Phase C scopes against `origin/develop` rather than the recorded base

Edit `.claude/workflow/track-code-review.md` §Phase C Startup item 5 and
the §Sub-agents context block: change "`git diff {base_commit}..HEAD`"
to "`git diff origin/develop...HEAD`" (three-dot — equivalent to
"changes on this branch since it diverged from develop"). This naturally
excludes commits already on `develop` (the merged PRs) without changing
how Phase B records the base. The recorded base remains useful for Phase
B's own implementer spawns (which target a specific step's diff, not
the cumulative track diff).

Tradeoff: if the branch was created on an older `develop` point and the
implementer never rebased / merged, three-dot diff and two-dot diff
return the same set, so no regression. If the branch rebased onto a
newer `develop`, three-dot would correctly capture only branch-unique
work.

### Option B — Phase B re-records the base at Phase C startup

Edit `.claude/workflow/track-code-review.md` §Phase C Startup item 1 to
add: "Before reading `## Base commit`, run
`git merge-base origin/develop HEAD` and compare against the recorded
SHA. If they differ AND the recorded SHA is an ancestor of the
merge-base, the recorded base predates a develop merge — update the
step file's `## Base commit` section to the merge-base SHA, commit the
update as a Workflow update commit, then proceed."

Tradeoff: more invasive (touches the step file mid-phase); the
historical base SHA in git log becomes harder to audit. Option A is
cleaner.

### Recommended

Option A. It localises the change to Phase C's review-target derivation
(one line in `track-code-review.md` + the context template), leaves
Phase B's recording rule untouched, and works for any cumulative-diff
review that Phase C runs.

## Acceptance criteria

- `.claude/workflow/track-code-review.md` §Phase C Startup or §Sub-agents
  context block specifies `git diff origin/develop...HEAD` (three-dot)
  as the canonical Phase C review target.
- All sub-agent context templates in `track-code-review.md` use the
  same diff-derivation rule.
- A grep for `git diff {base_commit}..HEAD` in
  `.claude/workflow/track-code-review.md` returns no matches (only the
  three-dot form remains, OR an explicit note that the two-dot form is
  used for a different purpose like Phase B step diffs).
- A test run of `git diff origin/develop...HEAD --shortstat` on a
  branch that has merged develop PRs returns only the
  branch-unique changes; the recorded base SHA in the step file
  matches `git merge-base origin/develop HEAD` (or, if Option B is
  taken, is updated to do so at Phase C startup).
- A new section in the Phase C doc cross-references the rule: "Why
  three-dot: the branch may have merged develop PRs between Phase B
  startup and Phase C; two-dot would include those as in-scope review
  targets."
