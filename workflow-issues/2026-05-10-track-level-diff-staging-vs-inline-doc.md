---
severity: medium
phase: phase-c
source-session: 2026-05-10 /execute-tracks unit-test-coverage
---

# Track-level diff staging is the de facto convention but `track-code-review.md` still says "inline"

## Symptom

`.claude/workflow/track-code-review.md` § Sub-agents § "## Diff" prescribes:
*"{output of git diff {base_commit}..HEAD — passed inline since it is
the review target}"*. The same doc's "Why paths, not inline contents"
note carves the diff out as the one exception that stays inline because
it is "small and step-specific."

For Track 22a Phase C the cumulative diff was 12.8K lines / 108 files.
Inlining that into 6 dimensional review sub-agent prompts would have
embedded 77K lines of diff into the orchestrator's tool-call history —
clearly impractical. I pre-staged the diff to
`/tmp/claude-code-track22a-diff-240255.patch` and the file list to
`/tmp/claude-code-track22a-files-240255.txt`, then pointed each
sub-agent at the path. Each sub-agent read the path successfully.

The same staging shape was needed for the iter-2 and iter-3 gate-check
spawns. Six dimensional reviewers × three iterations = eighteen agents,
all of whom would have received an inlined 12.8K-line diff under the
current doc's rule. The doc's own "small and step-specific" justification
for inlining holds for Phase B step-level review (one step's diff is
small) but breaks for Phase C track-level review on tracks that
accumulate many steps.

A previous session's reflection
(`workflow-issues/2026-05-08-track-level-diff-too-large-to-inline.md`)
raised the same friction without a definitive doc-side resolution.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/track-code-review.md` § Sub-agents § "## Diff"
    section in the canonical context block
  - `.claude/workflow/track-code-review.md` § "Why paths, not inline
    contents" justification paragraph
- Tool / sub-agent involved (if any): every Phase C dimensional review
  sub-agent spawn (`review-code-quality`, `review-bugs-concurrency`,
  `review-test-behavior`, `review-test-completeness`, optional
  `review-test-structure`, `review-performance`, etc.)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: Any Phase C session where the cumulative track
  diff exceeds approximately 5K lines or covers more than ~30 files.
  Tracks dominated by test additions (like Track 22a's ~95 test
  classes) hit this threshold trivially.

## Why it's a problem

Three impacts:

1. **Token cost on the orchestrator side.** With 18 sub-agent prompts
   each carrying a 12.8K-line diff, the orchestrator's tool-call
   history accumulates ~230K lines of redundant diff content. Even
   though the agents run as separate processes, the orchestrator's
   prompt-caching surface includes the spawn tool calls, so this
   weight matters.
2. **Cognitive load on the orchestrator agent.** When an orchestrator
   has to compose six near-identical 4-5K-token prompts, it tends to
   compress them in ways that drop the carefully-prescribed context
   block. The doc's per-section guidance ("Workflow Context", "Review
   Target", "Implementation Plan", "Track Steps", "Skip These Files",
   "Tooling", "Diff") is meant to keep prompts consistent across
   spawns; size pressure undermines that consistency.
3. **Doc-vs-practice drift.** The next agent reading
   `track-code-review.md` gets a rule ("inline") that the previous
   agent ignored. Either every Phase C session has to rediscover the
   staging pattern, or each one writes a one-off justification in
   chat for the deviation.

The staging fix is mechanical (pre-stage to `/tmp/claude-code-track-N-diff-$$.patch`,
pass the path) and the agents handle the path-read transparently —
but the doc should bless the pattern instead of leaving it in the gap
between "small and step-specific" (which is true for Phase B) and
"keep the orchestrator lean" (which is the project-wide rule).

## Proposed fix

Edit `.claude/workflow/track-code-review.md` § Sub-agents § "## Diff"
to make the routing rule size-conditional:

```
## Diff
For cumulative diffs ≤ ~3K lines / ~25 files: pass inline (the diff
is small enough that the orchestrator's tool-call cost is negligible).

For cumulative diffs above that threshold: pre-stage the diff to
`/tmp/claude-code-track-{N}-diff-$$.patch` and the changed-file list
to `/tmp/claude-code-track-{N}-files-$$.txt`, then pass both paths in
the sub-agent prompt:

  pre_staged_diff_path: /tmp/claude-code-track-{N}-diff-{PID}.patch
  pre_staged_files_path: /tmp/claude-code-track-{N}-files-{PID}.txt

Sub-agents read the paths via Bash / Read; the diff content lands in
sub-agent context, not the orchestrator's tool-call history. Apply the
project's `/tmp` file-isolation convention (unique suffix via $$ /
$PPID / branch name) — see project-level `CLAUDE.md` § Concurrent
Agent File Isolation.
```

Update the "Why paths, not inline contents" paragraph below to
acknowledge the threshold rather than describe the diff as a flat
exception. The same pattern applies to the Phase C iter-N gate-check
prompts (re-prompts can re-use the staged paths from iter-1 if the
underlying diff hasn't been re-rebased).

## Acceptance criteria

- `.claude/workflow/track-code-review.md` § Sub-agents has an explicit
  size threshold for inline-vs-stage routing.
- The threshold and staging path convention are documented in the same
  section as the prompt template, so an orchestrator composing the
  prompt sees the rule at the point of use.
- A future Phase C session with a >5K-line cumulative diff routes
  through the staging path without inventing an ad-hoc convention.
- The previous session's
  `workflow-issues/2026-05-08-track-level-diff-too-large-to-inline.md`
  is closed by the same change (file removed once the implementer
  triages both into the YouTrack tracker).
