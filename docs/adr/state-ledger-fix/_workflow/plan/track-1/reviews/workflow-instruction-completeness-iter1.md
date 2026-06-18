<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: WI1, sev: should-fix, loc: "track-review.md:1000", anchor: "### WI1 [should-fix] §Phase A Resume table not updated for the ledger-tail invariant", cert: C1, basis: "resumed session with committed roster but unadvanced ledger short-circuits to steady-state and re-loops Phase A — the exact YTDB-1140 trace the fix targets"}
  - {id: WI2, sev: suggestion, loc: "track-review.md:1050", anchor: "### WI2 [suggestion] step-2 recovery reuses <level> with no binding on the resumed path", cert: C2, basis: "recovery branch fires on interrupted/resumed sessions where step 5's <level> read never ran this session, leaving --ctx <level> unbound"}
  - {id: WI3, sev: suggestion, loc: "track-review.md:596", anchor: "### WI3 [suggestion] step 6 commits without checking the append succeeded", cert: C3, basis: "append returns non-zero on disk-full/unwritable; step 6 proceeds to git add+commit unconditionally, committing an unchanged ledger"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### WI1 [should-fix] §Phase A Resume table not updated for the ledger-tail invariant

File: `docs/adr/state-ledger-fix/_workflow/staged-workflow/.claude/workflow/track-review.md` (line 1000), Axis: gate resume path (loop closure).

The new step-2 ledger-tail verification and its recovery branch live only in
§Phase A Completion, which runs at the end of a session that executed §What You
Do steps 1-6. The §Phase A Resume table (line 1000, row 3) is the path a *fresh*
`/execute-tracks` takes when a prior session already completed Phase A, and that
table was not updated to carry the new `phase=C track=<N>` tail invariant.

The trace the fix is meant to close re-opens on the resume path:

1. Session 1 runs §What You Do through step 6. Step 6 now does two ops in
   sequence — the `--append-ledger` call, then `git add track + ledger && commit
   && push`. The append is skipped (orchestrator drops the sub-step) or writes
   nothing the commit captures (a no-op `git add` of an unchanged `phase-ledger.md`
   when the append failed), so the decomposition commits but the ledger tail stays
   `phase=A`.
2. Session 1 ends (it is a mandatory session boundary; the orchestrator may end
   before reaching §Phase A Completion step 2, or step 2's normal-path check is
   what was skipped).
3. Session 2 clears context, re-runs `/execute-tracks`. The precheck reads the
   stale `phase=A` and the Startup Protocol routes to §Phase A Resume (per the
   track file's own §Context and Orientation: `phase=A` is read as "no track file
   yet, pre-Phase-A").
4. §Phase A Resume reaches table row 3 (all reviews recorded, non-empty `[ ]`
   roster). The committed roster makes the row resolve to its else-branch: "the
   track file is already in steady state and `/execute-tracks` should route to
   Phase B on the next invocation."
5. But the ledger still reads `phase=A`, so the next invocation does NOT route to
   Phase B — it re-enters §Phase A Resume again. The loop the fix removes survives
   on the resume path.

Step 2's tail check is never reached on this path because the resume table's
terminal action asserts steady-state without re-running step 6 or handing off to
§Phase A Completion step 2. The step's own scope names this directly: "does the
new step-2 verification gate have a resume/recovery path that actually re-verifies
(loop closure)." On the resume path it does not.

Suggestion: update §Phase A Resume table row 3's else-branch so the "steady
state" conclusion is gated on the ledger tail reading `phase=C track=<N>`, not on
the commit alone. Concretely, add a tail check to the row: if the roster is
committed but the ledger tail is not `phase=C track=<N>`, route to §Phase A
Completion step 2's recovery branch (re-run the step-6 append, dedicated commit,
re-verify) before declaring steady state. This makes commit-landed no longer
imply ledger-advanced — the exact equivalence the new step 6 breaks.

### WI2 [suggestion] step-2 recovery reuses <level> with no binding on the resumed path

File: `docs/adr/state-ledger-fix/_workflow/staged-workflow/.claude/workflow/track-review.md` (line 1050), Axis: argument validation (unbound placeholder on a recovery path).

The step-2 recovery snippet runs `workflow-startup-precheck.sh --append-ledger
--ctx <level> --phase C --track <N>`. The `<level>` token is bound only by §What
You Do sub-step 5's statusline read (lines 563-571), which reuses into step 6
(line 591). §Phase A Completion provides no instruction binding `<level>`.

On the normal completion path this is fine — step 2 runs in the same session as
step 5/6, so `<level>` is still in context. But the recovery branch explicitly
targets the interrupted case ("the session was interrupted between step 5 and
step 6") and the resumed-session case where the prior session ended. On those
paths step 5's read never ran this session, so `<level>` is undefined when the
orchestrator reaches the recovery snippet, and the procedure gives no rule for
what to substitute.

Suggestion: in the step-2 recovery branch, point `<level>` at a read with the
same fallback step 5 uses — "read `/tmp/claude-code-context-usage-$PPID.txt`,
parse `level=`, use `unknown` on miss" — or state explicitly that `<level>` may
be `unknown` here (the script accepts the bare token). One clause closes it.

### WI3 [suggestion] step 6 commits without checking the append succeeded

File: `docs/adr/state-ledger-fix/_workflow/staged-workflow/.claude/workflow/track-review.md` (line 596), Axis: error and recovery path (external call exit status).

Step 6 runs the `--append-ledger` call, then unconditionally proceeds to `git add
docs/adr/<dir-name>/_workflow/plan/track-<N>.md docs/adr/<dir-name>/_workflow/phase-ledger.md
&& git commit && git push`. The script guards its writes (disk-full / unwritable
return 1 with a diagnostic, per `workflow-startup-precheck.sh:1620-1638`), but
step 6 carries no instruction to check the append's exit status before committing.
A failed append followed by an unconditional commit lands the unchanged ledger
inside the atomic commit, and the bug condition (`phase=A` tail) persists.

This is a suggestion, not a should-fix, because §Phase A Completion step 2's
tail check is the backstop on the normal completion path and has a recovery. The
gap is only that step 6 itself proceeds blind, deferring all detection to step 2
(and step 2 is itself bypassed on the resume path — see WI1).

Suggestion: add one clause to step 6 — "if the append returns non-zero, halt and
do not commit; resolve the write failure first" — so the failure is caught at the
append site rather than deferred to the completion gate.

## Evidence base

#### C1 [CONFIRMED] §Phase A Resume table 3 else-branch bypasses the new tail check

Complement / loop-closure check. The new tail verification + recovery is added
only at §Phase A Completion step 2 (staged lines 1030-1062). §Phase A Resume's
resume-action table (staged lines 996-1000) is unchanged by the delta; row 3's
else-branch ("otherwise the track file is already in steady state and
`/execute-tracks` should route to Phase B on the next invocation") predicates
"route to Phase B" on the commit landing, not on the ledger tail. The track
file's §Context and Orientation (lines 201-217) and §The bug (lines 219-230)
confirm `phase=A` routes a fresh session back into §Phase A Resume, and
`determine_state_from_ledger`'s `C)` case (script line 1781) only fires when the
tail already reads `phase=C`. So a committed-roster-but-`phase=A`-ledger state
loops: resume table → steady-state → next invocation reads `phase=A` → §Phase A
Resume again. The new step-2 recovery is on the §Phase A Completion path, which
this resume route does not enter. Loop not closed on the resume path; the precise
gap the step scope flags.

#### C2 [CONFIRMED] <level> bound only by §What You Do step 5; absent in §Phase A Completion

Argument-availability check. grep for `level`/`ctx` across the staged file shows
the only binding reads are §What You Do step 5 (lines 563-571) and the per-step
gate at lines 529-539; §Phase A Completion (lines 1017-1083) has no statusline
read and no `<level>` binding. The step-2 recovery snippet (line 1050) uses
`--ctx <level>`. The recovery branch's own trigger text targets the
between-step-5-and-step-6 interruption and the resumed session, on which step 5's
read did not run this session — so `<level>` is unbound exactly where the
recovery fires. Normal-path use is sound (same session). Confirmed gap, scoped to
the recovery branch.

#### C3 [CONFIRMED] step 6 commits regardless of append exit status

Error-path check. Step 6 (staged lines 581-616) sequences the append then the
`git add`/`commit`/`push` with no exit-status guard between them. The append
function (`workflow-startup-precheck.sh:1618-1638`) can return 1 on a write
failure with a stderr diagnostic, so a non-zero return is a reachable state. The
commit's `git add` of an unchanged `phase-ledger.md` stages nothing and the
commit still succeeds with the track file alone, so the failure is silent at the
commit. Detection is fully deferred to §Phase A Completion step 2's tail check;
step 6 has no inline guard. Confirmed, minor because step 2 backstops the normal
path (modulo WI1 on the resume path).
