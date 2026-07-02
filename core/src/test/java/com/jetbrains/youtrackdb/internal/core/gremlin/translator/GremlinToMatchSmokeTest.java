package com.jetbrains.youtrackdb.internal.core.gremlin.translator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.structure.Edge;
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

    assertEquals(
        "a recognized g.V() must contain exactly one YTDBMatchPlanStep after applyStrategies()",
        1,
        countBoundarySteps(admin.getSteps()));

    var names =
        admin.toList().stream().map(v -> (String) ((Vertex) v).value("name")).sorted().toList();
    assertEquals(List.of("Alice", "Bob", "Carol"), names);
  }

  /**
   * Translator-on vs translator-off parity: the same {@code g.V()} shape must return the same
   * result multiset with the strategy enabled and disabled. Order is not pinned (MATCH reorders),
   * so parity is asserted on sorted name multisets. Also confirms engagement is opposite in the
   * two runs — a boundary step on, a {@code YTDBGraphStep} off.
   */
  @Test
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
      assertEquals(
          "translator on → exactly one boundary step", 1, countBoundarySteps(on.getSteps()));
      var namesOn = on.toList().stream().map(v -> (String) ((Vertex) v).value("name")).sorted()
          .toList();

      // Translator OFF.
      session
          .getConfiguration()
          .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
      var off = graph.traversal().V().asAdmin();
      off.applyStrategies();
      assertEquals(
          "translator off → no boundary step", 0, countBoundarySteps(off.getSteps()));
      assertTrue(
          "translator off → start step must remain a YTDBGraphStep but was "
              + off.getStartStep().getClass(),
          off.getStartStep() instanceof YTDBGraphStep<?, ?>);
      var namesOff = off.toList().stream().map(v -> (String) ((Vertex) v).value("name")).sorted()
          .toList();

      // Multiset equality (sorted lists preserve multiplicity — the duplicate "Alice" appears
      // twice in both).
      assertEquals("translator-on and translator-off multisets must match", namesOff, namesOn);
      assertEquals(List.of("Alice", "Alice", "Bob", "Carol"), namesOn);
    } finally {
      session
          .getConfiguration()
          .setValue(
              GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, originalValue);
    }
  }

  /**
   * Timing: a translated {@code g.V()} that engages the boundary step must still execute and
   * return results in a finite, non-negative wall-clock window. This is a coarse liveness check
   * (the translated path terminates and produces output), not a performance assertion — the
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
    assertEquals(1, countBoundarySteps(admin.getSteps()));

    var start = System.nanoTime();
    var count = admin.toList().size();
    var elapsedNanos = System.nanoTime() - start;

    assertEquals("translated g.V() must return every vertex", 50, count);
    assertTrue("elapsed time must be non-negative", elapsedNanos >= 0);
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
    assertEquals(1, countBoundarySteps(admin.getSteps()));

    var vertices = admin.toList().stream().map(v -> (Vertex) v).toList();
    assertEquals(1, vertices.size());
    assertEquals(bob.id(), vertices.get(0).id());
    assertEquals("Bob", vertices.get(0).value("name"));
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
    assertEquals(1, countBoundarySteps(admin.getSteps()));

    var returned = admin.toList().stream().map(v -> (Vertex) v).toList();
    var idsReturned = returned.stream().map(Vertex::id).collect(Collectors.toSet());
    var namesReturned =
        returned.stream().map(v -> (String) v.value("name")).collect(Collectors.toSet());
    assertEquals(Set.of(alice.id(), carol.id()), idsReturned);
    assertEquals(Set.of("Alice", "Carol"), namesReturned);
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
    assertEquals(
        "duplicate-id source must decline (no boundary step)",
        0,
        countBoundarySteps(admin.getSteps()));

    // The declined shape runs natively and emits the vertex once per list entry: two emissions.
    var native2 = admin.toList();
    assertEquals("native g.V(id, id) emits the vertex once per list entry", 2, native2.size());
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
    assertEquals("g.E() must decline (no boundary step)", 0, countBoundarySteps(admin.getSteps()));
    assertTrue(
        "g.E() must remain on a YTDBGraphStep but was " + admin.getStartStep().getClass(),
        admin.getStartStep() instanceof YTDBGraphStep<?, ?>);
    var graphStep = (YTDBGraphStep<?, ?>) admin.getStartStep();
    assertEquals(Edge.class, graphStep.getReturnClass());
  }

  /**
   * A follow-up step ({@code g.V().out()}) declines the whole traversal — Phase 1's recognized
   * set is exactly the bare vertex source (the minimal-prefix size-1 gate). The traversal keeps
   * its native step shape with no boundary step spliced.
   */
  @Test
  public void followUpStepDeclinesUnderMinimalPrefixGate() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    var admin = graph.traversal().V().out("knows").asAdmin();
    admin.applyStrategies();
    assertEquals(
        "a multi-step traversal must decline under the minimal-prefix gate",
        0,
        countBoundarySteps(admin.getSteps()));

    // The declined shape still executes correctly on the native pipeline: Alice -knows-> Bob.
    var names =
        admin.toList().stream().map(v -> (String) ((Vertex) v).value("name")).sorted().toList();
    assertEquals(List.of("Bob"), names);
  }

  /**
   * A {@code g.V().has("name", "Alice")} start declines: {@code YTDBGraphStepStrategy} folds the
   * {@code has} into a {@code YTDBGraphStep} with a non-empty has-container, which the translator
   * declines. Because the translator runs first, at translator time the step is still a plain
   * {@code GraphStep} carrying no folded predicate — but the whole traversal has more than one
   * step before folding collapses it, so the minimal-prefix gate declines it regardless, and the
   * native folder then handles the shape.
   */
  @Test
  public void startWithHasContainerDeclines() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    var admin = graph.traversal().V().has("name", "Alice").asAdmin();
    admin.applyStrategies();
    assertEquals(
        "g.V().has(...) must decline (no boundary step)", 0, countBoundarySteps(admin.getSteps()));

    var names =
        admin.toList().stream().map(v -> (String) ((Vertex) v).value("name")).sorted().toList();
    assertEquals(List.of("Alice"), names);
  }

  /**
   * Kill-switch round-trip on the shared strategy singleton: flag {@code true → false → true}.
   * With the flag off the strategy declines (no boundary step); flipping it back on re-engages on
   * the same singleton, proving the flag is read fresh per {@code apply()} rather than cached at
   * registration.
   */
  @Test
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
      assertEquals(
          "kill-switch off → no boundary step", 0, countBoundarySteps(off.getSteps()));
      assertTrue(
          "kill-switch off → start step must remain a YTDBGraphStep but was "
              + off.getStartStep().getClass(),
          off.getStartStep() instanceof YTDBGraphStep<?, ?>);

      session
          .getConfiguration()
          .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
      var on = graph.traversal().V().asAdmin();
      on.applyStrategies();
      assertEquals(
          "kill-switch back on → exactly one boundary step", 1, countBoundarySteps(on.getSteps()));
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

    assertEquals(
        "step list must be identical after re-applying the strategy",
        stepsAfterFirst,
        new ArrayList<>(admin.getSteps()));
    assertEquals(
        "start step instance must be unchanged", startAfterFirst, admin.getStartStep());
    assertEquals(1, countBoundarySteps(admin.getSteps()));

    var names =
        admin.toList().stream().map(v -> (String) ((Vertex) v).value("name")).sorted().toList();
    assertEquals(List.of("Alice", "Bob"), names);
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
    assertEquals(1, countBoundarySteps(admin.getSteps()));

    var labels = admin.toList().stream().map(v -> ((Vertex) v).label()).sorted().toList();
    assertEquals(List.of("Person", "Place"), labels);
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
   *   <li>duration is non-negative and the result count matches the fixture.</li>
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

    // Run the monitored traversal exactly once. toList() both compiles the traversal (translating
    // g.V() to the boundary step and appending the metrics step) and drains-then-closes it, which
    // fires the metrics listener once. The traversal is intentionally NOT wrapped in
    // try-with-resources: a monitored toList() already closes the traversal, so an additional
    // close() on block exit would close (and re-fire) the metrics step a second time — a benign
    // framework double-close that is identical on the native pipeline and unrelated to
    // translation, but it would mask a real double-count here. Running toList() alone gives one
    // close, one fire, so the exactly-once assertion below actually tests the translation path.
    var q = gs.V();
    var rowCount = q.toList().size();
    var boundaryEngaged = countBoundarySteps(q.asAdmin().getSteps()) == 1;
    graph.tx().commit();

    assertTrue(
        "monitored g.V() must still be translated to exactly one boundary step", boundaryEngaged);
    assertEquals("exactly one QueryDetails must be recorded", 1, listener.callCount);
    assertNotNull("a query string must be captured", listener.query);
    assertEquals(
        "querySummary must round-trip through the OptionsStrategy unaffected by translation",
        summary,
        listener.querySummary);
    // getQuery() renders the ORIGINAL Gremlin bytecode (fixed at construction), so it must still
    // describe the g.V() source — not the spliced boundary step.
    assertTrue(
        "getQuery() must render the original g.V() Gremlin, not the boundary step; was: "
            + listener.query,
        listener.query.contains("V(")
            && !listener.query.contains("YTDBMatchPlanStep"));
    assertTrue("execution duration must be non-negative", listener.executionTimeNanos >= 0);
    assertEquals("recorded result count must match the fixture", 3, rowCount);
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
    assertTrue(
        "explain() of a translated g.V() must surface the YTDBMatchPlanStep marker; was: "
            + translatedExplain,
        translatedExplain.contains("YTDBMatchPlanStep"));

    // hasLabel(...) is unrecognised in this track, so the whole traversal declines to the native
    // pipeline: no boundary step, and the native vertex step (a GraphStep) is still rendered.
    var declinedExplain = graph.traversal().V().hasLabel("Person").explain().toString();
    assertFalse(
        "explain() of a declined traversal must not contain a boundary step; was: "
            + declinedExplain,
        declinedExplain.contains("YTDBMatchPlanStep"));
    assertTrue(
        "declined explain must still render a native vertex step; was: " + declinedExplain,
        declinedExplain.contains("GraphStep"));
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
    private long executionTimeNanos;

    @Override
    public void queryFinished(
        QueryDetails queryDetails, long startedAtMillis, long executionTimeNanos) {
      this.callCount++;
      this.query = queryDetails.getQuery();
      this.querySummary = queryDetails.getQuerySummary();
      this.executionTimeNanos = executionTimeNanos;
    }
  }
}
