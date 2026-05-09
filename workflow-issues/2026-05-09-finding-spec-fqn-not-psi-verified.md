---
severity: medium
phase: phase-c
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Phase C iter-2 finding spec contained a wrong FQN; implementer did not PSI-verify, missed the fix, forced an extra iteration

## Symptom

Track 21 Phase C iteration 2's finding spec for **CQ5** named four
inline FQNs to convert to imports in
`BTreeMVBucketV2BulkOpsTest.java`:

> Replace inline FQNs with regular imports for: `DurablePage`,
> `RID` (`com.jetbrains.youtrackdb.api.record.RID`), and the
> Mockito statics `Mockito.reset` and `ArgumentMatchers.eq`.

The orchestrator-supplied FQN for `RID`
(`com.jetbrains.youtrackdb.api.record.RID`) was **wrong** — no
class exists at that path. The actual class lives at
`com.jetbrains.youtrackdb.internal.core.db.record.record.RID`
(directory listing under `core/src/main/java/...` confirms).

The implementer (Opus, `model: opus`) applied 3/4 of the CQ5
sub-fixes (DurablePage + Mockito statics) but skipped the RID
conversion. The structured RESULT block reported `psi_audits: 0`
under TOOLING_NOTES, confirming the implementer never PSI-verified
the FQN before deciding what to do with it. The gate-check
sub-agent (`review-code-quality`) caught the residual at the
diff line `var rids = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.RID>();`
and flagged CQ5 as STILL OPEN, forcing a third iteration purely
to add `import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;`
and rewrite that one line — a mechanical change that should have
been part of iteration 2.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/track-code-review.md` § "Implementer Spawns"
    (orchestrator composes the `findings:` block)
  - `.claude/workflow/implementer-rules.md` § "Tooling discipline"
    (implementer's PSI rules)
  - `.claude/workflow/conventions.md` §1.4 *Tooling discipline —
    prefer mcp-steroid PSI for Java symbol audits* (canonical
    source for PSI rules in the workflow)
- Tool / sub-agent involved: track-level implementer
  (`mode=FIX_REVIEW_FINDINGS`, `level=track`)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase C / Phase B implementer spawn whose
  `findings:` block cites a Java FQN in the issue description; the
  bug surfaces only when the cited FQN is wrong (typo, hallucination,
  or out-of-date package path) AND the implementer does not
  PSI-verify before deciding what to do.

## Why it's a problem

The implementer cannot recover from a wrong FQN in the prompt
without PSI-verifying every FQN it sees. Today neither the
implementer rulebook nor the prompt template mandates this.
A wrong FQN cascades:

1. Implementer searches its in-context understanding for the FQN,
   does not find it (or finds a different class with the same
   simple name), and silently skips the sub-fix.
2. Gate-check catches the residual.
3. An entire extra iteration runs to close one mechanical line.
4. Iteration budget shrinks (3-iteration cap is shared across
   sessions; if the orchestrator's prompt errors burn 1 of 3 on
   trivial residuals, real findings have less budget).

The cost is one full implementer spawn (Opus, ~60 K tokens of
sub-agent work) plus a gate-check, plus the orchestrator's overhead
in detecting and dispatching the iteration — easily 10 K tokens
of orchestrator context. All for a one-line Edit the orchestrator
itself could have caught at prompt-composition time with a single
PSI find-class call.

## Proposed fix

Two complementary edits, both worth landing:

(a) **Implementer rulebook addition** — Edit
`.claude/workflow/implementer-rules.md` (the section that lists
what the implementer reads before applying findings) to add an
explicit clause:

> When the `findings:` block cites a fully qualified Java symbol
> name (FQN), PSI-verify the FQN exists at the named path before
> applying the fix. If the named class does not exist, search by
> simple name and use the actual location; if multiple matches
> exist, surface a `RESULT: DESIGN_DECISION_NEEDED` rather than
> silently picking one or skipping the sub-fix.

(b) **Track-code-review and step-implementation orchestrator
addition** — Edit `.claude/workflow/track-code-review.md`
§ "Implementer Spawns" (and the parallel section in
`step-implementation.md`) to add:

> Before composing the `findings:` block, the orchestrator MUST
> PSI-verify every FQN it cites. The cheap way: keep the simple
> class names + the file path of the offending line, and let the
> implementer resolve the FQN itself. The expensive way:
> `mcp__localhost-6315__steroid_execute_code` with a
> `JavaPsiFacade.findClass(...)` lookup per FQN. Pick whichever
> is cheaper for the iteration's finding count.

Recommended: (a) + (b) together. (a) is the safety net; (b)
prevents the safety net from being exercised when the
orchestrator can avoid the FQN altogether.

## Acceptance criteria

- [ ] `.claude/workflow/implementer-rules.md` carries an explicit
  PSI-verify-FQN clause that fires before the implementer applies
  any finding citing an FQN.
- [ ] `.claude/workflow/track-code-review.md` § "Implementer Spawns"
  AND `.claude/workflow/step-implementation.md` § "Implementer Prompt
  Template" carry an explicit orchestrator-side rule (or a "prefer
  simple names" guidance) for FQN handling in `findings:`.
- [ ] Reproduction: a Phase C session whose finding spec cites a
  wrong-on-purpose FQN like `com.fake.api.RID` either (a) gets the
  finding skipped with `RESULT: DESIGN_DECISION_NEEDED` from the
  implementer, or (b) is caught at orchestrator-side composition
  before the spawn — never a silent skip.
