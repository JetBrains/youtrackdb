# Cucumber baseline — both feature runners, both kill-switch arms

Both TinkerPop feature runners execute 1930 scenarios. With the translator on they fail the same
27 scenarios, scenario name for scenario name; with it off both are green. All four arms were
measured in one sitting at `fe4b5cc95d`, one plain `-D` apart. Every compliance failure either
runner reports is therefore a regression this branch introduces, which puts `embedded`'s residue
in step 5's scope on the same terms DR-S2 set for `core`'s. Two figures the track file left open
are closed here: `embedded`'s off arm, which nothing had measured, and the unexplained
1931-against-1930 scenario count, which turns out to be a surefire accounting artifact rather than
an extra scenario.

## The four arms at `fe4b5cc95d`

| Runner | Arm | Scenarios | Failures | Errors | Skipped | Wall | Source |
|---|---|---|---|---|---|---|---|
| `core` (`YTDBGraphFeatureTest`) | on | 1930 | 27 | 0 | 14 | 17.32 s | measured here |
| `core` (`YTDBGraphFeatureTest`) | off | 1930 | 0 | 0 | 14 | 16.92 s | measured here |
| `embedded` (`EmbeddedGraphFeatureTest`) | on | 1930 | 27 | 0 | 14 | 17.75 s | measured here |
| `embedded` (`EmbeddedGraphFeatureTest`) | off | 1930 | 0 | 0 | 14 | 17.67 s | measured here |

The surefire lines the table reads, verbatim. The `embedded` rows report 1931 because the module's
single surefire test set also holds `ShadedJarSmokeTest`; see the count section below.

```text
core   on  [ERROR]   Tests run: 1930, Failures: 27, Errors: 0, Skipped: 14, Time elapsed: 17.32 s <<< FAILURE! -- in )
core   off [WARNING] Tests run: 1930, Failures: 0, Errors: 0, Skipped: 14, Time elapsed: 16.92 s -- in )
embed  on  [ERROR]   Tests run: 1931, Failures: 27, Errors: 0, Skipped: 14, Time elapsed: 17.75 s <<< FAILURE! -- in )
embed  off [WARNING] Tests run: 1931, Failures: 0, Errors: 0, Skipped: 14, Time elapsed: 17.67 s -- in )
```

The test-set name renders as `)` on every row. The JUnit47 provider takes its test-set name from
the cucumber-junit runner's description, which is not a class name, so the label carries no
information and is the same on both runners.

## The code state these figures describe

`fe4b5cc95d245bb16beefa4dedc335784e2c1943`, plus the `EmbeddedTranslatorKillSwitchWitnessTest` the
same commit adds. That commit changes no other file under `core/` or `embedded/`, so the runs
describe the commit's code state exactly. The forked JVM stamps the SHA into its own log, which is
how the figures are tied to a commit rather than to an assertion:

```text
Storage 'memory:killswitchwitness' is created under YouTrackDB distribution : 0.5.0-SNAPSHOT
(build fe4b5cc95d245bb16beefa4dedc335784e2c1943, branch gremlin-to-match-translator-design)
```

## Commands

`core`, both arms, translator-off shown:

```bash
./mvnw -pl core -o test-compile surefire:test@gremlin-feature-compliance-tests \
  -Dyoutrackdb.query.gremlin.toMatchTranslator.enabled=false \
  -Dmaven.test.failure.ignore=true
```

`embedded`, both arms, preceded once by the install:

```bash
./mvnw -pl core -am install -DskipTests
./mvnw -pl embedded test \
  -Dyoutrackdb.query.gremlin.toMatchTranslator.enabled=false \
  -Dmaven.test.failure.ignore=true
```

Three properties of these commands are load-bearing. `test-compile` runs in the same invocation
because the surefire `test` mojo declares `<phase>test</phase>` with no `executePhase`, so a bare
`surefire:test@<id>` measures whatever was last compiled (T22). The kill-switch is a plain `-D` and
never rides inside `-DargLine=`: on `core` a command-line `argLine` replaces the POM's `-ea` / heap
/ `--add-opens` block wholesale, and on `embedded` it is inert, because that module declares
`<argLine>` inline in the surefire `<configuration>` where plugin config beats the user property
(R9). The install is not optional for `embedded` — `-pl embedded` leaves `core` out of the reactor,
so both the `core` jar and its test-jar resolve from `~/.m2`, and the test-jar carries the local
feature files the runner reads.

## The off arm is self-witnessed

`EmbeddedTranslatorKillSwitchWitnessTest` runs in the same fork as the scenarios and prints what
the fork actually received alongside the plan it actually built:

```text
off arm: [kill-switch witness] youtrackdb.query.gremlin.toMatchTranslator.enabled=false boundarySteps=0
on  arm: [kill-switch witness] youtrackdb.query.gremlin.toMatchTranslator.enabled=null  boundarySteps=1
```

The test asserts the coupling rather than printing it: with the property explicitly `false` a bare
`g.V()` must carry no `AbstractMatchPlanStep` after `applyStrategies()`, and with the property unset
or true it must carry exactly one. The on-arm branch is the positive control. A switch that never
reached the fork leaves the property unset, so the assertion still demands the on-arm shape. An
off-arm run that failed to turn the translator off therefore fails the test rather than publishing
the on-arm number twice. `embedded` has no known-good scenario count that would expose that mistake
by itself, which is why R9 asks for the witness on this runner in particular.

## Why `embedded` reports 1931 where `core` reports 1930

