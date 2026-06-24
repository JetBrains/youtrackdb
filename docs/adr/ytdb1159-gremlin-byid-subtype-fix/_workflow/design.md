<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Polymorphic `hasLabel` on by-id traversals — Design

## Overview

YouTrackDB's Gremlin layer makes a class scan polymorphic: `g.V().hasLabel("Parent")`
returns `Child` vertices because `YTDBGraphStep`'s class-scan branch resolves the
label through a SQL `FROM <type>` extent, which already walks the subtype tree. The
by-id form takes a different path. `g.V(childId).hasLabel("Parent")` loads the
element by id and filters it in memory with an exact label-string match that never
reads the step's `polymorphic` flag, so a `Child` is dropped even though it is a
`Parent`. That is YTDB-1159.

This design makes the by-id branch match labels polymorphically, reusing the same
logic the dedicated `YTDBHasLabelStep` already applies. It also fixes a second,
independent defect on the same `V(id).hasLabel(...)` path: `YTDBGraphCountStrategy`
rewrites `g.V(id).hasLabel(X).count()` into a whole-class count and silently
discards the id, so the count reflects every `X` in the class instead of the one
pinned vertex.

The enabling primitive is a single shared label-matching helper that both
`YTDBHasLabelStep` and the by-id branch call. Two independent label matchers that
drifted apart is the root cause; consolidating them removes the chance to drift
again.

What else changes to fit: the by-id branch separates label containers from the
rest before filtering; `YTDBGraphCountStrategy` gains an id guard that mirrors the
guard its sibling branch already has; the `YTDBHasLabelProcessTest` class gains
edge, multi-arg, and count coverage.

The sections below establish the shared concepts and class shape, then walk each of
the two bugs and the test strategy that guards them.

## Core Concepts

This design works with four existing ideas. Each is named and used without
re-definition below; each pairs the idea with the behavior it explains and points
at the section that elaborates it.

**By-id branch.** The path in `YTDBGraphStep.elements()` taken when the step carries
explicit element ids (`g.V(id...)`). It fetches those elements directly and filters
them in memory. This is where the bug lives. → "Bug 1 — by-id `hasLabel` ignores
polymorphism".

**Class-scan branch.** The path taken when the step has no ids (`g.V()`). It builds
a query whose `FROM <type>` extent is polymorphic by default and post-filters only
for non-polymorphic queries, so it already behaves correctly. → "Class Design".

**Polymorphic label match.** Testing a label predicate against an element's concrete
class name and, when the query is polymorphic, every superclass name. `YTDBHasLabelStep.filter()`
does this today; the by-id branch does not. → "Bug 1 — by-id `hasLabel` ignores
polymorphism".

**`hasLabel` folding.** `YTDBGraphStepStrategy` moves a `hasLabel` that directly
follows a GraphStep into that step's `HasContainer` list rather than building a
separate `YTDBHasLabelStep`. This is why the by-id branch, not the dedicated label
step, ends up responsible for matching the label. → "Workflow".

## Class Design

```mermaid
classDiagram
    class YTDBGraphStep {
        -boolean polymorphic
        -List~HasContainer~ hasContainers
        -elements(getByIds, getElement) Iterator
    }
    class YTDBHasLabelStep {
        -List~P~ predicates
        -boolean polymorphic
        +filter(traverser) boolean
    }
    class YTDBLabelMatcher {
        <<utility>>
        +matches(element, predicates, polymorphic)$ boolean
    }
    class YTDBGraphCountStrategy {
        +apply(traversal) void
    }
    YTDBGraphStep ..> YTDBLabelMatcher : by-id label containers
    YTDBHasLabelStep ..> YTDBLabelMatcher : predicate list
    YTDBGraphCountStrategy ..> YTDBGraphStep : reads ids + hasContainers
```

