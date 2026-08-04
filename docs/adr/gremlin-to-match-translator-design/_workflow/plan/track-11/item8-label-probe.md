# Item 8 probe: does a start-step `as()` label resolve

The adjacent claim item 8 flags as unconfirmed is **refuted as written**. A user `as()` label on the start step does resolve: `g.V().as("a").select("a")` translates and engages a boundary step. What fails is every spelling that puts a `has()` or `hasLabel()` between the start and the consumer, and the reason is not the start step. TinkerPop's `FilterRankingStrategy` relocates the label off the `GraphStep` and onto the following `HasStep` before any provider strategy runs, and `HasStepRecogniser` never calls `bindStepLabels`. Item 8's named hypothesis — that `YTDBGraphStepStrategy` folds the `has()` into the `GraphStep` and drops the labels — is also refuted: that strategy runs *after* the translator, and its fold branch does copy labels (`YTDBGraphStepStrategy.java:131`).

Item 8 therefore grows a second fix site, but not the one it named. The site is `HasStepRecogniser.recognize`, one `bindStepLabels` call at the contribution point, measured to unblock all four failing spellings and the harness's IS1 shape with row parity intact.

## Measurement setup

Worktree `.claude/worktrees/t11-item8`, branch `ytdb-558-t11-item8-probe`, base `b7cc89b371`. The probe ran as a throwaway JUnit 4 class extending `GraphBaseTest`, deleted after the run; it is reproducible from the shapes below in about ten minutes.

Fixture: four vertices seeded in the order Carol (age 30), Alice (44), Bob (22), Acme (a `Company`), so insertion order matches neither name order nor age order and a RID-ordered result cannot be mistaken for a value-ordered one. Edges: Alice `knows` Bob, Alice `knows` Carol, Alice `worksAt` Acme.

The kill switch is toggled per arm through `session.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, …)`, never through `-DargLine`.

Two things are measured per spelling. Engagement is the count of `AbstractMatchPlanStep` instances in the step list after `applyStrategies()`: 1 means the walk translated, 0 means it declined to native. Rows are the canonicalised result multiset on both arms, compared for equality so a divergence cannot hide behind an engagement difference.

The "translator sees" column is reconstructed by cloning the traversal's strategy list, removing `GremlinToMatchStrategy` and `YTDBGraphStepStrategy` from the clone, and applying the rest. Other provider strategies still run, so the column is an approximation of the recognisers' input; the attribution in § Mechanism isolates the one strategy that matters.

## The four spellings

All four return identical rows on both arms. Engagement is the discriminator.

| # | Spelling | As written | Translator sees | Engaged | Rows (both arms) |
|---|---|---|---|---|---|
| S1 | `g.V().as("a").select("a")` | `GraphStep[a] -> SelectOneStep` | `GraphStep[a] -> SelectOneStep` | **1** | Carol, Alice, Bob, Acme |
| S2 | `g.V().has("name","Alice").as("a").select("a")` | `GraphStep -> HasStep[a] -> SelectOneStep` | `GraphStep -> HasStep[a] -> SelectOneStep` | 0 | Alice |
| S3 | `g.V().as("a").has("name","Alice").select("a")` | `GraphStep[a] -> HasStep -> SelectOneStep` | `GraphStep -> HasStep[a] -> SelectOneStep` | 0 | Alice |
| S4 | `g.V().hasLabel("Person").as("a").select("a")` | `GraphStep -> HasStep[a] -> SelectOneStep` | `GraphStep -> HasStep[a] -> SelectOneStep` | 0 | Bob, Carol, Alice |

S3 is the one that settles the question. The user wrote the label on the graph step; by the time the translator sees the traversal it sits on the `HasStep`. Where the label ends up, not where it was written, decides the outcome.

Two IS1-shaped variants behave the same way. `g.V().hasLabel("Person").has("age",44).as("p").out("knows").as("c").select("p","c")` engages 0, and so does the variant that writes `as("p")` on the graph step ahead of both filters — the relocation happens either way.

Replacing the bare `select("a")` with `select("a").values("name")` changes nothing: S1's analogue still engages 1, the other three still engage 0.

## Mechanism

### `FilterRankingStrategy` relocates the label

`FilterRankingStrategy` walks adjacent step pairs and, when the next step is a filter that does not itself consume the current step's labels, moves those labels forward onto it. Removing that one strategy from the cloned list and re-applying leaves the label where the user wrote it:

```text
with FilterRanking    : GraphStep -> HasStep[a] -> SelectOneStep
without FilterRanking : GraphStep[a] -> HasStep -> SelectOneStep
```

The move is sound at the TinkerPop level, because a filter does not transform the traverser: the labelled element is the same element before and after. That is also why binding the relocated label to the same boundary alias is the correct fix rather than a workaround.

