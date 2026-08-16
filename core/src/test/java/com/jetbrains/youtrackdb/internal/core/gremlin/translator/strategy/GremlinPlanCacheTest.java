package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Before;
import org.junit.Test;

/**
 * R6 determinism and correctness tests for {@link GremlinPlanCache} and {@link
 * GremlinPlanFingerprint}: distinct shapes occupy distinct entries, same shapes fingerprint
 * identically, positional rebinding serves the second value's multiset, RID-bearing shapes bypass
 * the cache, schema changes invalidate entries, a second apply records a hit, and a cache hit
 * returns the same multiset as a cold rebuild.
 */
public class GremlinPlanCacheTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(this::graphSession);

  @Before
  public void enableTranslator() {
    support.setTranslatorEnabled(true);
    GremlinPlanCache.instance(graphSession()).invalidate();
  }

  /** {@code eq(null)} (bare {@code IS NULL}) and scalar {@code eq(v)} ({@code = ?}) differ in fingerprint. */
  @Test
  public void eqNull_and_eqValue_distinctFingerprints() {
    var nullWalk = walk(() -> graph.traversal().V().has("age", P.eq(null)));
    var valueWalk = walk(() -> graph.traversal().V().has("age", P.eq(30)));
    assertThat(fingerprint(nullWalk)).isNotEqualTo(fingerprint(valueWalk));
  }

  /** Distinct {@code hasLabel} class names stay discriminating in the fingerprint (R1). */
  @Test
  public void distinctHasLabel_distinctFingerprints_polymorphicAndNonPolymorphic() {
    seedPersonEmployeeHierarchy();
    withPolymorphic(true, () -> {
      var person = walk(() -> graph.traversal().V().hasLabel("Person"));
      var company = walk(() -> graph.traversal().V().hasLabel("Company"));
      assertThat(fingerprint(person)).isNotEqualTo(fingerprint(company));
    });
    withPolymorphic(false, () -> {
      var person = walk(() -> graph.traversal().V().hasLabel("Person"));
      var employee = walk(() -> graph.traversal().V().hasLabel("Employee"));
      assertThat(fingerprint(person)).isNotEqualTo(fingerprint(employee));
    });
  }

  /** NOT-differing shapes ({@code not(out(a))} vs {@code not(out(b))}, NOT vs no-NOT) differ (A1). */
  @Test
  public void notDifferingShapes_distinctFingerprints() {
    seedKnowsGraph();
    var notA = walk(() -> graph.traversal().V().not(__.out("knows")));
    var notB = walk(() -> graph.traversal().V().not(__.out("likes")));
    assertThat(fingerprint(notA)).isNotEqualTo(fingerprint(notB));

    var noNot = walk(() -> graph.traversal().V());
    assertThat(fingerprint(notA)).isNotEqualTo(fingerprint(noNot));
  }

  /** {@code hasId(...)} marks the walk RID-bearing and bypasses the plan cache. */
  @Test
  public void hasId_bypassesPlanCache() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var result = walk(() -> graph.traversal().V().hasId(alice.id()));
    assertThat(result.cacheEligible()).isFalse();

    apply(() -> graph.traversal().V().hasId(alice.id()));
    var fp = fingerprint(result);
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isFalse();
  }

  /** Two independent walks of the same shape produce identical fingerprints (R2). */
  @Test
  public void sameShape_identicalFingerprint() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    var first = walk(() -> graph.traversal().V().has("age", P.eq(30)));
    var second = walk(() -> graph.traversal().V().has("age", P.eq(99)));
    assertThat(fingerprint(first)).isEqualTo(fingerprint(second));
  }

  /**
   * A cached plan reused with a second predicate value returns the second value's multiset, not the
   * first's (R3).
   */
  @Test
  public void cachedPlan_rebindsSecondValue() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    apply(() -> graph.traversal().V().has("age", 30));
    var fp = fingerprint(walk(() -> graph.traversal().V().has("age", P.eq(30))));
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isTrue();

    var secondRun = apply(() -> graph.traversal().V().has("age", 40));
    assertThat(sortedNames(secondRun)).containsExactly("Bob");
  }

  /**
   * The range type guard's comparability-block names are part of the plan's shape, so two guards
   * naming different blocks must not share a cache entry — while two guards naming the same block
   * and differing only in the compared value must.
   *
   * <p>{@code STRING} and {@code BOOLEAN} are the pair that matters: both are one-name blocks, so a
   * rendering that collapses the names to placeholders makes the two keys byte-identical. The guard
   * reaches the key through the alias-filter section only, which is why an edge-free shape such as
   * {@code not(has(…))} is where the collision would land — a shape with a hop carries its filter on
   * a path item, and path items are already rendered verbatim.
   */
  @Test
  public void guardBlockNames_discriminateFingerprints_whileValuesDoNot() {
    var booleanBlock = walk(() -> graph.traversal().V().not(__.has("v", P.lt(true))));
    var stringBlock = walk(() -> graph.traversal().V().not(__.has("v", P.lt("m"))));
    assertThat(fingerprint(booleanBlock))
        .as("a BOOLEAN-block guard and a STRING-block guard are different plans")
        .isNotEqualTo(fingerprint(stringBlock));

    var otherStringValue = walk(() -> graph.traversal().V().not(__.has("v", P.lt("z"))));
    assertThat(fingerprint(stringBlock))
        .as("two guards naming the same block must still share one entry — the compared value is "
            + "rebound per execution, so splitting on it would cost plan reuse for nothing")
        .isEqualTo(fingerprint(otherStringValue));
  }

  /**
   * The row-level consequence of the fingerprint above: after a guarded shape has been compiled and
   * cached, the same shape with a literal of another runtime type must answer for its own guard, not
   * be served the cached one.
   *
   * <p>Values of five runtime types sit under one undeclared key. Natively — the container is inside
   * a {@code not(…)} child and therefore unfolded — a range comparison only relates operands of the
   * same comparability block, so {@code lt(true)} sees the two Booleans and {@code lt("m")} sees the
   * three Strings. Served the Boolean run's cached plan, the String run would keep {@code s_alpha}
   * as well, because a {@code BOOLEAN} type conjunct is false for a String and the enclosing
   * {@code NOT} then passes the row: five rows against native's four.
   */
  @Test
  public void cachedGuardedPlan_isNotServedToAnotherLiteralType() {
    graph.addVertex(T.label, "Types", "name", "s_alpha", "v", "alpha");
    graph.addVertex(T.label, "Types", "name", "s_zulu", "v", "zulu");
    graph.addVertex(T.label, "Types", "name", "b_true", "v", true);
    graph.addVertex(T.label, "Types", "name", "b_false", "v", false);
    graph.addVertex(T.label, "Types", "name", "n_ten", "v", 10);
    graph.tx().commit();

    var booleanRun = apply(() -> graph.traversal().V().not(__.has("v", P.lt(true))));
    assertThat(sortedNames(booleanRun))
        .as("not(v < true) withdraws only the Boolean below true")
        .containsExactly("b_true", "n_ten", "s_alpha", "s_zulu");
    assertThat(
        GremlinPlanCache.instance(graphSession())
            .contains(fingerprint(walk(() -> graph.traversal().V()
                .not(__.has("v", P.lt(true)))))))
        .as("the Boolean-guard plan must be cached, else the second run cannot be served it")
        .isTrue();

    var stringRun = apply(() -> graph.traversal().V().not(__.has("v", P.lt("m"))));
    assertThat(sortedNames(stringRun))
        .as("not(v < \"m\") withdraws only the String below \"m\" — being served the Boolean run's "
            + "guard would keep s_alpha too")
        .containsExactly("b_false", "b_true", "n_ten", "s_zulu");
  }

  /** {@code within} with different element counts does not collide on fingerprint. */
  @Test
  public void withinDifferentSizes_distinctFingerprints() {
    var one = walk(() -> graph.traversal().V().has("age", P.within(30)));
    var two = walk(() -> graph.traversal().V().has("age", P.within(30, 40)));
    assertThat(fingerprint(one)).isNotEqualTo(fingerprint(two));
  }

  /**
   * Distinct {@code limit} / {@code skip} literals must not share a cache entry — they are inline in
   * MATCH and not rebound per execution.
   */
  @Test
  public void distinctLimitSkip_distinctFingerprints() {
    var limit2 = walk(() -> graph.traversal().V().limit(2));
    var limit5 = walk(() -> graph.traversal().V().limit(5));
    assertThat(fingerprint(limit2)).isNotEqualTo(fingerprint(limit5));

    var skip1 = walk(() -> graph.traversal().V().skip(1));
    var skip2 = walk(() -> graph.traversal().V().skip(2));
    assertThat(fingerprint(skip1)).isNotEqualTo(fingerprint(skip2));
  }

  /** {@code order().by(...)} differs from unordered {@code g.V()} in the fingerprint. */
  @Test
  public void orderBy_distinctFromUnordered() {
    var plain = walk(() -> graph.traversal().V());
    var ordered =
        walk(() -> graph.traversal().V().order()
            .by("name", org.apache.tinkerpop.gremlin.process.traversal.Order.desc));
    assertThat(fingerprint(plain)).isNotEqualTo(fingerprint(ordered));
  }

  /** {@code dedup()} (DISTINCT) differs from a non-distinct walk of the same hop shape. */
  @Test
  public void dedup_distinctFromNonDistinct() {
    seedKnowsGraph();
    var plain = walk(() -> graph.traversal().V().out("knows"));
    var deduped = walk(() -> graph.traversal().V().out("knows").dedup());
    assertThat(fingerprint(plain)).isNotEqualTo(fingerprint(deduped));
  }

  /** {@code groupCount().by("name")} differs from bare {@code count()} in the fingerprint. */
  @Test
  public void groupCount_distinctFromCount() {
    var count = walk(() -> graph.traversal().V().count());
    var groupCount = walk(() -> graph.traversal().V().groupCount().by("name"));
    assertThat(fingerprint(count)).isNotEqualTo(fingerprint(groupCount));
  }

  // ---------------------------------------------------------------------------
  // SF2 / TC4 — the fingerprint discriminates result-shaping variants, and a user
  // identifier embedding fingerprint delimiter chars ([ ] : ; ->) cannot forge
  // another walk's key. GremlinPlanFingerprint length-prefixes every
  // variable-length token, so no combination of user strings can collide.
  // ---------------------------------------------------------------------------

  /** {@code order().by("name", asc)} and {@code .desc} occupy distinct fingerprints (direction). */
  @Test
  public void orderAscVsDesc_distinctFingerprints() {
    var asc = walk(() -> graph.traversal().V().order().by("name", Order.asc));
    var desc = walk(() -> graph.traversal().V().order().by("name", Order.desc));
    assertThat(fingerprint(asc)).isNotEqualTo(fingerprint(desc));
  }

  /** {@code order().by("name")} and {@code order().by("age")} differ (order key property). */
  @Test
  public void orderByDifferentKeys_distinctFingerprints() {
    var byName = walk(() -> graph.traversal().V().order().by("name"));
    var byAge = walk(() -> graph.traversal().V().order().by("age"));
    assertThat(fingerprint(byName)).isNotEqualTo(fingerprint(byAge));
  }

  /** {@code group().by("name")} and {@code group().by("age")} differ (group key property). */
  @Test
  public void groupByDifferentKeys_distinctFingerprints() {
    var byName = walk(() -> graph.traversal().V().group().by("name"));
    var byAge = walk(() -> graph.traversal().V().group().by("age"));
    assertThat(fingerprint(byName)).isNotEqualTo(fingerprint(byAge));
  }

  /**
   * SF2 injection guard: a single {@code has()} key {@code "a]b:c"} that embeds the fingerprint's
   * delimiter characters must not share a fingerprint with the two-key split {@code has("a").has(
   * "b:c")}. Length-prefixing makes each token self-delimiting, so the delimiters inside the crafted
   * key cannot merge/split the encoding into the split shape's key. A raw delimiter concatenation
   * (the pre-SF2 form) is exactly the collision this pins against.
   */
  @Test
  public void craftedHasKeyWithDelimiters_distinctFromSplitKeys() {
    var crafted = walk(() -> graph.traversal().V().has("a]b:c", P.eq(1)));
    var split = walk(() -> graph.traversal().V().has("a", P.eq(1)).has("b:c", P.eq(1)));
    assertThat(fingerprint(crafted)).isNotEqualTo(fingerprint(split));
  }

  /**
   * SF2 injection guard on the RETURN projection: a {@code values("x]")} key embedding a delimiter
   * must not collide with the plain {@code values("x")} key. The trailing {@code ]} is carried
   * verbatim inside a length-prefixed token, so it cannot forge the plain key's encoding.
   */
  @Test
  public void craftedValuesKeyWithDelimiter_distinctFromPlainKey() {
    var crafted = walk(() -> graph.traversal().V().values("x]"));
    var plain = walk(() -> graph.traversal().V().values("x"));
    assertThat(fingerprint(crafted)).isNotEqualTo(fingerprint(plain));
  }

  /** Schema listener invalidates the cache; no live schema mutation required. */
  @Test
  public void schemaChange_invalidatesCache() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    apply(() -> graph.traversal().V().has("age", 30));
    var fp = fingerprint(walk(() -> graph.traversal().V().has("age", P.eq(30))));
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isTrue();

    var before = GremlinPlanCache.getLastInvalidation(graphSession());
    GremlinPlanCache.instance(graphSession()).onSchemaUpdate(null, "test", null);
    assertThat(GremlinPlanCache.getLastInvalidation(graphSession())).isGreaterThan(before);
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isFalse();
  }

  /**
   * First apply of a shape records a miss and populates the cache; the second apply of the same
   * shape records a hit — proof the production {@code get} path served the plan, not a silent
   * rebuild.
   */
  @Test
  public void secondApply_recordsCacheHit() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var hitsBefore = cache.getHits();
    var missesBefore = cache.getMisses();

    apply(() -> graph.traversal().V().has("age", 30));
    assertThat(cache.getMisses()).isEqualTo(missesBefore + 1);
    assertThat(cache.getHits()).isEqualTo(hitsBefore);

    apply(() -> graph.traversal().V().has("age", 40));
    assertThat(cache.getHits()).isEqualTo(hitsBefore + 1);
    assertThat(cache.getMisses()).isEqualTo(missesBefore + 1);
  }

  /**
   * Cold rebuild (after invalidate) and a subsequent cache hit return the same multiset for the
   * same predicate value — plan reuse must not change Gremlin results.
   */
  @Test
  public void cacheHit_sameResultsAsColdRebuild() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.addVertex(T.label, "Person", "name", "Carol", "age", 30);
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var cold = sortedNames(apply(() -> graph.traversal().V().has("age", 30)));
    assertThat(cold).containsExactly("Alice", "Carol");

    cache.invalidate();
    var rebuilt = sortedNames(apply(() -> graph.traversal().V().has("age", 30)));
    assertThat(rebuilt).isEqualTo(cold);

    var hitsBefore = cache.getHits();
    var warm = sortedNames(apply(() -> graph.traversal().V().has("age", 30)));
    assertThat(cache.getHits()).isEqualTo(hitsBefore + 1);
    assertThat(warm).isEqualTo(cold);
  }

  /**
   * A count plan carries the non-cacheable {@code CountFromClassStep}, so it must never be cached:
   * a second apply of {@code g.V().count()} records another miss, not a hit. Guards the security fix
   * — caching the plan would replay a build-time security-policy decision on another session and
   * disclose a class's true count past a row-hiding READ policy (see {@code CountFromClassStep}).
   */
  @Test
  public void countPlan_notCached_secondApplyIsMiss() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var hitsBefore = cache.getHits();
    var missesBefore = cache.getMisses();

    apply(() -> graph.traversal().V().count());
    apply(() -> graph.traversal().V().count());

    assertThat(cache.getHits()).isEqualTo(hitsBefore);
    assertThat(cache.getMisses()).isEqualTo(missesBefore + 2);
  }

  /**
   * Concurrent {@code contains()} / {@code invalidate()} against one shared {@link GremlinPlanCache}
   * must never throw and must keep the lifetime hit/miss counters non-negative. Eight threads start
   * together on a {@link CyclicBarrier} to maximise interleaving; two of them also invalidate
   * periodically while the rest only read. Only the thread-safe cache is touched off-thread (Guava
   * cache + {@code LongAdder} counters + {@code AtomicLong} timestamp) — the seed, the plan
   * population, and the fingerprint are computed on the main thread first, because the graph session
   * is thread-affine and cannot be driven from worker threads. This complements the {@code
   * buildPlan} concurrent-invalidation guard by pinning that the cache structure itself is safe
   * under a read/invalidate race. Mirrors {@code YqlExecutionPlanCacheTest#testConcurrentAccess}.
   */
  @Test
  public void concurrentContainsAndInvalidate_neverThrowsAndKeepsCountersNonNegative()
      throws InterruptedException {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(graphSession());
    // Populate the cache so contains() has a live entry to race with invalidate().
    apply(() -> graph.traversal().V().has("age", 30));
    var fp = fingerprint(walk(() -> graph.traversal().V().has("age", P.eq(30))));

    var threadCount = 8;
    var iterations = 500;
    var barrier = new CyclicBarrier(threadCount);
    var done = new CountDownLatch(threadCount);
    var firstError = new AtomicReference<Throwable>();

    for (var t = 0; t < threadCount; t++) {
      var invalidator = t < 2; // two threads also invalidate; the remaining six only read
      var worker = new Thread(() -> {
        try {
          barrier.await(); // release all threads at once
          for (var i = 0; i < iterations; i++) {
            cache.contains(fp);
            if (cache.getHits() < 0 || cache.getMisses() < 0) {
              throw new AssertionError("cache counters went negative under concurrency");
            }
            if (invalidator && (i % 50) == 0) {
              cache.invalidate();
            }
          }
        } catch (Throwable e) {
          firstError.compareAndSet(null, e);
        } finally {
          done.countDown();
        }
      });
      worker.start();
    }

    assertThat(done.await(30, TimeUnit.SECONDS))
        .as("all cache-concurrency workers must finish within 30s")
        .isTrue();
    assertThat(firstError.get())
        .as("no worker thread may throw during concurrent contains()/invalidate()")
        .isNull();
    assertThat(cache.getHits()).isGreaterThanOrEqualTo(0);
    assertThat(cache.getMisses()).isGreaterThanOrEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private GremlinToMatchTranslator.TranslationResult walk(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    var admin = supplier.get().asAdmin();
    var result = GremlinStepWalker.production().walk(admin);
    assertThat(result).isNotNull();
    return result;
  }

  private List<?> apply(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    var admin = supplier.get().asAdmin();
    GremlinToMatchStrategy.instance().apply(admin);
    assertThat(admin.getSteps()).hasSize(1);
    assertThat(admin.getSteps().getFirst()).isInstanceOf(YTDBMatchPlanStep.class);
    return admin.toList();
  }

  private static String fingerprint(GremlinToMatchTranslator.TranslationResult result) {
    var inputs = result.inputs();
    assertThat(inputs).as("single-plan cache tests require MatchPlanInputs").isNotNull();
    return GremlinPlanFingerprint.fingerprint(inputs);
  }

  private static List<String> sortedNames(List<?> vertices) {
    return vertices.stream().map(v -> ((Vertex) v).value("name")).map(Object::toString).sorted()
        .toList();
  }

  private void seedPersonEmployeeHierarchy() {
    graph.addVertex(T.label, "Person", "name", "Pat");
    graph.addVertex(T.label, "Employee", "name", "Em");
    graph.addVertex(T.label, "Company", "name", "Co");
    graph.tx().commit();
  }

  private void seedKnowsGraph() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    alice.addEdge("likes", bob);
    graph.tx().commit();
  }

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private void withPolymorphic(boolean value, Runnable body) {
    var config = graphSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, value);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }
}