`YTDBLabelMatcher` is the new shared helper. Its single static method answers one
question: does this element satisfy any of these label predicates, given the
polymorphic flag? It holds exactly the logic that lives inline in
`YTDBHasLabelStep.filter()` today: for a YouTrackDB element, resolve the schema
class once, test each predicate against the concrete class name, and when the query
is polymorphic also test each predicate against every superclass name; for any
other element type, fall back to a string test against `element.label()`. The method
takes the predicate **list** (OR semantics, matching `YTDBHasLabelStep`'s
`anyMatch`) so the superclass walk runs once per element regardless of how many
labels a single `hasLabel` names. `YTDBHasLabelStep` delegates its whole predicate
list in one call; `YTDBGraphStep`'s by-id branch calls it once per label container
(each container's single predicate wrapped in a one-element list) and ANDs the
results. The helper has no state and no dependency on the step types, so neither
caller pulls the other's package in.

The `element.label()` fallback is never reached on the by-id path — by-id elements
come only from `YTDBVertexImpl` / `YTDBEdgeImpl`, both YouTrackDB elements. It exists
so the helper stays reusable from `YTDBHasLabelStep`, which can receive non-YouTrackDB
traversers mid-traversal.

## Workflow

```mermaid
flowchart TD
    A["YTDBGraphStep.elements()"] --> B{"ids present?"}
    B -- "yes (g.V(id))" --> C["fetch elements by id"]
    C --> D["split hasContainers:<br/>label vs non-label"]
    D --> E["non-label: HasContainer.testAll"]
    D --> F["label: YTDBLabelMatcher.matches<br/>per container (polymorphic-aware)"]
    E --> G["emit surviving elements"]
    F --> G
    B -- "no (g.V())" --> H["build polymorphic FROM query"]
    H --> I["non-polymorphic? post-filter labels"]
    I --> G
```

The diagram shows the fix's shape. The class-scan branch (right) is unchanged: the
`FROM <type>` extent is polymorphic, and the existing `!polymorphic` post-filter
narrows it back for non-polymorphic queries. The by-id branch (left) is what
changes. Today it runs one `HasContainer.testAll` over every container, which
applies an exact string match to label containers regardless of the flag. After the
fix it partitions the containers: non-label containers keep going through
`HasContainer.testAll`, and label containers route through `YTDBLabelMatcher`, which
honors the flag.

The reason the by-id branch carries label containers at all is folding.
`YTDBGraphStepStrategy` walks the traversal and, when a `hasLabel` directly follows
the GraphStep, moves its predicate into the step's `HasContainer` list. This applies
whether or not the step has ids, so `g.V(id).hasLabel("Parent")` arrives at
`elements()` with the label as a container and the ids set, landing in the by-id
branch with the label to match.

## Bug 1 — by-id `hasLabel` ignores polymorphism

**TL;DR.** `g.V(id).hasLabel("Parent")` drops a `Child` because the by-id branch
matches labels with an exact string test that ignores the polymorphic flag. The fix
splits label containers from the rest and matches labels through the shared
`YTDBLabelMatcher`, which tests the concrete class plus every superclass when
polymorphic. The same branch serves edges and multi-argument `hasLabel`, so the fix
covers `g.E(id).hasLabel(super)` and `g.V(id).hasLabel("A","B")` too.

The by-id branch today filters every loaded element with a single
`HasContainer.testAll` over the whole container list. For a label container that
test compares the element's concrete label string against the predicate, so a
`Child` never matches `Parent`. The class-scan branch avoids this because its
polymorphism comes from the SQL extent, not from an in-memory label test.

The fix partitions the containers once per traversal. The discriminator is the
container **key**: a container is a label container when
`T.label.getAccessor().equals(container.getKey())`, the same test
`YTDBGraphStepStrategy` and `YTDBHasLabelStep` use. This is deliberately not the
`YTDBGraphQueryBuilder.addCondition(...) == LABEL` test the class-scan branch uses
eight lines below: `addCondition` classifies only `eq` and `within` label predicates
as labels and demotes the rest (for example `has(T.label, neq("Child"))`) to
`NOT_CONVERTED`. Keying on the label string instead routes every label predicate to
the matcher, so the by-id path stays consistent with `YTDBHasLabelStep` for all label
predicate shapes, not just the `hasLabel`-generated `eq` / `within` ones.

Non-label containers (property predicates, id predicates) keep their exact
`HasContainer.testAll` semantics. Label containers route through
`YTDBLabelMatcher.matches`, called once per label container per element, combined
with AND across containers so `hasLabel("A").hasLabel("B")` still requires both.
Within a single multi-argument `hasLabel("A","B")`, the predicate is a `within` set,
and the matcher tests each candidate class name (concrete, then superclasses)
against that set, so a subtype of either `A` or `B` matches when polymorphic.

Edges share the branch: `vertices()` and `edges()` both call `elements()`, so
`g.E(edgeId).hasLabel("SuperEdge")` has the identical defect and the identical fix.

### Edge cases / Gotchas

- Non-polymorphic by-id queries keep exact matching: the matcher tests only the
  concrete class when the flag is false, so `gn().V(id).hasLabel("Parent")` stays 0
  for a `Child`.
- An element with no schema class returns no match, mirroring `YTDBHasLabelStep`'s
  existing guard.
- A non-YouTrackDB element on the by-id path (defensive, not expected) falls back to
  a string test against `element.label()`, matching `YTDBHasLabelStep`'s else-branch.
- `where(not(hasLabel(...)))` is unaffected: that label test is not folded into the
  GraphStep, so it runs through `YTDBHasLabelStep`, which is already correct.

### References

- D-records: D1, D2
- Invariants: the by-id branch and `YTDBHasLabelStep` produce identical label-match
  results for the same element, label predicate, and polymorphic flag — for every
  label predicate shape, which the key-based partition above guarantees.

## Bug 2 — count id-drop on `V(id).hasLabel(X).count()`

**TL;DR.** `YTDBGraphCountStrategy` rewrites a single-label-filter GraphStep into a
class count even when the step carries ids, discarding the id filter; the count then
reflects every `X` in the class. The fix requires an empty id set on the label-filter
branch, mirroring the guard its sibling branch already has. With ids present the
optimization is skipped and the traversal counts the by-id elements directly, which
are correct once Bug 1 lands.

The strategy has two branches that produce a class count. The empty-containers
branch (`g.V().count()`) already guards on `getIds().length == 0`. The label-filter
branch (`g.V().hasLabel(X).count()`) does not, so `g.V(id).hasLabel(X).count()`
matches it, extracts the label, removes every step, and installs a `YTDBClassCountStep`
that counts the whole class. The id is gone.

The fix adds the same `getIds().length == 0` condition to the label-filter branch.
When ids are present the branch no longer fires, no rewrite happens, and the
traversal executes normally: `YTDBGraphStep`'s by-id branch yields the matching
elements (polymorphism-correct after Bug 1) and `CountGlobalStep` counts them. This
is a distinct defect from the polymorphism bug — `YTDBClassCountStep` itself honors
the polymorphic flag through `countClass(cl, polymorphic)`; the defect is that the id
filter is dropped before the count runs.

The defect is masked by single-vertex test data, where the class count and the
single-id count coincide. It was reproduced with two `Child` vertices:
`gp().V(childId).hasLabel("Child").count()` returned 2 while `.toList().size()`
returned 1.

**Fix-order constraint.** The fall-through is correct only once Bug 1 is fixed: a
skipped rewrite hands the count to the by-id branch, which must already be
polymorphism-correct. If the Bug 2 guard landed before Bug 1, a polymorphic
`g.V(id).hasLabel("Parent").count()` would fall through to a by-id branch that still
drops the `Child` and would return 0 — a regression from the accidentally-correct
single-vertex class count. Both fixes therefore land in the same track, and the Bug
2 guard never lands in a commit earlier than the Bug 1 matcher.

### Edge cases / Gotchas

- `g.V(id).count()` with no `hasLabel` is already correct: empty containers plus
  non-empty ids fails the sibling branch's guard, so no rewrite happens.
- `g.V(id).hasLabel(A).has(prop).count()` is already correct: two containers fail
  the single-container precondition, so the strategy never fires.
- Skipping the optimization for the id case trades a class count for a
  fetch-filter-count over the id set. The id set is bounded by the query, so the
  cost is proportional to the ids the user named, not the class size.

### References

- D-records: D3
- Mechanics: the empirical reproduction is the count assertion in the brought-back
  test (see "Test strategy").

## Test strategy

**TL;DR.** Extend `YTDBHasLabelProcessTest`. Keep the four committed by-id and
has-id methods. Add a count-honors-id method (the reproduction brought back as a
permanent test), an edge by-id method, and a multi-argument by-id method. The class
runs through the `YTDBProcessTest` suite, which sets up the graph provider; a direct
`-Dtest` surefire run fails because the provider is never initialized.

The committed methods already pin the four corners of the polymorphism matrix:
by-id versus has-id, each polymorphic and non-polymorphic. The additions close the
gaps this design opened:

- **Count honors id.** Two vertices of the same concrete class, one pinned by id;
  assert `toList().size()` and `count()` agree at 1, for both polymorphic and exact
  labels. This is the reproduction from research, kept permanently; it fails today
  on the count assertion and guards Bug 2. Include the polymorphic supertype count
  case (`gp().V(childId).hasLabel("Parent").count()` with a second `Child` present),
  which would return 0 if the Bug 2 guard ever landed ahead of the Bug 1 matcher, so
  an out-of-order intermediate commit fails CI.
- **Edge by-id.** An edge class hierarchy, `g.E(edgeId).hasLabel(superEdge)`,
  polymorphic returns 1 and non-polymorphic returns 0. Guards the edge half of Bug 1.
- **Multi-argument by-id.** `g.V(childId).hasLabel("A","B")` where the vertex is a
  subtype of `A`; polymorphic matches, non-polymorphic does not. Guards the
  `within`-predicate path of Bug 1.

### Edge cases / Gotchas

- These scenario tests run only through the suite entry point. The suite is driven
  by `YTDBProcessTest`; a local single-class run uses
  `surefire:test@sequential-tests -Dtest=YTDBProcessTest -Dgremlin.tests=<fqcn>`.
- `checkSize` asserts `toList().size()` and `count()` agree, so it doubles as the
  guard that links Bug 1 and Bug 2: once Bug 1 fixes the list path, a remaining
  count id-drop would surface as a `checkSize` mismatch on multi-vertex data.

### References

- D-records: D1, D2, D3