`FilterRankingStrategy` is a standard optimisation strategy. It runs before every provider strategy, so the relocation is already done when `GremlinToMatchStrategy` starts its walk. No ordering change on the YouTrackDB side can avoid it.

### `HasStepRecogniser` never binds

Only two sites call `bindStepLabels` today: `StartStepRecogniser:141` (start step to `BOUNDARY_ALIAS`) and `GremlinPatternAssembler.claimFoldedHop:75` (folded vertex hop to the target alias). `HasStepRecogniser.recognize` reads `ctx.boundaryAlias()`, contributes filters through `putAliasFilter`, and returns `ACCEPTED` without ever looking at `hasStep.getLabels()`. A label parked there is dropped, so `SelectStepRecogniser` / `SelectOneStepRecogniser` cannot resolve it and the walk declines.

Confirmed by measurement. Adding one call at the contribution point,

```java
if (!ctx.bindStepLabels(hasStep, boundary)) {
  return Outcome.DECLINE;
}
```

flips S2, S3, S4 and both IS1 variants from engaged 0 to engaged 1, with row parity against native still holding on every one of them. The edit was reverted after the run; no production change is committed.

### The `YTDBGraphStepStrategy` hypothesis is refuted

Two independent reasons, either one sufficient.

Ordering. `YTDBGraphStepStrategy.applyPrior()` returns `Set.of(GremlinToMatchStrategy.class)`, so the translator runs first and never sees a folded `YTDBGraphStep`. Whatever that strategy does to labels cannot affect translation.

The fold preserves labels anyway. `rebuildTraversal` copies them at line 131 (`current.getLabels().forEach(currentGraphStep::addLabel)`), which is what `TraversalHelper.copyLabels` would do, and the source `HasStep` is removed immediately after. Measured with the translator off: `g.V().has("name","Alice").as("a").select("a")` ends as `YTDBGraphStep[a] -> SelectOneStep` and returns Alice.

## Adjacent defect: the `hasLabel` replacement branch does drop labels

The `else` branch of `rebuildTraversal` — a `HasStep` that does not directly follow a `GraphStep` — inserts a `YTDBHasLabelStep` carrying the extracted `~label` predicates and removes the original `HasStep` when all its containers were consumed. It copies no labels to the replacement. The label is lost, and this is a wrong answer rather than a decline, because it lands on the native pipeline.

Measured with the translator off:

```text
g.V().out("knows").hasLabel("Person").as("a").select("a")
  post-strategy : YTDBGraphStep -> VertexStep -> NoOpBarrierStep -> YTDBHasLabelStep -> SelectOneStep
  rows          : []

g.V().out("knows").hasLabel("Person")            (oracle, same element set)
  post-strategy : YTDBGraphStep -> VertexStep -> NoOpBarrierStep -> YTDBHasLabelStep
  rows          : [Carol, Bob]
```

`select("a")` finds no `a` in the path and drops every traverser. The correct answer is Carol and Bob.

This is pre-existing, not branch-introduced: `git show origin/develop:…/YTDBGraphStepStrategy.java` has the identical `else` branch with no label copy, and the measurement runs with the translator off. It was not separately re-measured on a `develop` worktree, because this probe stayed confined to its own.

The fix is `TraversalHelper.copyLabels(hch, ytdbHasLabelStep, true)` after the `addStep`. It is translator-independent and belongs in its own issue, not in item 8.

## Falsifiability

The engagement assertions can redden. Commenting out the `bindStepLabels` call at `StartStepRecogniser:141` drops S1 from engaged 1 to engaged 0 while leaving S2 through S4 unchanged, and the assertion failed as expected. The same run carried the `HasStepRecogniser` bind described above, so both directions were demonstrated together: six of eight probe tests failed, each on the engagement assertion and none on row parity. Both source edits were reverted.

## Sizing note

**Item 8 grows a second fix site, and the adjacent claim is re-aimed rather than retired.** The second site is `HasStepRecogniser.recognize`, not any start-step site — `StartStepRecogniser:141` already works and needs no change. Both sites are the same one-line `bindStepLabels`-or-decline call at a contribution point, so the two together stay a single small step; the cost is in the tests, not the code.

Three consequences for whoever implements it.

The `HasStep` bind is measured to be correct on this fixture, but it widens the recognised set, so it needs the same watch-it-fail discipline item 8 already specifies for the edge bind. Write each test against the pre-fix decline first.

The harness's `is1FullProfile` on branch `t11-item7-jmh` asserts `requireNotTranslated` and will fail once the bind lands. That is the intended signal recorded in the harness's own commit message; the shape moves from the declining group to the translating group. Its Javadoc also needs correcting — it says `as("p")` "sits on the start step", and the label is on the `HasStep`.

The native-path `hasLabel` label drop found above is a separate, pre-existing defect and should not be folded into item 8.
