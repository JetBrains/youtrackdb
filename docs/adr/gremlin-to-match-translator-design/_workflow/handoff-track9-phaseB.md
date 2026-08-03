<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Handoff — Track 9 Phase B, 2026-08-03T19:05Z [ctx=info→warning]

Paused at the context warning with **step 9's fix respawn owed and three blockers
open**. Branch `gremlin-to-match-translator-design`, HEAD `0b28a1cb7d`, everything
pushed, working tree clean. Read this, then `plan/track-9.md` (`## Decision Log`
DR-S2 through DR-S8, `## Surprises & Discoveries`, `## Episodes`), then the two
step-9 review files.

## Do this first

**Spawn the step 9 `FIX_REVIEW_FINDINGS` respawn.** Nothing else is in flight on
Track 9. `step_base_commit: 87cf455d8f`, step commit under review `c252146ba5`.
Findings are in `plan/track-9/reviews/bugs-step9-iter1.md` (BG1–BG6) and
`test-structure-step9-iter1.md`. Do **not** write the step 9 episode until the
respawn returns — the episode header takes the `Review fix:` SHA.

## The three blockers, and why they agree

Both reviewers converged on the same defect from opposite directions, which is the
strongest signal on this track so far.

**BG1 (bugs, blocker) — the sub-walk escape is unsound.**
`PropertiesStepRecogniser`'s escape is scoped to the whole captured child rather
than to the position inside it where the projection is unread. So wrapping the
exact meta-property read step 9 exists to stop — `properties(k).has(metaKey, v)` —
inside `where` / `filter` / `and` / `not` restores it. Measured on all four
spellings as a row set **disjoint** from native: the translation returns the vertex
carrying a top-level `acl` and drops the one whose `acl` is a meta-property.

**Test-structure blocker 1 — that escape ships with no exercised control.** The
sub-walk arm of `countConsumedAndSubWalkPropertiesForms_stillTranslate` never
reaches the gate: `where(__.values(k))` is claimed by
`TraversalFilterStepRecogniser.presenceKey` as the `has(key)` desugar. Proof by
mutation: forcing `SubTraversalPredicateAdapter.projectsReturnedPayload()` to
`true` leaves the whole `...gremlin.translator.**` package green — 32 classes, 634
tests. The count arm *is* pinned twice over; only the sub-walk arm is vacuous.
**This is why BG1 shipped**, and it is the tenth recorded instance of this
branch's vacuous-acceptance failure mode.

**Test-structure blocker 2 — `isSingleValueProperty` narrowing is untested and
costs a working translation.** `ByModulatorTranslatorTest` gained no case, its
existing bodies are hand-built and skip `applyStrategies()`, and the class is green
under either predicate. The narrowing silently withdrew a correct translation of
`group().by(k).by(values(k2).count())` — boundary steps 1 → 0, payloads identical
either way — because `AdjacentToIncidentStrategy` rewrites that value-side child
into the element form. BG3 is the same finding from the bugs side. The commit
message's "this one costs nothing" is false for the value-side call site; the
key-side half of the claim checks out.

**One live divergence that is NOT step 9's** and needs an owner:
`g.V().and(__.values("age"), __.values("name"))` returns all three seeded vertices
translated against Alice alone natively. It reproduces at the parent commit, so
step 9 did not introduce it — but a non-vacuous sub-walk test would have surfaced
it. BG2 covers the mechanism: a projection inside a sub-walk contributes **nothing
at all**, not a presence conjunct, so the AND is a no-op.

## Where the track stands

Residue **1930 / 5 / 14**, translator on; off arm 0. Roster:

| Step | State |
|---|---|
| 1, 2, 3, 5, 7, 8 | `[x]`, episodes written |
| 4, 6, 12 | `[~]` — dropped, reasons in DR-S3 and DR-S7 |
| 9 | code merged (`c252146ba5`), reviews landed, **fix respawn owed, no episode, roster `[ ]`** |
| 10 | BG3 single-plan slice-then-hop — not started |
| 11 | BG2 across three recognisers — not started |
| 13 | the `not()` range-comparison divergence — not started |
| 14 | the control probe (DR-S7) — not started |

