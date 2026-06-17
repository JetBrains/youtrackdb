# Chapter 12 — Phase C: track-level code review and track completion

Every step of a track is committed, tested, and recorded. Phase C is what turns that pile of commits into a closed track: it runs one more review over the whole track's changes at once, drives the fixes through the same approval loop the earlier gates used, and then signs the track off with a completion record. This chapter teaches that last sub-phase — what the track-level review catches that step reviews could not, how you read and act on its report, how fixes land without the orchestrator ever editing code itself, and what "closing a track" actually writes to disk.

You arrive here with the review mechanism from Chapter 11 already in hand: a review fans out across dimensions, a triage step picks which agents run, each agent files findings at one of three severities, and an iteration loop applies fixes and re-checks them until the reviews pass. Phase C reuses that machinery wholesale, but turns it on a different target. Where Chapter 11 described the mechanism at either scale, Phase C is the one place it runs at *track* scale: the input is the cumulative diff of the entire track, not one step's commit. Everything in this chapter is built on that shift of target, plus a second mechanism you met at the Phase A gate in Chapter 9 — the conversational *Review mode* loop the user drives.

## Phase C opens on a track whose every step is already done

You re-run `/execute-tracks`. Phase B has committed the last step of the track, written its episode, and stopped at a session boundary. The workflow picks the track back up, sees that all its steps are checked off, and enters Phase C. The orchestrator does not start by reading code. It starts by reconstructing the boundary of what to review: it reads the SHA the track recorded when Phase B began, confirms that SHA is still an ancestor of the current `HEAD`, and computes the cumulative diff `git diff {base_commit}..HEAD`. That range, every commit the track produced from its first step to its last, is the review target for the whole phase.

The ancestor check is not ceremony. A rebase between Phase B and Phase C rewrites every commit on the branch, so the SHA recorded at Phase B startup still resolves but is no longer an ancestor of `HEAD`. Diffing against a stale base would silently pull in commits from earlier tracks, inflating the review by orders of magnitude or, after a subtle rebase, shifting it by a handful of files. When the recorded base is stale, the orchestrator recomputes the real on-branch parent from the commit that recorded it and notes the discrepancy in the track file, keeping both SHAs for the audit trail.

## A track review catches what no single step review could see

The reason Phase C runs a review at all, after every `high`-risk step was already reviewed in Phase B, is that the cumulative diff shows interactions a step never could. One step adds a producer; a later step adds the consumer; only the two diffs side by side reveal whether the producer and the consumer actually meet. A step review reads one commit and cannot see the other. The track review reads them together, and that is its whole point: inconsistent error handling spread across steps, an integration that two steps each assumed the other would write, architectural drift from the plan that accreted one reasonable-looking step at a time.

Because the value is in those cross-step seams, the review leans on the IDE's symbol index rather than text search wherever a finding turns on a reference-accuracy question. "Does this producer have a consumer?" and "is this renamed symbol still referenced from the step that did not change?" are find-usages questions; when the IDE is reachable they route through it, and only fall back to text search (with a noted caveat) when it is not. The same fan-out also runs one pass that the step level skipped: the full set of code, test, and workflow review agents. Step reviews defer most agents to keep a single step cheap; the track pass is where code-quality, test-behavior, test-completeness, and the deferred workflow reviewers finally read the whole change. Triage still selects which conditional agents run, exactly as Chapter 11 described — the track review is the same selection, run against a larger diff.

There is one exception, and it is worth holding precisely because it is easy to misremember. A track of exactly one step *whose step is `risk: high`* skips the track-level review entirely. The reason is that a `high` single step was already given a full dimensional review in Phase B against the identical diff, and a one-step track has no cross-step seam to catch. The skip is recorded in the track file and the phase proceeds straight to completion. But a single step tagged `medium` or `low` had its step review *skipped* in Phase B (only `high` steps earn one), so its diff has never been reviewed — and the track review runs in full, treating that one step as the entire diff. The shorthand "single-step tracks skip the review" is wrong in exactly the case that matters: the skip is gated on the step having already been reviewed, not on the step count.

```
   all steps of the track committed
              │
              ▼
   ┌──────────────────────────────────────────────┐
   │ exactly 1 step AND that step is risk: high ?   │
   └──────────────────────────────────────────────┘
        │ yes                          │ no
        ▼                              ▼
   skip track review            run track review
   (identical diff already      (full fan-out over the
    reviewed in Phase B)         cumulative track diff)
        │                              │
        └──────────────┬───────────────┘
                       ▼
                 Track Completion
```

**Figure 12.1 — When the track-level review is skipped.** Only a one-step track whose sole step is `high` skips the review, because Phase B already reviewed that exact diff. A `medium` or `low` single step was never reviewed and runs the full track review.

## The review loop is the orchestrator and a fresh implementer, split

