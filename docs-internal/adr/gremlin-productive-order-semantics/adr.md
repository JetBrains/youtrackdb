# Productive Gremlin order semantics — Architecture Decision Record

## Summary

A global-scope Gremlin `order()` step keeps a record that lacks the ordered property and
sorts it as a null key. Apache TinkerPop drops that record, because a `by(...)` modulator
is a traversal and an unproductive traversal drops its traverser. The YouTrackDB Query
Language (YQL) keeps the row and sorts the missing column as null. The two engines
disagreed for the same sort, and the Gremlin answer depended on which engine served the
query.

This is a DELIBERATE DEVIATION from portable Apache TinkerPop semantics. It ships on by
default. A deployment-wide setting and a per-traversal override restore the portable
behavior.

Both execution paths carry the change. The native path adds one traversal strategy,
`YTDBProductiveOrderByStrategy`, filtered to `OrderGlobalStep`. The translated path stops
emitting the order-key `IS DEFINED` conjunct through the existing policy seam,
`OrderKeyPresencePolicy`. The translation cache keys on the resolved setting.

## Goals

- One record missing the ordered key survives a global-scope order, on both execution
  paths, and sorts where YQL sorts a null key. Met.
- One setting and one per-traversal override answer for both paths. Met, through one
  resolver, `YTDBStrategyUtil.orderIncludesMissingKey`.
- No upstream conformance scenario is edited. Met, through the suite opt-out.
- Local-scope order, `select`, `values`, `group` and `dedup` modulators keep their
  filtering behavior. Met.

## Constraints

- The drop happens in `OrderGlobalStep.processAllStarts`, before any comparison. A
  comparator change therefore cannot restore the record.
- `TraversalStrategies.GlobalCache` holds one strategy list per graph class for the whole
  process, and a static initializer populates it. A registration gated on configuration
  would freeze the decision at first class load.
- The translation cache is storage-wide rather than per session, so any resolved value
  that changes the emitted plan must appear in the shape key.
- Branch `order-by-nulls-first-last` makes null placement configurable and touches the
  same registration point. Placement assertions must read the configured default rather
  than a fixed position.

## Decision Records

**D1: Rewrite the projection, not the comparator.** The strategy wraps each by-modulator
of `OrderGlobalStep` in `coalesce(modulator, constant(null))`. Rejected: wrapping the
comparator. A comparator never sees a traverser dropped before the sort. The wrapping
helper also infers direction from comparator identity. It therefore misreads a descending
order and stops the shuffle optimization.

**D2: A YouTrackDB-owned strategy, not upstream `ProductiveByStrategy`.** The upstream
strategy selects every `ByModulating` traversal parent, so registering it would also make
local-scope order, `select`, `dedup`, `group` and `path` productive. The owned strategy is
filtered to `OrderGlobalStep`, which is exactly the global-scope order step. Rejected:
registering the upstream strategy.

**D3: Register unconditionally and read the setting inside `apply`.** The strategy list is
built once per graph class for the whole process. Rejected: conditional registration,
which would freeze the decision at first class load and ignore every later write.

**D4: One policy seam for the translated path.** `OrderKeyPresencePolicy` answers from the
resolved setting, and the order recogniser routes its per-comparator emission through it.
Rejected: reading the setting at each emission site.

**D5: One resolver for both paths.** Both halves call
`YTDBStrategyUtil.orderIncludesMissingKey`, which reads the traversal option first and the
session default second. Rejected: two independent readers, which let one traversal get two
answers.

**D6: Key the translation cache on the resolved setting.** The shape key carries an `oim`
token beside the polymorphism, edge-label-verification and productive-by tokens. Rejected:
leaving the key alone, which would splice a plan built under one setting into a traversal
running under the other, across sessions.

**D7: Global scope only.** Local-scope order stays as it is. No YQL analogue exists, and
the translator never handles the local-scope order step. Inclusion there would change the
size of a collection inside a row rather than the set of rows. Rejected: covering both
scopes.

**D8: Run the conformance suites with the portable opt-out.** The suite base configuration
carries the opt-out, so the suites keep measuring portable behavior. Rejected: editing
about six upstream scenarios, which makes every future upstream update a merge conflict.

## Invariants and Contracts

- Exactly one mechanism serves each shape. A recognised shape loses its `OrderGlobalStep`
  to the translated boundary, so the native rewrite finds nothing. A declined shape keeps
  the step and gets the rewrite. The native strategy names the translator as a prior
  strategy, which orders the two.
- The opt-out switches off the YouTrackDB default alone. Upstream `ProductiveByStrategy`
  still makes a modulator productive on a traversal that adds it.
- Null placement follows the YQL dialect. An ascending order puts the null key first, and
  a descending order puts it last.

## Accepted Risks

**R1: Two order spellings contradict each other.** Under the default `order().by(k)` keeps
a record missing `k`, while `fold().order(Scope.local).by(k)` drops the entry. The two
spellings differ by one argument. Accepted as an intentional released difference, stated
in the user documentation.

**R2: Zero upstream conformance coverage of the shipped default.** The suites run with the
portable opt-out, so about 66 upstream scenarios exercise portable semantics only. That
surface includes order nested inside `repeat`, `local`, `union`, `choose` and `where`,
order combined with `limit`, `tail`, `range`, `skip` and `sample`, multi-key order, and
interaction with `EarlyLimitStrategy` and `RepeatUnrollStrategy`. Accepted, with
project-owned tests covering the default and this record naming the deviation.

**R3: A consumer may rely on the drop as a filter.** Such a query returns more rows after
the upgrade. Accepted, mitigated by the setting, the override and the release note.

**R4: No detection mechanism.** Nothing reports which queries change their rows. A
deployment can restore the old behavior without recompiling, and the release note is the
only signal. Accepted.

**R5: Sibling-branch coordination.** Branch `order-by-nulls-first-last` makes null
placement configurable and touches the same registration point, the same configuration
class and the same documentation. The second change to land reconciles the two settings.
Accepted as a coordination duty.

**R6: A public behavioral change ships beside several blocker fixes.** The diff grows and
the review takes longer. Accepted on the user decision to deliver complete order-by
behavior in one change.

## Known Defect

Under the including default, a translated query loses duplicate rows when three conditions
hold together. The conditions are a fan-in hop, an indexed order key, and the order applied
to the fan-in target. The sorted alias then carries no filter. The planner roots an ordered
index scan on it, and each target is visited once.

The repair belongs to the ordered-index cost track in the same pull request, which owns
root selection and the ordered-scan guards. A test pins the wrong value on purpose and
names the inversion that track must perform.

## Non-Goals

- Making local-scope order productive.
- Making `select`, `values`, `group`, `dedup` or `path` modulators productive.
- Changing null placement, which the sibling branch owns.
- Repairing ordered-index root selection, which the ordered-index track owns.