The five surviving Cucumber scenarios are **not** step 9's: four are BG2 (step 11)
and one is the `not()` divergence (step 13). Step 9 fixed 2 of its 7 and diagnosed
the rest.

## Decisions taken this session

- **DR-S3** — steps 2/4/6 restructured; `core`'s A/B already existed.
- **DR-S4** — BG3 to step 5 (later moved to step 10); step 8 ships with Phase C as
  its only review gate.
- **DR-S5** — step 5 decomposed into five bounded steps, because a `FAILED` return
  would have reverted the code rather than splitting it.
- **DR-S6** — `mean` modelled as `K0_NONE` in `ShapeClassifier`, taking five
  previously unmodelled aggregate names with it; PF1's estimator gap deferred to
  executor work.
- **DR-S7** — steps 6 and 12 dropped; step 14 is a control probe; the final
  four-arm baseline moves to **track completion**, where R8 cannot invalidate it.
- **DR-S8** — Track 11 item 6 becomes absolute: **both runners green**, 0 failures
  and 14 skips, plus a frozen exclusion list for anything reproducing on `develop`.
  A decline keeps this reachable (declined traversals run natively and pass); a
  **waiver does not**.

## Two process facts the next session must not re-derive

**Commit `de6c8b402b` is the orchestrator's, not an implementer's.** Step 5's spawn
was stopped before emitting a `RESULT` block; the tree was verified independently
and committed under `handle_result_missing`'s commit-as-is path. Recorded in the
step 5 episode.

**The coverage gate has not run on any step this session** — steps 5 and 9 both
skipped it by orchestrator instruction, because it is the full
`clean package -P coverage` build and implementers on this branch have repeatedly
burned hours in it. **Phase C owns it and must not skip it.**

## The Cucumber suite is not a net for this defect family

Step 5's four blockers and step 9's `group().by(properties(k))` bucket-merge were
all invisible to the compliance suite — the residue was byte-identical before and
after fixing them. Steps 10, 11 and 13 must gate on measured translator-on /
translator-off equivalence, not on the scenario count. Recorded in
`## Surprises & Discoveries` and in DR-S8.

## Out of band: Track 11 item 7

Branch **`t11-item7-jmh`**, worktree `.claude/worktrees/t11-jmh`, commit
`06caa2f962`, based on `ffb57fe5cf`. Not merged, not pushed. Three files in
`jmh-ldbc`, no existing file modified; compiles and runs (43 tests). Three of four
shapes green on both arms; `fold` red because `GremlinStepWalker` has no `FoldStep`
entry — items 2–3's deliverable. Status recorded in `plan/track-11.md` item 7.

**An agent is still running in that worktree** extending the shape set. It was
given two corrections mid-flight worth knowing about:

- Only **3 of 21** LDBC queries use `LET`, so A7's premise that the set is
  uniformly unreproducible is too strong. IS1 and IS3 look expressible with shapes
  the translator recognises today; IC1 is out (variable-depth `repeat`, deliberately
  declined); IS7 is out (`optional`, Phase 2).
- **Declining shapes earn benchmarks too.** An on/off A/B on a declined shape
  measures the decline path's own cost, which nobody has quantified, and reserves a
  zero baseline against which a future translation shows its gain. Such shapes must
  assert `requireNotTranslated` on **both** arms, which doubles as a tripwire when
  the shape later starts translating.

Collect that agent's return before touching the worktree. `union` is **not** an
equivalent of `LET` — it concatenates, `LET` binds a correlated per-row name; the
Gremlin analogues are `as()`/`select()` and `project().by()`.

## Rules any respawn runs under

Never `mvn install`, never `-am` — the shared `~/.m2` poisons pending measurements
(Track 11 R5). The core snapshot there is a local install from 2026-08-03 15:32 and
predates everything Track 9 landed after that. Never the full coverage gate. One
Maven invocation at a time per worktree. Iterate with
`./mvnw -pl core -o test-compile surefire:test@gremlin-feature-compliance-tests
-Dmaven.test.failure.ignore=true`; the `test-compile` prefix is not optional.
Steps 14 and the completion baseline are measurements — stop every other stream
before running them.