Phase C runs the same iterate-until-pass loop as every other review, but with a division of labor worth naming explicitly, because it is the same split that made Phase B work (Chapter 10). The orchestrator owns the loop: it spawns the review fan-out, synthesizes the findings into one routed list, decides which are in scope for this track, and re-checks the fixes. It does not apply the fixes. Applying fixes (reading the diff, editing source, running tests and the coverage gate, committing) is delegated to a **fresh implementer sub-agent**, spawned once per iteration that has something to fix, exactly the sub-agent rulebook Chapter 10 introduced. The orchestrator never edits a source file in Phase C.

The reason for the split is the same one that justified it in Phase B. A track-level fan-out can be six dimensional reviewers across three iterations, all reading the same large diff; folding the fix work into the orchestrator too would pile every source read, every Maven run, and every reviewer's output into the orchestrator's own context until it could no longer think clearly. Pushing the fix work into a fresh sub-agent each iteration keeps that traffic out of the orchestrator. The implementer is spawned with two settings that tell it what job it is doing: `level=track`, meaning it works against the cumulative track diff rather than one step, and `mode=FIX_REVIEW_FINDINGS`, meaning it is applying review findings on top of the existing `HEAD` rather than building something new. It reads the synthesized findings, applies the fixes, runs the touched tests plus the coverage gate, and commits with a `Review fix:` prefix that marks the commit as a review-driven change in the branch log.

```
   ┌────────────────────────────────────────────────────┐
   │ ORCHESTRATOR                                         │
   │   spawn review fan-out  →  synthesize findings       │
   │   classify in-scope vs deferred                      │
   │            │ in-scope findings                       │
   │            ▼                                          │
   │     spawn fresh IMPLEMENTER  (level=track,            │
   │            │                  mode=FIX_REVIEW_FINDINGS)│
   │            │   reads diff, fixes, tests, commits      │
   │            ▼   "Review fix:" commit on HEAD           │
   │     gate-check fan-out  →  PASS / loop                │
   └────────────────────────────────────────────────────┘
                    max 3 iterations
```

**Figure 12.2 — The Phase C review loop.** The orchestrator drives the loop and re-checks fixes; a fresh implementer applies them and commits. The orchestrator never edits source itself.

When the implementer returns, the orchestrator dispatches on its structured result. A success means the `Review fix:` commit is already on `HEAD` and pushed, so the orchestrator records the iteration in the track file and runs a gate-check, re-spawning only the review dimensions that still had open findings, each with a compact gate-check prompt that asks for a verdict per finding rather than a full re-survey. A design-decision return pauses the loop, surfaces the decision to the user, and re-spawns the implementer with the user's chosen direction (a back-edge to escalation that Chapter 14 covers). A failure exits the loop with its findings left unfixed, to be surfaced at completion. A return with no parsable result block at all, an implementer that ran out of budget mid-fix, drops into a manual recovery the orchestrator cannot automate: it inspects the working tree and asks the user to commit what landed, re-spawn, or discard. The loop runs at most three iterations total, counted across sessions; if blockers survive all three, they are carried to completion and shown to the user rather than lost.

## Review mode: the user drives the approval, not a yes/no prompt

Once the review loop passes (or exhausts its iterations), Phase C does not auto-close the track. It presents the results and hands the decision to the user through the same conversational gate you met at Phase A in Chapter 9: an approval panel with three one-step options — **Approve**, **Review mode**, or **ESCALATE**.

Approve closes the track. ESCALATE routes to inline replanning for a deep rework (Chapter 14). The middle option is the one that makes the gate a conversation. In Review mode you drop observations across as many chat turns as you like — "this fix should also guard the null case", "why did the implementer skip the second call site?", a request to change something the review missed. The orchestrator silently classifies each remark, answers questions inline, acknowledges the rest in one plain line, and accumulates them. It never narrates the machinery at you; the internal type labels stay out of chat. When you signal you are done, one approval panel surfaces the whole accumulated set so you see everything before it happens, and only on Apply does any change land.

Two of those classified actions matter at completion. A *question* is read-only: it is answered inline and has no side effect, but it stays in the buffer so the final panel shows the audit trail of what was asked. A *fix request* is the one with teeth. On Apply, every fix request is collected into a findings list and a fresh implementer is spawned with `level=track` and `mode=FIX_REVIEW_FINDINGS`, the same template the review loop used, whose `Review fix:` commit lands on top of `HEAD`. The orchestrator still does not touch source itself. A handful of action types available at the Phase A gate are not available here: there is no plan edit, step-description edit, track skip, or clarification at completion, because none of those have a target once the track's code is written. After each Apply, the panel re-renders so you can layer on more, until you Approve or ESCALATE.

The user-driven fix requests at completion carry no iteration budget. The three-iteration cap exists to bound *autonomous* review-loop spinning; a user clicking Apply is the natural rate limit, so it replaces the cap rather than consuming it. This is why a track can be re-opened after the fact — a user noticing something post-merge re-enters Review mode, and the fix spawns are budgetless.

## Closing a track is a deferred, ordered write

Approval is the only point at which Phase C records the track as done, and the write order is deliberate. Nothing about completion is written before the user approves — and that is the load-bearing design choice of the whole phase.

