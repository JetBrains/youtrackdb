package com.jetbrains.youtrackdb.internal.core.gremlin.translator;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener.QueryDetails;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * End-to-end smoke tests for the now-registered {@link GremlinToMatchStrategy}. These are the
 * first tests to run a real {@code graph.traversal()} through the full strategy chain (the
 * strategy is registered globally for the embedded graph, so every traversal here is subject to
 * it), so they verify that:
 *
 * <ul>
 *   <li>the translator-first ordering actually holds — a recognized {@code g.V()} shape ends up
 *       with exactly one {@link YTDBMatchPlanStep} after {@code applyStrategies()}, which only
 *       happens if the translator runs <em>before</em> {@code YTDBGraphStepStrategy} would fold
 *       the start step into a {@code YTDBGraphStep} (the ordering wired via the three {@code
 *       applyPrior()} edits);</li>
 *   <li>translator-on and translator-off return the same result multiset for every recognized
 *       shape (order is not pinned — MATCH's planner may reorder);</li>
 *   <li>the recognized set is exactly the bare vertex source: any follow-up step, an edge start,
 *       or a duplicate-id source declines to the native pipeline;</li>
 *   <li>the kill-switch and idempotency gates behave as designed;</li>
 *   <li>query monitoring survives translation — a translated {@code g.V()} records exactly one
 *       {@link QueryDetails} whose summary and query string are unaffected by the boundary
 *       splice.</li>
 * </ul>
 *
 * <p>Engagement is asserted by counting {@link YTDBMatchPlanStep} occurrences across the whole
 * step list (must be exactly one on a recognized shape, zero on a declined one), rather than by
 * only inspecting the start step: the design invariant is "exactly one boundary step after
 * {@code applyStrategies()}", and counting is the direct expression of it.
 */
public class GremlinToMatchSmokeTest extends GraphBaseTest {

  /**
   * A recognized {@code g.V()} must end up with exactly one {@link YTDBMatchPlanStep} after
   * {@code applyStrategies()} and return every vertex in the graph. Counting the boundary step
   * across the whole step list (not just the start step) is the direct expression of the
   * one-boundary-step invariant; it would fail under the backup's reversed ordering, where the
   * translator ran after {@code YTDBGraphStepStrategy} and never saw a plain {@code GraphStep}.
   */
  @Test
  public void translatesBareGvExactlyOneBoundaryStepAndReturnsAllVertices() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.tx().commit();

    var admin = graph.traversal().V().asAdmin();
    admin.applyStrategies();

    assertThat(countBoundarySteps(admin.getSteps()))
        .as("a recognized g.V() must contain exactly one YTDBMatchPlanStep after applyStrategies()")
        .isEqualTo(1);

    var names =
        admin.toList().stream().map(v -> (String) v.value("name")).sorted().toList();
    assertThat(names).isEqualTo(List.of("Alice", "Bob", "Carol"));
  }

  /**
   * Translator-on vs translator-off parity: the same {@code g.V()} shape must return the same
   * result multiset with the strategy enabled and disabled. Order is not pinned (MATCH reorders),
   * so parity is asserted on sorted name multisets. Also confirms engagement is opposite in the
   * two runs — a boundary step on, a {@code YTDBGraphStep} off.
   */
  @Test
  @SuppressWarnings("DataFlowIssue")
  public void translatorOnVsOffReturnsSameMultiset() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.addVertex(T.label, "Person", "name", "Alice"); // deliberate duplicate value
    graph.tx().commit();

    var originalValue =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      // Translator ON.
      session
          .getConfiguration()
          .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
      var on = graph.traversal().V().asAdmin();
      on.applyStrategies();
      assertThat(countBoundarySteps(on.getSteps()))
          .as("translator on → exactly one boundary step")
          .isEqualTo(1);
      var namesOn = on.toList().stream().map(v -> (String) v.value("name")).sorted()
          .toList();

      // Translator OFF.
      session
          .getConfiguration()
          .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
      var off = graph.traversal().V().asAdmin();
      off.applyStrategies();
      assertThat(countBoundarySteps(off.getSteps()))
          .as("translator off → no boundary step")
          .isEqualTo(0);
      assertThat(off.getStartStep())
          .as("translator off → start step must remain a YTDBGraphStep")
          .isInstanceOf(YTDBGraphStep.class);
      var namesOff = off.toList().stream().map(v -> (String) v.value("name")).sorted()
          .toList();

      // Multiset equality (sorted lists preserve multiplicity — the duplicate "Alice" appears
      // twice in both).
      assertThat(namesOn)
          .as("translator-on and translator-off multisets must match")
          .isEqualTo(namesOff);
      assertThat(namesOn).isEqualTo(List.of("Alice", "Alice", "Bob", "Carol"));
    } finally {
      session
          .getConfiguration()
          .setValue(
              GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, originalValue);
    }
  }

  /**
   * Liveness: a translated {@code g.V()} that engages the boundary step must run to completion and
   * return every vertex. Termination is the evidence here — a translated path that hung would never
   * reach the count assertion — so the test pins the full result count rather than a wall-clock
   * bound. A ceiling would be flaky under CI load, and {@code @Test(timeout=…)} does not fit: it
   * runs the body on a fresh thread where the thread-bound YouTrackDB session is inactive. The
   * translator-vs-native performance comparison is a later hardening task.
   */
  @Test
  public void translatedTraversalExecutesInFiniteTime() {
    for (var i = 0; i < 50; i++) {
      graph.addVertex(T.label, "Person", "name", "P" + i);
    }
    graph.tx().commit();

    var admin = graph.traversal().V().asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps())).isEqualTo(1);

    var count = admin.toList().size();

    // toList() returning all 50 rows is itself the finite-time evidence: a hung translation would
    // never reach this line. See the method Javadoc for why there is no wall-clock ceiling.
    assertThat(count).as("translated g.V() must return every vertex").isEqualTo(50);
  }

  /**
   * A translated bare {@code g.V()} on an EMPTY graph returns empty end-to-end. This exercises the
   * class-scan path (no RID hint, no filter — {@code SELECT FROM V}), which is structurally
   * different from the by-RID fast path covered by
   * {@code translatedSingleIdLookup_nonExistentRid_returnsEmpty}: it proves the real class-scan
   * plan produces an empty stream on a class with no rows rather than throwing or emitting a
   * phantom row. The mocked {@code iterator_emptyStream} unit only pins the boundary iterator's
   * empty-stream handling, not the real plan's output.
   */
  @Test
  public void translatedBareVertexSource_emptyGraph_returnsEmpty() {
    var admin = graph.traversal().V().asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps()))
        .as("a bare g.V() must still translate to one boundary step on an empty graph")
        .isEqualTo(1);

    assertThat(admin.toList())
        .as("translated bare g.V() on an empty graph must return empty")
        .isEmpty();
  }

  /**
   * Single-id lookup {@code g.V(id)} translates and returns exactly the addressed vertex, with
   * its property intact.
   */
  @Test
  public void translatesSingleIdLookup() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    var admin = graph.traversal().V(bob.id()).asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps())).isEqualTo(1);

    var vertices = admin.toList().stream().toList();
    assertThat(vertices).hasSize(1);
    assertThat(vertices.getFirst().id()).isEqualTo(bob.id());
    assertThat((String) vertices.getFirst().value("name")).isEqualTo("Bob");
  }

  /**
   * A single-id lookup {@code g.V(id)} where {@code id} is a syntactically valid RID that
   * addresses no stored record still TRANSLATES (the recogniser accepts any convertible,
   * non-duplicate id) and returns an empty result end-to-end, matching native {@code g.V(id)}.
   * The mocked {@code iterator_emptyStream} unit only proves the boundary iterator handles an
   * empty stream; this proves the real {@code SELECT FROM #missing:rid} MATCH plan actually
   * produces an empty stream against live storage — it does not throw or emit a phantom row.
   */
  @Test
  public void translatedSingleIdLookup_nonExistentRid_returnsEmpty() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    // Build a RID that provably addresses no stored record: add a throwaway vertex, commit so it is
    // assigned a real persisted collection:position, capture that RID, then delete the vertex. A
    // deleted record's RID makes the non-existence structural — it holds however many vertices the
    // fixture has, because nothing is inserted after the delete to reoccupy the freed slot. A
    // hardcoded high position (the earlier 999_999L) was empty only by luck of the fixture size,
    // and a negative position is worse: that is the #-1:-1 new-record placeholder the recogniser
    // treats as a degenerate lookup whose result diverges from the native path.
    var doomed = graph.addVertex(T.label, "Person", "name", "Doomed");
    graph.tx().commit();
    var missing = doomed.id();
    doomed.remove();
    graph.tx().commit();

    var admin = graph.traversal().V(missing).asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps()))
        .as("a well-formed non-existent RID must still translate to one boundary step")
        .isEqualTo(1);

    assertThat(admin.toList())
        .as("translated g.V(missingRid) must return empty, matching native g.V(missingRid)")
        .isEmpty();
  }

  /**
   * Multi-id lookup with distinct ids {@code g.V(id1, id2)} translates and returns exactly the
   * two addressed vertices. Order is not pinned, so the assertion is on id/name sets.
   */
  @Test
  public void translatesMultiIdLookupDistinctIds() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    graph.tx().commit();

    var admin = graph.traversal().V(alice.id(), carol.id()).asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps())).isEqualTo(1);

    var returned = admin.toList().stream().toList();
    var idsReturned = returned.stream().map(Vertex::id).collect(Collectors.toSet());
    var namesReturned =
        returned.stream().map(v -> (String) v.value("name")).collect(Collectors.toSet());
    assertThat(idsReturned).isEqualTo(Set.of(alice.id(), carol.id()));
    assertThat(namesReturned).isEqualTo(Set.of("Alice", "Carol"));
  }

  /**
   * Multi-id lookup mixing an existing and a non-existent RID {@code g.V(existing, missing)}
   * translates (both ids are distinct and convertible) and returns only the existing vertex,
   * matching native. The single-id non-existent case is covered by {@code
   * translatedSingleIdLookup_nonExistentRid_returnsEmpty}; this pins the multi-id {@code @rid IN
   * [...]} path against a partially-satisfiable list — a plan that emitted a phantom row for the
   * missing RID, or dropped the existing one, would surface only here.
   */
  @Test
  public void translatedMultiIdLookup_mixedExistingAndMissing_returnsOnlyExisting() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    // A RID that provably addresses no stored record: add a vertex, commit so it is assigned a real
    // collection:position, capture that RID, then delete the vertex (see the single-id variant).
    var doomed = graph.addVertex(T.label, "Person", "name", "Doomed");
    graph.tx().commit();
    var missing = doomed.id();
    doomed.remove();
    graph.tx().commit();

    var admin = graph.traversal().V(alice.id(), missing).asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps()))
        .as("a distinct existing+missing id pair must still translate to one boundary step")
        .isEqualTo(1);

    var ids = admin.toList().stream().map(Vertex::id).collect(Collectors.toSet());
    assertThat(ids)
        .as("translated g.V(existing, missing) must return only the existing vertex")
        .isEqualTo(Set.of(alice.id()));
  }

  /**
   * A {@code g.V(id, id)} source with a <em>duplicate</em> id declines to native. An {@code @rid
   * IN [...]} filter has set semantics (one emission per distinct rid), while native {@code
   * g.V(ids)} emits once per list entry, so a repeated id would break multiset equality — the
   * recognizer declines it. Parity is checked against the native pipeline (translator off) to pin
   * the exact multiset the declined shape must reproduce.
   */
  @Test
  public void duplicateIdSourceDeclinesToNativeMultiset() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    // With the translator ON the duplicate-id shape must decline (no boundary step): the
    // recognizer refuses it because @rid IN cannot reproduce the once-per-occurrence multiset.
    var admin = graph.traversal().V(alice.id(), alice.id()).asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps()))
        .as("duplicate-id source must decline (no boundary step)")
        .isEqualTo(0);

    // The declined shape runs natively and emits the vertex once per list entry: two emissions.
    var native2 = admin.toList();
    assertThat(native2).as("native g.V(id, id) emits the vertex once per list entry").hasSize(2);
  }

  /**
   * An edge start {@code g.E()} declines: Phase 1 recognizes vertex-rooted patterns only. The
   * step list keeps its native {@code YTDBGraphStep} (edge return class), and no boundary step is
   * spliced.
   */
  @Test
  public void edgeStartDeclines() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    var admin = graph.traversal().E().asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps()))
        .as("g.E() must decline (no boundary step)")
        .isEqualTo(0);
    assertThat(admin.getStartStep())
        .as("g.E() must remain on a YTDBGraphStep")
        .isInstanceOf(YTDBGraphStep.class);
    var graphStep = (YTDBGraphStep<?, ?>) admin.getStartStep();
    assertThat(graphStep.getReturnClass()).isEqualTo(Edge.class);
  }

  /**
   * An unrecognized follow-up step declines the whole traversal under all-or-nothing. Now that the
   * folded {@code out("knows")} hop translates, this uses a trailing {@code count()} — which has no
   * recognizer in this track — as the unrecognized step: the walker recognizes the vertex source and
   * the {@code out} hop, then hits the {@code CountGlobalStep} with no registry entry, so the whole
   * traversal declines and stays on the native pipeline with no boundary step spliced. The walker
   * has no step-count gate — it walks the whole step list and declines at the first unrecognized
   * step class.
   */
  @Test
  public void followUpStepDeclinesUnrecognizedStep() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    var admin = graph.traversal().V().out("knows").count().asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps()))
        .as("an unrecognized follow-up step must decline the whole traversal")
        .isEqualTo(0);

    // The declined shape still executes correctly on the native pipeline: one knows-neighbour (Bob).
    assertThat(admin.toList()).isEqualTo(List.of(1L));
  }

  /**
   * A {@code g.V().has("name", "Alice")} start declines. Because the translator runs before {@code
   * YTDBGraphStepStrategy} folds the {@code has} into the start step, at translator time the
   * traversal is still {@code [GraphStep, HasStep]}: the walker recognizes the vertex source but no
   * recognizer claims the {@code HasStep} in this track, so under all-or-nothing the whole traversal
   * declines. The native folder then collapses the {@code has} into a {@code YTDBGraphStep} and
   * handles the shape.
   */
  @Test
  public void startWithHasContainerDeclines() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    var admin = graph.traversal().V().has("name", "Alice").asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps()))
        .as("g.V().has(...) must decline (no boundary step)")
        .isEqualTo(0);

    var names =
        admin.toList().stream().map(v -> (String) v.value("name")).sorted().toList();
    assertThat(names).isEqualTo(List.of("Alice"));
  }

  /**
   * Kill-switch round-trip on the shared strategy singleton: flag {@code true → false → true}.
   * With the flag off the strategy declines (no boundary step); flipping it back on re-engages on
   * the same singleton, proving the flag is read fresh per {@code apply()} rather than cached at
   * registration.
   */
  @Test
  @SuppressWarnings("DataFlowIssue")
  public void killSwitchRoundTripOffThenOn() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    var originalValue =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      session
          .getConfiguration()
          .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
      var off = graph.traversal().V().asAdmin();
      off.applyStrategies();
      assertThat(countBoundarySteps(off.getSteps()))
          .as("kill-switch off → no boundary step")
          .isEqualTo(0);
      assertThat(off.getStartStep())
          .as("kill-switch off → start step must remain a YTDBGraphStep")
          .isInstanceOf(YTDBGraphStep.class);

      session
          .getConfiguration()
          .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
      var on = graph.traversal().V().asAdmin();
      on.applyStrategies();
      assertThat(countBoundarySteps(on.getSteps()))
          .as("kill-switch back on → exactly one boundary step")
          .isEqualTo(1);
    } finally {
      session
          .getConfiguration()
          .setValue(
              GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, originalValue);
    }
  }

  /**
   * Idempotency: re-invoking the strategy directly on an already-translated traversal is a
   * no-op. After {@code applyStrategies()} splices the boundary step, applying the singleton again
   * must leave the step list identical (same instances, same order) and still produce the correct
   * result — the idempotency scan finds the existing boundary step and returns immediately.
   */
  @Test
  public void strategyReapplyIsNoOp() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    var admin = graph.traversal().V().asAdmin();
    admin.applyStrategies();
    var stepsAfterFirst = new ArrayList<>(admin.getSteps());
    var startAfterFirst = admin.getStartStep();

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(new ArrayList<>(admin.getSteps()))
        .as("step list must be identical after re-applying the strategy")
        .isEqualTo(stepsAfterFirst);
    assertThat(admin.getStartStep())
        .as("start step instance must be unchanged")
        .isEqualTo(startAfterFirst);
    assertThat(countBoundarySteps(admin.getSteps())).isEqualTo(1);

    var names =
        admin.toList().stream().map(v -> (String) v.value("name")).sorted().toList();
    assertThat(names).isEqualTo(List.of("Alice", "Bob"));
  }

  /**
   * A bare {@code g.V()} must return subclass instances too — it never narrows by {@code @class}.
   * With two vertex classes ({@code Person}, {@code Place}), the translated {@code g.V()} returns
   * both, matching the native polymorphic-by-default behavior. Narrowing to the exact root class
   * would wrongly drop the subclass instances.
   */
  @Test
  public void bareGvReturnsAllVertexClassesPolymorphically() {
    session.createVertexClass("Person");
    session.createVertexClass("Place");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Place", "name", "Berlin");
    graph.tx().commit();

    var admin = graph.traversal().V().asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps())).isEqualTo(1);

    var labels = admin.toList().stream().map(Element::label).sorted().toList();
    assertThat(labels).isEqualTo(List.of("Person", "Place"));
  }

  /**
   * Query monitoring survives translation. This is the empirical proof that the translator does
   * not break {@link QueryMetricsListener}: with monitoring enabled and a {@code querySummary}
   * configured via {@code with(...)}, a translated {@code g.V()} records exactly one {@link
   * QueryDetails} whose:
   *
   * <ul>
   *   <li>{@code getQuerySummary()} equals the configured summary — read from the {@code
   *       OptionsStrategy}, decoupled from the step list the translator rewrote;</li>
   *   <li>{@code getQuery()} renders the original {@code g.V()}-shaped Gremlin (from {@code
   *       traversal.getBytecode()}, fixed at construction), <em>not</em> the boundary step — the
   *       one assumption this test nails: the boundary splice does not leak into the recorded
   *       query;</li>
   *   <li>callback fires exactly once (no double-count from the MATCH execution path);</li>
   *   <li>the result count matches the fixture.</li>
   * </ul>
   *
   * <p>The metrics step is a {@code FinalizationStrategy}, so it is appended <em>after</em> the
   * translator (a {@code ProviderOptimizationStrategy}) has run {@code replaceAllSteps}; the
   * assertion below proves that ordering holds in practice, not just in theory.
   */
  @Test
  public void queryMonitoringSurvivesTranslation() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.tx().commit();

    var summary = "smoke-metrics-summary";
    var listener = new RememberingListener();
    ((YTDBTransaction) graph.tx())
        .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
        .withQueryListener(listener);
    graph.tx().open();

    var gs = graph.traversal().with(YTDBQueryConfigParam.querySummary, summary);

    // Compile the monitored traversal without draining it. applyStrategies() runs the translator
    // (rewriting g.V() to the boundary step) and appends the metrics step, so the boundary-step
    // count below is read off the freshly compiled step list rather than off state left behind by a
    // drain. applyStrategies() sets up steps only; the metrics listener fires when the traversal
    // runs and closes.
    var q = gs.V().asAdmin();
    q.applyStrategies();
    var boundaryEngaged = countBoundarySteps(q.getSteps()) == 1;

    // Drain exactly once. toList() runs the already-compiled traversal and closes it, firing the
    // metrics listener a single time. The traversal is intentionally NOT wrapped in
    // try-with-resources: a monitored toList() already closes the traversal, so an extra close() on
    // block exit would re-fire the metrics step — a benign framework double-close, identical on the
    // native pipeline and unrelated to translation, but it would mask a real double-count here.
    // applyStrategies() above already locked the strategy chain, so toList() neither re-translates
    // nor re-appends the metrics step. One drain, one close, one fire — so the exactly-once
    // assertion below actually tests the translation path.
    var rowCount = q.toList().size();
    graph.tx().commit();

    assertThat(boundaryEngaged)
        .as("monitored g.V() must still be translated to exactly one boundary step")
        .isTrue();
    assertThat(listener.callCount).as("exactly one QueryDetails must be recorded").isEqualTo(1);
    assertThat(listener.query).as("a query string must be captured").isNotNull();
    assertThat(listener.querySummary)
        .as("querySummary must round-trip through the OptionsStrategy unaffected by translation")
        .isEqualTo(summary);
    // getQuery() renders the ORIGINAL bytecode via the Groovy translator, so a bare g.V() source
    // renders the `.V()` step call. Assert that specific step token — a loose `V(` also matches
    // addV(/hasV( — and separately assert the spliced boundary step did not leak into the render.
    assertThat(listener.query)
        .as("getQuery() must render the original g.V() step, not the boundary step; was: "
            + listener.query)
        .contains(".V()");
    assertThat(listener.query)
        .as("the boundary step must not leak into the recorded query; was: " + listener.query)
        .doesNotContain("YTDBMatchPlanStep");
    assertThat(rowCount).as("recorded result count must match the fixture").isEqualTo(3);
  }

  /**
   * A translated traversal must surface the translation in {@code explain()}, and a declined one
   * must not. {@code explain()} applies the full strategy chain (including the globally
   * registered {@link GremlinToMatchStrategy}) to a clone, so a recognised {@code g.V()} shows
   * its native step chain collapsed to a single {@link YTDBMatchPlanStep} marker, while a
   * declined {@code g.V().hasLabel(...)} keeps its native vertex step. This is the signal the
   * per-track e2e tests assert on to pin "this shape translates / this shape declines". The
   * negative case also asserts the declined explain still renders a native vertex step, so the
   * "no boundary step" assertion is not vacuously true on an empty or errored explanation.
   */
  @Test
  public void explainReflectsTranslation() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    // Recognised bare g.V(): the strategy replaces the whole step list with one boundary step,
    // so its toString() marker appears in the final traversal of the explanation.
    var translatedExplain = graph.traversal().V().explain().toString();
    assertThat(translatedExplain)
        .as("explain() of a translated g.V() must surface the YTDBMatchPlanStep marker; was: "
            + translatedExplain)
        .contains("YTDBMatchPlanStep");
    // The FINAL (post-strategy) traversal must be collapsed to the boundary marker alone, with no
    // native vertex step surviving. Scope the check to the "Final Traversal" section: a GraphStep
    // always appears in the explanation's "Original Traversal" (and pre-translation strategy) rows,
    // so a whole-string "no GraphStep" assertion would be vacuously false-proof against a spliced-
    // but-not-collapsed regression.
    var finalSection =
        translatedExplain.substring(translatedExplain.lastIndexOf("Final Traversal"));
    assertThat(finalSection)
        .as("the translated final traversal must not retain a native GraphStep box; was: "
            + finalSection)
        .doesNotContain("GraphStep");

    // hasLabel(...) is unrecognised in this track, so the whole traversal declines to the native
    // pipeline: no boundary step, and the native vertex step (a GraphStep) is still rendered.
    var declinedExplain = graph.traversal().V().hasLabel("Person").explain().toString();
    assertThat(declinedExplain)
        .as("explain() of a declined traversal must not contain a boundary step; was: "
            + declinedExplain)
        .doesNotContain("YTDBMatchPlanStep");
    assertThat(declinedExplain)
        .as("declined explain must still render a native vertex step; was: " + declinedExplain)
        .contains("GraphStep");
  }

  /**
   * {@code explain()} of a translated non-adjacent edge filter {@code outE(L).has(...).inV()}
   * surfaces the boundary step and collapses the native chain. The interposed {@code has(...)}
   * blocks TinkerPop's incident-to-adjacent fold, so the native form is {@code VertexStep /
   * HasStep / EdgeVertexStep}; a successful translation replaces all three with a single {@link
   * YTDBMatchPlanStep}. The check is scoped to the "Final Traversal" section: the native step boxes
   * always appear in the "Original Traversal" rows, so a whole-string assertion would pass
   * vacuously against a spliced-but-not-collapsed regression. {@code VertexStep} as a substring also
   * catches {@code EdgeVertexStep}.
   */
  @Test
  public void explainReflectsEdgeFilterTranslation() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();

    var explain =
        graph.traversal().V().outE("knows").has("since", P.lt(2015)).inV().explain().toString();
    assertThat(explain)
        .as("explain() of a translated edge filter must surface the YTDBMatchPlanStep marker; was: "
            + explain)
        .contains("YTDBMatchPlanStep");
    var finalSection = explain.substring(explain.lastIndexOf("Final Traversal"));
    assertThat(finalSection)
        .as("the translated final traversal must collapse to the boundary step, leaving no native"
            + " VertexStep / HasStep / EdgeVertexStep / GraphStep box; was: " + finalSection)
        .doesNotContain("VertexStep", "HasStep", "GraphStep");
  }

  /**
   * {@code explain()} of a translated bare hop {@code out(L)} surfaces the boundary step and leaves
   * no native {@code VertexStep} in the final traversal. Complements {@link
   * #explainReflectsTranslation()} (which pins the {@code g.V()} source) with a hop shape, the
   * headline Track-3 addition. Scoped to the "Final Traversal" section for the same reason.
   */
  @Test
  public void explainReflectsBareHopTranslation() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    var explain = graph.traversal().V().out("knows").explain().toString();
    assertThat(explain)
        .as("explain() of a translated bare hop must surface the YTDBMatchPlanStep marker; was: "
            + explain)
        .contains("YTDBMatchPlanStep");
    var finalSection = explain.substring(explain.lastIndexOf("Final Traversal"));
    assertThat(finalSection)
        .as("the translated final traversal must collapse to the boundary step, leaving no native"
            + " VertexStep / GraphStep box; was: " + finalSection)
        .doesNotContain("VertexStep", "GraphStep");
  }

  /**
   * {@code explain()} of a declined {@code bothE(L).has(...).otherV()} edge filter shows no boundary
   * step and keeps the native chain. A BOTH-direction edge filter that must return the other
   * endpoint cannot be expressed in the edge-as-node MATCH form (the executor exposes no {@code
   * otherV}), so the whole traversal declines to the native pipeline -- the negative companion to
   * {@link #explainReflectsEdgeFilterTranslation()}. The native vertex source still renders (a
   * {@code GraphStep}, matched as a substring of the folded {@code YTDBGraphStep}).
   */
  @Test
  public void declinedBothEdgeFilterExplainStaysNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();

    var explain =
        graph.traversal().V().bothE("knows").has("since", P.lt(2015)).otherV().explain().toString();
    assertThat(explain)
        .as("explain() of a declined bothE edge filter must not contain a boundary step; was: "
            + explain)
        .doesNotContain("YTDBMatchPlanStep");
    assertThat(explain)
        .as("declined explain must still render the native vertex source; was: " + explain)
        .contains("GraphStep");
  }

  /**
   * A translated boundary step re-iterates correctly on the same instance after {@code reset()},
   * over a REAL execution plan. Exhaustion closes only the arming's stream and leaves the plan open,
   * so {@code reset()} + a second drive rewinds and re-runs the plan and returns the same rows; the
   * plan is closed only by the final {@code close()}. A regression that closed the plan at
   * exhaustion would leave the second pass re-running an already-closed plan (its steps' close guard
   * is sticky), leaking the re-run's cursors. The mock-plan reset unit test cannot catch this
   * because a mock plan has no real cursors; this drives a real {@code SelectExecutionPlan}.
   */
  @Test
  public void translatedBoundaryStep_reIteratesAfterReset_overRealPlan() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    var admin = graph.traversal().V().asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps())).isEqualTo(1);
    var boundary = (YTDBMatchPlanStep<?, ?>) admin.getSteps().getFirst();

    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    try {
      var firstPass = drainNames(boundary);
      // Re-arm the SAME instance and drive it again — the path that a bad exhaustion-closes-plan
      // would break.
      boundary.reset();
      var secondPass = drainNames(boundary);

      assertThat(firstPass).isEqualTo(List.of("Alice", "Bob"));
      assertThat(secondPass)
          .as("a reset boundary step must re-run its real plan and return the same rows")
          .isEqualTo(firstPass);
    } finally {
      boundary.close();
      tx.commit();
    }
  }

  /** Drains a boundary step to exhaustion, returning the matched vertices' names, sorted. */
  private static List<String> drainNames(YTDBMatchPlanStep<?, ?> boundary) {
    var names = new ArrayList<String>();
    while (boundary.hasNext()) {
      Object vertex = boundary.next().get();
      names.add(((Vertex) vertex).value("name"));
    }
    Collections.sort(names);
    return names;
  }

  /**
   * Counts {@link YTDBMatchPlanStep} occurrences across a step list. Takes {@code List<?>}
   * because {@code Traversal.Admin.getSteps()} returns a raw {@code List<Step>}.
   */
  private static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof YTDBMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }

  /**
   * Records the last {@link QueryDetails} the metrics step reports plus a call counter, so the
   * test can assert exactly-once delivery and inspect the captured query / summary / duration.
   */
  private static final class RememberingListener implements QueryMetricsListener {

    private int callCount;
    private String query;
    private String querySummary;

    @Override
    public void queryFinished(
        QueryDetails queryDetails, long startedAtMillis, long executionTimeNanos) {
      this.callCount++;
      this.query = queryDetails.getQuery();
      this.querySummary = queryDetails.getQuerySummary();
    }
  }
}
