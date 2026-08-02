<!-- workflow-sha: c2f43f01ec -->
# Core compliance failure dispositions

Twenty-one `gremlin-process-compliance-tests` failures survive Track 10 and every
one is deferred as pre-existing branch debt. This file is the disposition record
that `## Validation and Acceptance`'s second clause requires — "every out-of-scope
one is recorded with its disposition" — so the track can close without declaring
green on a suite that is not green.

It supersedes `core-test-failure-inventory.md` as the enumeration the criterion
reads. That file is not wrong about what it measured; it measured a different
suite. See § Why the original inventory does not answer this.

## Measurement

| Field | Value |
|---|---|
| Control (track base) | `f007749249`, run in worktree `.claude/worktrees/track10-base` |
| Subject | `0c7911a74f` for the attribution run; `d14493217c` for the closing verification |
| Command | `./mvnw -pl core clean test -Dmaven.test.failure.ignore=true`, repo root, default in-memory env |
| Logs | `/tmp/track10-base-control.log` (control), `/tmp/track10-core-reproduce.log` (subject), `/tmp/track10-final-verify.log` (closing) |

Per-execution totals, control against subject:

| Execution | Base `f007749249` | Subject |
|---|---|---|
| `default-test` | 18263 — 23 F / 82 E | 18290 — green |
| `sequential-tests` | 2165 — green | 2174 — green |
| `gremlin-process-compliance-tests` | 965 — 120 F / 204 E | 965 — 16 F / 6 E |
| `gremlin-structure-compliance-tests` | 914 — 100 E | 920 — green |

Method-level set difference over `gremlin-process-compliance-tests`: **300**
failing methods at the base, **22** at the subject, and exactly one method
failing at the subject that does not fail at the base —
`YTDBHasLabelProcessTest.testByIdHasLabelSiblingClassDoesNotMatch`, fixed in
`0c7911a74f`. The track repaired 278 failures in this execution plus 105 in
`default-test` and 100 in `structure-compliance`, and caused one.

## Dispositions

All 21 are **deferred to Track 9**, whose Plan of Work item 1 already owns
"fix any pre-existing cross-track mistranslation before adding new recognisers".
Each fails byte-identically at the track base, so none is this track's to fix and
none can be attributed to its diff.

| Count | Class | Disposition |
|---|---|---|
| 5 | `HasTest` | Deferred — Track 9 item 1a. By-id filter on a post-hop alias. Not BG8: reverting the `SQLWhereClause` leaf branch, which disables promotion for code-assembled clauses entirely, leaves all five failing with identical signatures. |
| 2 | `AndTest` | Deferred — Track 9 item 1a. Multi-alias shapes with the dropped-filter signature (over-emission: 2→3 and 1→6). |
| 1 | `WhereTest` | Deferred — Track 9 item 1a. Over-emission 2→10, same signature. |
| 1 | `SelectTest` | Deferred — Track 9 item 1a. Over-emission 4→6. |
| 2 | `ValueMapTest` | Deferred — Track 9 item 1. `ClassCast String → List` and an NPE on `List.get`; boundary value shaping for the MAP output type. |
| 2 | `PropertiesTest` | Deferred — Track 9 item 1. `ClassCast String → Property`. |
| 1 | `MeanTest` | Deferred — Track 9 item 1. `ClassCast Integer → Double`. |
| 1 | `SumTest` | Deferred — Track 9 item 1. |
| 1 | `GroupTest` | Deferred — Track 9 item 1. Over-emission 4→5. |
| 1 | `GroupCountTest` | Deferred — Track 9 item 1. NPE on `Long.longValue`. |
| 1 | `ElementMapTest` | Deferred — Track 9 item 1. Under-emission 4→2. |
| 1 | `OrderTest` | Deferred — Track 9 item 1. Ordering divergence, expected `[josh]` got `[marko]`. |
| 1 | `PartitionStrategyProcessTest` | Deferred — Track 9 item 1. Fails byte-identically at the base; not BG8 despite the pause handoff grouping it there. |
| 1 | `SubgraphStrategyProcessTest` | Deferred — Track 9 item 1. Same. |

## Why the original inventory does not answer this

`core-test-failure-inventory.md` ran at `f5737976be` and recorded two surefire
executions bound to `test` and four failures. The 2026-08-02 rebase onto
`develop` — the same rebase DR-M5 covers — pulled in develop's `9b9dfa20fd`,
which restored three TinkerPop compliance executions this branch had never run.
`./mvnw -pl core test` now drives 965 process-compliance tests plus 920
structure-compliance tests that did not execute when the inventory was taken, so
the inventory's enumeration claim is scoped to a suite set that no longer
describes the command.

The four scenarios that inventory did record are all fixed: `sequential-tests`
is green at the subject, and all 20 `YTDBQueryMetricsStrategyTest` scenarios pass.

**Rule this adds to DR-U6.** A failure inventory is valid only against the commit
it was taken at, and a rebase invalidates it exactly as it invalidates a recorded
base SHA. The track file already knew to recompute the base SHA after the
2026-08-02 rebase and did so. Recompute the inventory at the same moment.

## References

- `plan/track-10/reviews/bugs-iter1.md` — BG8 (fixed, `0c7911a74f`), BG9 (fixed, `d14493217c`)
- `plan/track-9.md` § Plan of Work item 1a — mechanism and measurements for the dropped per-alias filter
- `implementation-plan.md` § Checklist, Track 10 — the title revision this measurement forced