The reason is resume safety. If the orchestrator wrote "track complete" and *then* waited for approval, a session that ended in that gap would resume to find the track already marked done and would skip the user's review entirely. By deferring every completion write until after approval, an interrupted session simply re-enters track completion on resume and re-presents the same panel. The signal a resuming session reads is precise: all of the track's phases are checked off in its progress section, but the phase ledger has not yet recorded the track's completion boundary. That gap (steps done, ledger not advanced) is exactly the state "approval still pending", and it is the same in every tier.

On approval, the orchestrator first compiles the *track completion episode*: a strategic summary of what the track built, what it discovered, any plan deviations with cross-track impact, and which review-fix iterations applied non-trivial changes. This is the track-scale analogue of the per-step episode from Chapter 10, synthesized from all the step episodes plus the notes the fix iterations stashed, and sized in proportion to how much the next track needs to know. It re-reads the cumulative diff before compiling, so it reflects any `Review fix:` commit a prior session's implementer may have landed. Then the writes land in order:

- **The completion episode goes to the track file's `## Episodes` section**, as a final `### Track completion` block after the per-step episodes. This is the home that exists in *every* tier, including `minimal`, which has no plan to carry an episode elsewhere.
- **In `lite` and `full`, the plan's entry for the track collapses and is marked `[x]`.** The full episode prose lives in the track file now, so the plan keeps only a one-line summary, a pointer to the track-file episode, and a pointer to the track file. The detailed implementation subsections that were in the plan are superseded by the committed code and the episodes, and they would otherwise bloat every later reviewer's strategic-context prompt, so collapsing them keeps the plan lean. In `minimal` there is no plan, so this step does not exist; the track-file episode is the only record.
- **The phase ledger records the completion boundary**, advancing the active track to the next one if a track remains, or crossing to the final phase if this was the last track. This ledger append is the machine-readable resume signal; in `lite` and `full` the plan's `[x]` is the human-visible echo of the same fact.

These land together in one `Mark <track> complete` commit, so the draft PR shows a clean track boundary. Before that, if the review surfaced findings that were real but out of scope for this track, the orchestrator folds them into plan corrections (adding the work to a future track or a new one) in a separate commit, so the deferred work is not lost. After the commit and push, the orchestrator runs the self-improvement reflection (Chapter 16's feedback loop) and the session ends.

## What Phase C hands forward

A track leaves Phase C closed. Its cumulative diff has been reviewed for the cross-step interactions no single step review could see; any in-scope findings were fixed by a fresh implementer through the same `Review fix:` loop the rest of the workflow uses; the user drove the final approval through Review mode; and the completion episode plus the ledger boundary record the track as done in a way a resumed session reads correctly. If more tracks remain, the next `/execute-tracks` session picks up the next one at Phase A, and the Pre-Flight gate's look-back (Chapter 9) reads the episode this phase just wrote.

When the last track closes, the ledger crosses to the final phase, and the change has nowhere left to go but out. Chapter 13 picks up there: Phase 4, where all the tracks are complete and the workflow assembles the durable artifacts (the final design and the architectural-decision record), promotes any staged changes, removes the working scaffolding, and readies the branch to merge. The question this chapter set up and Chapter 13 answers is what survives the merge, once every track has been built, reviewed, and signed off.

## Further reading

- `.claude/workflow/track-code-review.md` — Phase C in full. The base-commit ancestor check and stale-rebase recompute (§Phase C Startup), the single-step skip condition (§Single-Step Track), the cross-step rationale and the PSI tooling rule (§Tooling), the review fan-out and synthesis (§Synthesis), the iterate-until-pass loop and its three-iteration cap (§Review loop), the per-iteration implementer spawn and the success/escalate/failure/missing handlers (§Implementer Spawns, §Phase C Implementer Handlers), the deferred-finding plan corrections (§Plan Corrections), and the deferred, ordered completion write (§Track Completion).
- `.claude/workflow/review-mode.md` — the conversational approval loop the user drives at completion: the three-option Approval-panel contract, the accumulate-then-one-panel flow, the `QUESTION` and `FIX_FINDING` action types, and the four implementer-return outcomes at completion (§Approval-panel contract, §Flow, §Action types, §Completion FIX_FINDING outcome mapping).
- `.claude/workflow/review-iteration.md` — the iteration limit (max 3) and the compact gate-check budget the re-checks use (§Limits, §Dimensional-review gate-check budget).
- `.claude/workflow/implementer-rules.md` — the track-level fix implementer: the `level=track` / `mode=FIX_REVIEW_FINDINGS` inputs, the rule that the implementer never writes the plan or track file, and the `Review fix:` commit it produces (§Inputs, §What the implementer does, §Return contract).
- `.claude/workflow/commit-conventions.md` — the `Review fix:` prefix and the `Mark <track> complete` Workflow-update commit (§Commit type prefixes).
- `.claude/workflow/episode-format-reference.md` — where the track-completion episode lives and the Progress-only write shape Phase C uses (§Where episodes live, §The four-section write checklist).