The extra unit is `ShadedJarSmokeTest.embeddedInMemoryDatabaseRoundTrip`, not a scenario. Surefire
folds it into the same test set as the Cucumber suite, so the set's count is 1930 scenarios plus one
JUnit method. `TEST-_.xml` from the on-arm run holds 1931 `<testcase>` elements, one of which is
`classname="com.jetbrains.youtrackdb.shade.ShadedJarSmokeTest"`. The same folding explains the CI
figure: at `b35ac67d2f` the module's Results summary reads 1931 for a module whose `test` phase
then held exactly two test classes. Both runners run the same 1930 scenarios with the same 14
skips. The track file's `## Artifacts and Notes` recorded the discrepancy as unexplained; it is
resolved, and it was never a difference in scenario coverage.

## The 27 failing scenarios

Identical on both runners: a set difference in either direction over the two on-arm reports is
empty. The two runners share one failure surface, and the `embedded` module's shaded packaging
adds nothing to it.

| Feature | Scenario |
|---|---|
| Step - AdjacentToIncidentStrategy | `g_withStrategiesXAdjacentToIncidentStrategyX_V_whereXoutX` |
| Step - addV | `g_V_hasXname_markoX_propertyXfriendWeight_outEXknowsX_weight_sum__acl_privateX` |
| Step - addV | `get_g_addVXpersonX_propertyXsingle_name_stephenX_propertyXsingle_name_stephenm_since_2010X` |
| Step - and | `g_V_andXoutE__hasXlabel_personX_and_hasXage_gte_32XX_name` |
| Step - and | `g_V_asXaX_outXknowsX_and_outXcreatedX_inXcreatedX_asXaX_name` |
| Step - count | `g_V_group_byXlabelX_count` |
| Step - count | `g_V_order_byXlangX_count` |
| Step - count | `g_V_order_byXnoX_count` |
| Step - elementMap | `g_V_elementMap` |
| Step - group | `g_V_group_byXageX` |
| Step - groupCount | `g_V_groupCount_byXageX` |
| Step - groupCount | `g_V_outXcreatedX_name_groupCount` |
| Step - mean | `g_V_age_mean` |
| Step - merge | `g_V_hasXname_markoX_elementMap_mergeXV_hasXname_lopX_elementMapX` |
| Step - not | `g_V_notXhasXname_gt_27XX_name` |
| Step - order | `g_V_name_order` |
| Step - order | `g_V_order_byXageX` |
| Step - select | `g_V_asXaX_selectXaX_byXageX` |
| Step - select | `g_V_asXa_nX_selectXa_nX_byXageX_byXnameX` |
| Step - sum | `g_V_foo_sum` |
| Step - valueMap | `g_V_hasLabelXpersonX_filterXoutEXcreatedXX_valueMapXtrueX` |
| Step - valueMap | `g_V_hasLabelXpersonX_filterXoutEXcreatedXX_valueMap_withXtokensX` |
| Step - valueMap | `g_V_valueMapXname_ageX_withXtokensX` |
| Step - valueMap | `g_V_valueMapXtrueX` |
| Step - valueMap | `g_V_valueMapXtrue_name_ageX` |
| Step - valueMap | `g_V_valueMap_withXtokensX` |
| Step - where | `g_V_asXaX_outXcreatedX_asXbX_whereXandXasXbX_in__notXasXaX_outXcreatedX_hasXname_rippleXXX_selectXa_bX` |

## Earlier figures, and what they are still good for

### The CI pair at `b35ac67d2f`

Workflow run `30787491550`, Linux arm JDK 21, job `91604128756`. Excerpted rather than linked
because Actions logs expire:

```text
core     2026-08-03T05:53:24Z [ERROR] Tests run: 1930, Failures: 41, Errors: 0, Skipped: 14, Time elapsed: 16.09 s <<< FAILURE! -- in )
embedded 2026-08-03T06:01:01Z [ERROR] Tests run: 1931, Failures: 41, Errors: 0, Skipped: 14, Time elapsed: 14.57 s <<< FAILURE! -- in )
```

Both rows carry qualifications. CI never sets the kill-switch, so they are on-arm figures and the
pipeline supplies no control. And 41 is the transient number: step 1's first commit made the
translator root-only, which withdrew one scenario from the failing set, and the review fix at
`107de3ef34` restored both. The pre-triage `core` figure is 42.

### The pre-fix and post-fix `core` figures

| SHA | Arm | Figure | Provenance |
|---|---|---|---|
| `55da40dcdd` | on | 1930 / 42 / 14 | orchestrator-verified |
| `55da40dcdd` | off | 1930 / 0 / 14 | orchestrator-verified |
| `c4d9d67ae7` | on | 1930 / 27 / 14 | implementer-measured |

42 is the pre-fix number: it precedes step 3's per-alias filter binding. 27 is post-fix, and the
42 → 27 drop is what the dropped-filter family was expected to produce. Their run logs were
session-scoped files under `/tmp` and are gone, so this artifact carries the figures and their
provenance without excerpts. The four-arm table above supersedes them at a later SHA and reproduces
27 on `core`, which settles the one residue DR-S3 left open — that 27 was implementer-measured
where 42 and 0 were orchestrator-verified.

## This baseline is not the handoff

Step 5 fixes the residue and step 6 re-takes all four arms at the track's final commit, so every
figure here is superseded by construction (R8). The rule that makes it explicit: a published
baseline is stale once `git log <sha>..HEAD -- core embedded` is non-empty, and this artifact's own
commit adds a test file under `embedded/`, so it invalidates itself the moment it lands. That is
intended. What this baseline is for is the comparison step 5 works against and the attribution
question DR-S2 asked — both runners green with the translator off means the whole residue belongs
to this branch, on both.
