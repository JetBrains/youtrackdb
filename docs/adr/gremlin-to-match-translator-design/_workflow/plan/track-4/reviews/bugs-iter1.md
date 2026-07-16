<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: BG1, sev: suggestion, loc: "SQLEndsWithCondition.java:148-154", anchor: "### BG1 ", cert: C1, basis: "needsAliases uses the negated operand combination vs SQLBinaryCondition/SQLAndBlock; faithful copy of sibling; no live caller today"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED}
  - {id: C2, verdict: REFUTED}
  - {id: C3, verdict: REFUTED}
  - {id: C4, verdict: REFUTED}
  - {id: C5, verdict: REFUTED}
  - {id: C6, verdict: REFUTED}
  - {id: C7, verdict: REFUTED}
  - {id: C8, verdict: REFUTED}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [suggestion] SQLEndsWithCondition.needsAliases inverts the standard operand combination

The new node's `needsAliases` returns the logical negation of what every other
composite boolean node returns. It is a faithful copy of the same inverted body
in the sibling `SQLContainsTextCondition`, and no live path calls the method
today, so it cannot affect results now — but it is a latent wrong answer if a
future consumer ever routes an `ENDSWITH` condition through a live
`needsAliases` call.

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLEndsWithCondition.java` (lines 148-154)
- **Issue**: The body is

  ```java
  if (!left.needsAliases(aliases)) {
    return true;
  }
  return !right.needsAliases(aliases);
  ```

  which evaluates to `!(left.needsAliases && right.needsAliases)`. Every other
  composite node combines the operands with OR — `SQLBinaryCondition` (lines
  305-310) does `if (left.needsAliases(aliases)) return true; return
  right.needsAliases(aliases);` and `SQLAndBlock` OR-folds its sub-blocks. The
  new node reports "needs aliases" for a self-contained literal suffix match and
  "does not need aliases" for a condition that actually references an alias — the
  opposite of the intended predicate.
- **Evidence**: Confirmed against the reference implementations in cert C1. The
  method is never reached from a live path: a repo-wide search for
  `.needsAliases(` finds only recursive parent-to-child delegations inside
  `sql/parser/SQL*` node classes, with no invocation from the MATCH planner,
  executor, `SQLWhereClause`, or any other entry point (reference-accuracy
  caveat: mcp-steroid PSI was unavailable this session, so this is a grep result;
  `.needsAliases(` is a distinctive literal that grep resolves reliably for
  direct calls, though a reflective invoker would be missed).
- **Refutation considered**: The identical inverted body already ships in
  `SQLContainsTextCondition` (lines 195-200), so this is a deliberate mirror of
  the substring sibling, not a fresh transcription slip — which is exactly why it
  is a suggestion and not a should-fix. It is also currently dead, so no query
  regresses.
- **Suggestion**: Adopt the standard form
  (`if (left.needsAliases(aliases)) return true; return
  right.needsAliases(aliases);`) and fix `SQLContainsTextCondition` in the same
  edit so the substring/suffix pair stays consistent — or, if the inversion is
  intentional for these two nodes, add a comment stating why, so the next reader
  does not flag it again.

## Evidence base

#### C1 needsAliases inversion — CONFIRMED

`SQLEndsWithCondition.needsAliases` (148-154) computes `!(left.needsAliases &&
right.needsAliases)`, the negation of the OR-combination used by
`SQLBinaryCondition` (305-310) and `SQLAndBlock`; grep shows no live caller
outside the recursive `sql/parser` delegation chain, so it is a latent-only
defect.

#### C2 findMode survives every SQLMatchesCondition reconstruction path — REFUTED

Claim: a reconstruction site drops `findMode`, so a deep-copied or split plan
silently reverts a find-mode regex to full-match. Every site that builds a fresh
`SQLMatchesCondition` was enumerated and each carries `findMode`:
`copy()` (line 192), `splitForAggregation()` (line 329), `equals()` (line 251),
`hashCode()` (line 260), and `toGenericStatement()` (line 135). A repo-wide
`new SQLMatchesCondition` search returns only the `MatchWhereBuilder.matchesRegex`
factory (186), the two in-class reconstructors (187, 322), and the parser
(`YouTrackDBSql.java:9407`); the parser never touches `findMode`, leaving it at
its `false` default, which is correct for parsed full-match SQL. No site drops
the field. Not a bug.

#### C3 SQLEndsWithCondition round-trips both operands — REFUTED

Claim: a reconstruction site drops an operand, so a cloned plan loses the
predicate. `copy()` (156-159) and `splitForAggregation()` (279-282) both assign
`left` and `right`; `equals()` (394-408) and `hashCode()` (411-415) both fold
`left` and `right`; `toGenericStatement()` (331-335) emits both operands around
the `ENDSWITH` token; `getMatchPatternInvolvedAliases()` (417-432) is null-safe
on both. Every reconstruction and comparison site carries both operands. Not a
bug.

#### C4 toGenericStatement keeps find-mode fingerprint-distinct — REFUTED

Claim: a find-mode node and a full-match node on the same expression and pattern
collide on the plan-cache fingerprint. `toGenericStatement` emits
`" MATCHES(find) "` when `findMode` is set and `" MATCHES "` otherwise (line
135), so the two modes render different generic statements. Parsed SQL is always
full-match, so its fingerprint token is unchanged and existing cache keys are
stable. Not a bug.

#### C5 collate transform preserves NULL/absent semantics on all four eval paths — REFUTED

Claim: the rewrite to pattern-matching `instanceof` plus the collate call changes
how absent or null operands are treated. The old guard
`x == null || !(x instanceof String)` and the new `!(x instanceof String s)` are
equivalent — `instanceof` is already false for `null` — and the substitution is
applied identically on all four paths: `evaluate(Identifiable)` (36-46),
`evaluate(Result)` (58-64), `evaluateAny` (67-83), `evaluateAllFunction`
(85-100). `resolveCollate` / `collateForProperty` return `null` for a
schema-less record or an undeclared property, and `containsCollated` then falls
through to a raw `indexOf`, preserving default case-sensitive behavior. Not a
bug.

#### C6 (String) collate.transform cast is safe — REFUTED

Claim: `(String) collate.transform(value)` throws `ClassCastException` for some
collate. `transform` is only invoked after the operand has already passed the
`instanceof String` guard, so the argument is always a `String`. Both built-in
collates return a `String` for a `String` input: `DefaultCollate.transform`
returns the argument unchanged (line 40-42) and `CaseInsensitiveCollate.transform`
returns `s.toLowerCase(...)` (line 45-48). A custom collate returning a
non-`String` for a `String` would already break `SQLBinaryCondition`'s
collated comparison, so the String-preservation invariant is codebase-wide, not
newly assumed here. Not a bug.

#### C7 evaluate(Identifiable) session dependency is not a regression — REFUTED

Claim: the new `evaluate(Identifiable)` reads `ctx.getDatabaseSession()` (to wrap
the record in a `ResultInternal` and resolve the collate) where the old body did
not, so a caller with an unpopulated session now NPEs. `SQLBinaryCondition`
already reads `ctx.getDatabaseSession()` in its own `evaluate(Identifiable)`
(lines 63, 66) and calls `left.getCollate(currentRecord, ctx)` in
`evaluate(Result)` (line 102), and it backs every parsed `WHERE` comparison, so a
populated session in the `CommandContext` during condition evaluation is an
established precondition of the whole evaluation framework. The new read joins an
existing pattern; it introduces no new failure mode. Not a bug.

#### C8 SQLMatchesCondition.getMatchPatternInvolvedAliases does not NPE on the find-mode literal — REFUTED

Claim: the pre-existing, unguarded
`new ArrayList<>(expression.getMatchPatternInvolvedAliases())` +
`addAll(rightExpression.getMatchPatternInvolvedAliases())` (lines 264-271) — live
in `MatchExecutionPlanner` and the plan-cache `ShapeClassifier` — NPEs once
`matchesRegex` populates `rightExpression` with a bare string literal. For
`matchesRegex` both the field and the pattern are
`SQLExpression` → `SQLBaseExpression`, and `SQLBaseExpression`'s implementation
returns `Collections.emptyList()` (non-null) for a plain identifier and for a
string literal, so `new ArrayList<>(emptyList)` and `addAll(emptyList)` are both
safe. The grammar already produces `rightExpression` for `field MATCHES <expr>`
(`MatchesCondition` production), so the path is not even novel. Not a bug.
