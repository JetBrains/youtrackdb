package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exact-token, replay, bounded scratch and pinned-Guava ownership checks. */
@org.junit.experimental.categories.Category(com.jetbrains.youtrackdb.internal.SequentialTest.class)
public class GremlinShapeFastPathTest extends GraphBaseTest {
  @Before
  public void enableTranslation() {
    graph.addVertex(T.label, "FastVertex", "name", "Alice", "age", 1);
    graph.addVertex(T.label, "FastVertex", "name", "Bob", "age", 2);
    graph.tx().commit();
    graphSession().getConfiguration().setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    GremlinPlanCache.instance(graphSession()).invalidate();
  }

  @After
  public void clearTestInstrumentation() {
    GremlinShapeBuffer.hasherForTests = null;
    GremlinShapeBuffer.stopCounting();
  }

  /** Frozen tokens and text remain independent references for keys and complete binding layouts. */
  @Test
  public void scalarCorpusMatchesBothFrozenReferencesAndPredicateFingerprintText() {
    var corpus = List.of(
        graph.traversal().V().hasLabel("FastVertex").has("age", 1).values("name"),
        graph.traversal().V().hasLabel("FastVertex").has("age", P.gt(0)).out("edge").has("age", 2),
        graph.traversal().V().hasLabel("FastVertex").has("name", TextP.startingWith("Al")),
        graph.traversal().V().hasLabel("FastVertex").has("name", TextP.regex("A.*")),
        graph.traversal().V().hasLabel("FastVertex").has("age", P.eq(null)),
        graph.traversal().V().hasLabel("FastVertex").filter(__.has("age", 1)),
        graph.traversal().V().hasLabel("FastVertex").barrier(2500).valueMap("name"),
        graph.traversal().withSack(1).V().hasLabel("FastVertex").barrier(2500).valueMap("name"));
    for (var traversal : corpus) {
      TextShapeOracle.verify(traversal.asAdmin());
      try (var lease = GremlinShapeBuffer.acquire()) {
        var fast = extract(traversal.asAdmin(), lease);
        assertThat(fast.lookupKey()).isInstanceOf(GremlinShapeBuffer.class);
        assertThat(fast.key()).isEqualTo(fast.lookupKey());
      }
    }
    for (var predicate : List.of(P.eq(1), P.between(1, 4), TextP.regex("a"),
        TextP.notRegex("b"), TextP.startingWith("ab"))) {
      assertThat(GremlinShapeEncoder.predicateStructure(predicate, false))
          .isEqualTo(Track4TokenEncoder.predicateStructure(predicate, false));
    }
  }

  /** Both comparison directions use exact tokens even for colliding cached String hashes. */
  @Test
  public void probeAndFrozenKeyShareSymmetricTypedEqualityAndHash() {
    try (var lease = GremlinShapeBuffer.acquire()) {
      lease.append(2);
      lease.append("Aa");
      var frozen = lease.freeze();
      assertThat(frozen).isEqualTo(lease);
      assertThat(lease).isEqualTo(frozen);
      assertThat(lease.hashCode()).isEqualTo(frozen.hashCode());
      assertThat(frozen).isNotEqualTo(new GremlinShapeKey(new Object[] {"2", "Aa"}));
      var other = new GremlinShapeKey(new Object[] {2, "BB"});
      assertThat(frozen.hash64()).isEqualTo(other.hash64());
      assertThat(frozen).isNotEqualTo(other);
    }
  }

  /** Guava stores immutable exact keys, not temporary probes, through hits, repair and eviction. */
  @Test
  public void pinnedGuavaNeverPublishesLookupProbeAndKeepsForcedCollisionsIndependent()
      throws Exception {
    GremlinShapeBuffer.hasherForTests = value -> 7;
    Cache<GremlinShapeKey, String> cache = CacheBuilder.newBuilder().maximumSize(2).build();
    var first = new GremlinShapeKey(new Object[] {"first"});
    var second = new GremlinShapeKey(new Object[] {"second"});
    cache.put(first, "one");
    cache.put(second, "two");
    try (var probe = GremlinShapeBuffer.acquire()) {
      probe.append("first");
      assertThat(cache.getIfPresent(probe)).isEqualTo("one");
      assertThat(retainsProbe(cache, probe, java.util.Collections.newSetFromMap(
          new java.util.IdentityHashMap<>()))).isFalse();
      assertThat(cache.asMap().keySet()).allMatch(key -> key instanceof GremlinShapeKey);
      assertThat(cache.asMap().putIfAbsent(probe.freeze(), "loser")).isEqualTo("one");
      assertThat(cache.asMap().replace(probe.freeze(), "one", "repair")).isTrue();
    }
    assertThat(cache.getIfPresent(first)).isEqualTo("repair");
    assertThat(cache.getIfPresent(second)).isEqualTo("two");
    cache.put(new GremlinShapeKey(new Object[] {"third"}), "three");
    cache.cleanUp();
    assertThat(cache.size()).isLessThanOrEqualTo(2);
  }

  /**
   * The actual mixed cache keeps every private dimension and production shape independent.
   * Probe hits, rejected repairs and losing repairs retain only immutable keys.
   */
  @Test
  public void mixedCachePreservesCandidateDomainsAndNeverRetainsLookupProbe() throws Exception {
    GremlinShapeBuffer.hasherForTests = value -> 7;
    var db = graphSession();
    var cache = new GremlinPlanCache(32);
    var shape = new GremlinShapeKey(new Object[] {"Aa"});
    var collision = new GremlinShapeKey(new Object[] {"BB"});
    var cuts = new PrivateCandidatePreparation.Policy(java.util.Set.of(3));
    var base = new PrivateCandidatePreparation.CandidateKey("private", cuts, false, false, 7,
        shape);
    var keys = List.of(base,
        new PrivateCandidatePreparation.CandidateKey("other", cuts, false, false, 7, shape),
        new PrivateCandidatePreparation.CandidateKey("private",
            new PrivateCandidatePreparation.Policy(java.util.Set.of()), false, false, 7, shape),
        new PrivateCandidatePreparation.CandidateKey("private", cuts, true, false, 7, shape),
        new PrivateCandidatePreparation.CandidateKey("private", cuts, false, true, 7, shape),
        new PrivateCandidatePreparation.CandidateKey("private", cuts, false, false, 8, shape),
        new PrivateCandidatePreparation.CandidateKey("private", cuts, false, false, 7, collision));
    cache.getTranslationEntry(shape, db);
    long generation = cache.getGeneration();
    var production = new GremlinTranslationTemplate.Decline();
    assertThat(cache.putTranslationInternal(shape, production, db, generation))
        .isSameAs(production);
    var privateResidents = new ArrayList<GremlinTranslationTemplate>();
    for (int i = 0; i < keys.size(); i++) {
      var candidate = new GremlinTranslationTemplate.Candidate(
          i, PrivatePrefixDiscovery.Reason.FOLD_CUT, null);
      assertThat(cache.getTranslationEntry(keys.get(i), db)).isNull();
      assertThat(cache.putTranslationInternal(keys.get(i), candidate, db, generation))
          .isSameAs(candidate);
      privateResidents.add(candidate);
    }
    for (int i = 0; i < keys.size(); i++) {
      var key = keys.get(i);
      var resident = cache.getTranslationEntry(key, db);
      assertThat(resident.template()).isSameAs(privateResidents.get(i));
      var repair = new GremlinTranslationTemplate.Candidate(
          i, PrivatePrefixDiscovery.Reason.PLAIN, null);
      // A production envelope is not authority to replace any private publication.
      assertThat(cache.putTranslationInternal(key, repair, db, generation,
          cache.getTranslationEntry(shape, db))).isSameAs(resident.template());
      assertThat(cache.putTranslationInternal(key, repair, db, generation, resident))
          .isSameAs(repair);
      assertThat(cache.putTranslationInternal(key, privateResidents.get(i), db, generation,
          resident)).isSameAs(repair);
      privateResidents.set(i, repair);
    }
    var field = GremlinPlanCache.class.getDeclaredField("translationCache");
    field.setAccessible(true);
    var backing = (Cache<?, ?>) field.get(cache);
    try (var probe = GremlinShapeBuffer.acquire()) {
      probe.append("Aa");
      var resident = cache.getTranslationEntry(probe, db);
      assertThat(resident.template()).isSameAs(production);
      assertThat(cache.putTranslationInternal(probe, new GremlinTranslationTemplate.Decline(),
          db, generation, resident)).isNull();
      var repair = new GremlinTranslationTemplate.Decline();
      assertThat(cache.putTranslationInternal(probe.freeze(), repair, db, generation, resident))
          .isSameAs(repair);
      assertThat(cache.putTranslationInternal(probe.freeze(), production, db, generation,
          resident)).isSameAs(repair);
      assertThat(cache.getTranslationInternal(probe, db)).isSameAs(repair);
      assertThat(retainsProbe(backing, probe, java.util.Collections.newSetFromMap(
          new java.util.IdentityHashMap<>()))).isFalse();
      assertThat(backing.asMap().keySet()).allMatch(key -> key instanceof GremlinShapeKey
          || key instanceof PrivateCandidatePreparation.CandidateKey);
    }
    try (var reused = GremlinShapeBuffer.acquire()) {
      reused.append("unrelated");
      assertThat(cache.getTranslationEntry(reused, db)).isNull();
      for (int i = 0; i < keys.size(); i++) {
        assertThat(cache.getTranslationInternal(keys.get(i), db)).isSameAs(privateResidents.get(i));
      }
      assertThat(cache.getTranslationInternal(shape, db)).isInstanceOf(
          GremlinTranslationTemplate.Decline.class).isNotSameAs(production);
    }
  }

  /** Root admission annotates strategies without forcing grouping to rescan them on a warm hit. */
  @Test
  public void warmCombiningRootReusesScanThroughSafetyEvidenceWithoutMatchBookkeeping()
      throws Exception {
    var cold = graph.traversal().V().has("age", 1).barrier(2500).valueMap("name").asAdmin();
    assertThat(PropertyMapBulkingAdmission.capacity(cold)).isEqualTo(2500);
    GremlinToMatchStrategy.instance().apply(cold);
    var warm = graph.traversal().V().has("age", 2).barrier(2500).valueMap("name").asAdmin();
    var strategies = org.mockito.Mockito.spy(warm.getStrategies());
    warm.setStrategies(strategies);
    org.mockito.Mockito.clearInvocations(strategies);
    var counters = new GremlinShapeBuffer.Counters();
    GremlinShapeBuffer.startCounting(counters);
    var metrics = GremlinTranslationMetrics.of(graphSession());
    var nativeBefore = metrics.getRuleNative();
    GremlinToMatchStrategy.instance().apply(warm);
    assertThat(warm.getStrategies()).isInstanceOf(QuerySafetyAdmission.Evidence.class);
    assertThat(metrics.getRuleNative()).isEqualTo(nativeBefore + 1);
    org.mockito.Mockito.verify(strategies).iterator();
    org.mockito.Mockito.verify(strategies, org.mockito.Mockito.never())
        .getStrategy(org.mockito.ArgumentMatchers.any());
    assertThat(counters.emissions).isEqualTo(1);
    assertThat(counters.hits).isEqualTo(1);
    assertThat(counters.extractions).isZero();
    assertThat(counters.bindingMaps).isZero();
    assertThat(counters.contributions).isZero();
    // Observe a separate warm invocation without forcing harvest or freezing its leased key.
    var observed = new ArrayList<GremlinShapeExtractor.Extraction>();
    var observer = new GremlinToMatchStrategy(t -> {
      throw new AssertionError("warm combining root walked again");
    }, GremlinToMatchStrategy::buildPlan, true);
    observer.extractionObserver = observed::add;
    try (var contexts = org.mockito.Mockito.mockStatic(HasBindingContext.class,
        org.mockito.Mockito.CALLS_REAL_METHODS)) {
      observer.apply(graph.traversal().V().has("age", 1).barrier(2500).valueMap("name").asAdmin());
      assertThat(observed).hasSize(1);
      var encoder = observed.getFirst().encoder();
      assertThat(encoder.harvested()).isFalse();
      var frozen = GremlinShapeEncoder.class.getDeclaredField("frozenKey");
      frozen.setAccessible(true);
      assertThat(frozen.get(encoder)).isNull();
      contexts.verifyNoInteractions();
    }
  }

  /** The folded Java hash cannot replace full 64-bit rejection and exact token comparison. */
  @Test
  public void different64BitHashesWithSameJavaHashCoexist() {
    var rawFirst = new GremlinShapeKey(new Object[] {"first"}).hash64();
    GremlinShapeBuffer.hasherForTests = value -> value == rawFirst ? 0 : 0x100000001L;
    var first = new GremlinShapeKey(new Object[] {"first"});
    var second = new GremlinShapeKey(new Object[] {"second"});
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
    assertThat(first.hash64()).isNotEqualTo(second.hash64());
    assertThat(first).isNotEqualTo(second);
    Cache<GremlinShapeKey, String> cache = CacheBuilder.newBuilder().maximumSize(2).build();
    cache.put(first, "first");
    cache.put(second, "second");
    try (var probe = GremlinShapeBuffer.acquire()) {
      probe.append("first");
      assertThat(cache.getIfPresent(probe)).isEqualTo("first");
    }
    assertThat(cache.getIfPresent(second)).isEqualTo("second");
  }

  /** Thread-local probes cannot cross-publish or replace another exact key under collisions. */
  @Test
  public void concurrentSameKeyAndEqualHashPublicationsKeepEachResidentWinner() throws Exception {
    GremlinShapeBuffer.hasherForTests = value -> 1;
    Cache<GremlinShapeKey, Integer> cache = CacheBuilder.newBuilder().maximumSize(8).build();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 4; i++) {
        final int index = i;
        jobs.add(executor.submit(() -> {
          start.await();
          try (var lease = GremlinShapeBuffer.acquire()) {
            lease.append(index % 2);
            var key = lease.freeze();
            var resident = cache.asMap().putIfAbsent(key, index);
            assertThat(cache.getIfPresent(lease)).isEqualTo(resident == null ? index : resident);
          }
          return null;
        }));
      }
      start.countDown();
      for (var job : jobs) {
        job.get(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
    assertThat(cache.size()).isEqualTo(2);
  }

  /** Every finite capacity accepts its last slot and rejects the first slot beyond the bound. */
  @Test
  public void tokenEventAndNestingBoundsAbortWithoutGrowingScratch() {
    try (var lease = GremlinShapeBuffer.acquire()) {
      for (int i = 0; i < GremlinShapeBuffer.TOKEN_LIMIT; i++) {
        lease.append(i);
      }
      assertThat(lease.freeze().size()).isEqualTo(GremlinShapeBuffer.TOKEN_LIMIT);
      assertThatThrownBy(() -> lease.append("overflow"))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
      for (int i = 0; i < GremlinShapeBuffer.EVENT_LIMIT; i++) {
        lease.defer(() -> {
        });
      }
      assertThatThrownBy(() -> lease.defer(() -> {
      }))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
      for (int i = 0; i < GremlinShapeBuffer.DEPTH_LIMIT; i++) {
        lease.enter(__.identity().asAdmin());
      }
      assertThatThrownBy(() -> lease.enter(__.identity().asAdmin()))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
    }
    try (var lease = GremlinShapeBuffer.acquire()) {
      assertThat(lease.size()).isZero();
      lease.replay();
      var traversal = __.identity().asAdmin();
      lease.enter(traversal);
      assertThatThrownBy(() -> lease.enter(traversal))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
    }
  }

  /** A nested caller takes the full path and cannot close or overwrite the active outer lease. */
  @Test
  public void reentrancyAndExceptionCleanupLeaveOuterLeaseAndFrozenOutputOwned() {
    GremlinShapeKey frozen;
    try (var lease = GremlinShapeBuffer.acquire()) {
      lease.append("outer");
      assertThat(GremlinShapeBuffer.acquire()).isNull();
      frozen = lease.freeze();
      var nested = GremlinStepWalker.extractShape(
          graph.traversal().V().has("age", 2).asAdmin(), graphSession());
      assertThat(nested.bindings()).containsEntry(0, 2);
      assertThat(lease.token(0)).isEqualTo("outer");
    }
    try (var lease = GremlinShapeBuffer.acquire()) {
      lease.append("next");
      assertThat(frozen).isEqualTo(new GremlinShapeKey(new Object[] {"outer"}));
      assertThat(lease).isNotEqualTo(frozen);
    }
  }

  /** The last HasStep aborts at its custom operator, before any remaining container is read. */
  @Test
  public void unsupportedFinalPredicateAbortsFastEmissionAndSafetyKeepsApplicationNative() {
    var conversions = new java.util.concurrent.atomic.AtomicInteger();
    var operator =
        new org.apache.tinkerpop.gremlin.process.traversal.PBiPredicate<Object, Object>() {
          @Override
          public boolean test(Object first, Object second) {
            return true;
          }

          @Override
          public String toString() {
            conversions.incrementAndGet();
            return "custom";
          }
        };
    var traversal = graph.traversal().V().has("age", 1)
        .has("age", new P<>(operator, 1)).asAdmin();
    var last = (HasStep<?>) traversal.getSteps().getLast();
    var remaining = org.mockito.Mockito.spy(
        new org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer("age", P.eq(1)));
    last.addHasContainer(remaining);
    org.mockito.Mockito.clearInvocations(remaining);
    try (var lease = GremlinShapeBuffer.acquire()) {
      assertThatThrownBy(() -> extract(traversal, lease))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
      assertThat(conversions).hasValue(0);
      org.mockito.Mockito.verifyNoInteractions(remaining);
      // The unsupported operator must not emit even its "bi" token.
      assertThat(lease.token(lease.size() - 1)).isEqualTo("age");
    }
    var nativeTraversal = traversal.clone();
    nativeTraversal.setStrategies(traversal.getStrategies().clone());
    nativeTraversal.getStrategies().removeStrategies(GremlinToMatchStrategy.class);
    var expected = nativeTraversal.toList();
    assertThat(expected).containsExactly(graph.traversal().V().has("name", "Alice").next());
    var full = GremlinStepWalker.extractShape(traversal, graphSession());
    assertThat(conversions.get()).isPositive();
    TextShapeOracle.verify(traversal);
    var observed = new ArrayList<GremlinShapeExtractor.Extraction>();
    var strategy = new GremlinToMatchStrategy(t -> null, GremlinToMatchStrategy::buildPlan, true);
    strategy.extractionObserver = observed::add;
    var counters = new GremlinShapeBuffer.Counters();
    GremlinShapeBuffer.startCounting(counters);
    var steps = List.copyOf(traversal.getSteps());
    int conversionsBeforeAdmission = conversions.get();
    strategy.apply(traversal);
    assertThat(QuerySafetyAdmission.stored(traversal).reason())
        .isEqualTo(QuerySafetyAdmission.Reason.USER_FUNCTION);
    assertThat(traversal.getSteps()).containsExactlyElementsOf(steps);
    assertThat(conversions).hasValue(conversionsBeforeAdmission);
    assertThat(counters.leases).isZero();
    assertThat(counters.fallbacks).isZero();
    assertThat(counters.emissions).isZero();
    assertThat(observed).isEmpty();
    assertThat(full.key()).isNotNull();
    assertThat((List<?>) traversal.toList()).isEqualTo(expected);
    assertScratchCleared();
  }

  /** Real child scopes accept depth 16 and reject depth 17, with a single complete fallback. */
  @Test
  public void nestedTraversalDepthBoundaryUsesExtractorAndPreservesReferenceResults() {
    for (int depth : List.of(GremlinShapeBuffer.DEPTH_LIMIT,
        GremlinShapeBuffer.DEPTH_LIMIT + 1)) {
      var traversal = nestedTraversal(depth);
      TextShapeOracle.verify(traversal);
      try (var lease = GremlinShapeBuffer.acquire()) {
        if (depth == GremlinShapeBuffer.DEPTH_LIMIT) {
          assertThat(extract(traversal, lease).key())
              .isEqualTo(GremlinStepWalker.extractShape(traversal, graphSession()).key());
        } else {
          assertThatThrownBy(() -> extract(traversal, lease))
              .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
        }
      }
      assertAppliedBoundary(traversal, depth > GremlinShapeBuffer.DEPTH_LIMIT);
      assertScratchCleared();
    }
  }

  /** Real HasStep replay fills the last event slot, then falls back at the next HasStep. */
  @Test
  public void replayEventBoundaryThroughRealHasStepsPreservesResults() {
    for (int count : List.of(42, 43)) {
      var traversal = graph.traversal().V();
      for (int i = 0; i < count; i++) {
        traversal.asAdmin().addStep(new HasStep<>(traversal.asAdmin(),
            new org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer(
                "age", P.eq(1))));
      }
      TextShapeOracle.verify(traversal.asAdmin());
      try (var lease = GremlinShapeBuffer.acquire()) {
        if (count == 42) {
          assertThat(extract(traversal.asAdmin(), lease).bindings()).containsEntry(0, 1);
          assertThat(scratchCount(lease, "eventCount")).isEqualTo(GremlinShapeBuffer.EVENT_LIMIT);
        } else {
          assertThatThrownBy(() -> extract(traversal.asAdmin(), lease))
              .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
        }
      }
      assertAppliedBoundary(traversal.asAdmin(), count == 43);
      assertScratchCleared();
    }
  }

  /** Real ValuesSteps fill exactly 1024 tokens at 474 keys and fall back at 475 keys. */
  @Test
  public void tokenBoundaryThroughRealTraversalPreservesReferenceKeyAndNativeResults() {
    for (int count : List.of(474, 475)) {
      var keys = new String[count];
      keys[0] = "name";
      for (int i = 1; i < count; i++) {
        keys[i] = "absent" + i;
      }
      var traversal = graph.traversal().V().has("age", 1).values(keys).asAdmin();
      TextShapeOracle.verify(traversal);
      var full = GremlinStepWalker.extractShape(traversal, graphSession());
      // Flags, header and the three steps emit 76 fixed tokens plus two per property key.
      assertThat(full.key().size()).isEqualTo(76 + 2 * count);
      try (var lease = GremlinShapeBuffer.acquire()) {
        if (count == 474) {
          assertThat(extract(traversal, lease).key()).isEqualTo(full.key());
          assertThat(lease.size()).isEqualTo(1024);
        } else {
          assertThatThrownBy(() -> extract(traversal, lease))
              .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
          assertThat(lease.size()).isEqualTo(1024);
        }
      }
      assertAppliedBoundary(traversal, count == 475);
      assertScratchCleared();
    }
  }

  /** A self-containing structural predicate rejects fast emission and never publishes a key. */
  @Test
  public void cyclicStructuralPredicateFallsBackIncompleteWithoutCachePublication() {
    var cycle = new ArrayList<Object>();
    cycle.add(cycle);
    // Label operands are structural, so the complete encoder must visit the collection itself.
    var traversal = graph.traversal().V().has(T.label, P.eq(cycle)).asAdmin();
    try (var lease = GremlinShapeBuffer.acquire()) {
      assertThatThrownBy(() -> extract(traversal, lease))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
    }
    var full = GremlinStepWalker.extractShape(traversal, graphSession());
    assertThat(full.complete()).isFalse();
    var cache = GremlinPlanCache.instance(graphSession());
    assertThat(cache.containsTranslation(full.key())).isFalse();
    var translations = new java.util.concurrent.atomic.AtomicInteger();
    var strategy = new GremlinToMatchStrategy(t -> {
      translations.incrementAndGet();
      return null;
    }, GremlinToMatchStrategy::buildPlan, true);
    var observations = new java.util.concurrent.atomic.AtomicInteger();
    strategy.extractionObserver = extraction -> {
      assertThat(extraction.complete()).isFalse();
      assertThat(extraction.key()).isEqualTo(full.key());
      observations.incrementAndGet();
    };
    // Two applications must both fall back and walk. Neither may publish even a decline.
    for (int attempt = 0; attempt < 2; attempt++) {
      var counters = new GremlinShapeBuffer.Counters();
      GremlinShapeBuffer.startCounting(counters);
      strategy.apply(traversal);
      assertThat(counters.emissions).isEqualTo(1);
      assertThat(counters.fallbacks).isEqualTo(1);
      assertThat(counters.hits).isZero();
      assertThat(cache.containsTranslation(full.key())).isFalse();
      assertScratchCleared();
    }
    assertThat(observations).hasValue(2);
    assertThat(translations).hasValue(2);
  }

  /** A controlled cyclic child is rejected on reentry, before any recursive child emission. */
  @Test
  public void cyclicTraversalChildAbortsOnFirstReentryAndClearsScratch() {
    var traversal = nestedTraversal(2);
    var step = org.mockito.Mockito.spy(
        (org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep<
            ?>) traversal.getEndStep());
    traversal.removeStep(traversal.getSteps().size() - 1);
    traversal.addStep(step);
    org.mockito.Mockito.doReturn(List.of(traversal)).when(step).getLocalChildren();
    try (var lease = GremlinShapeBuffer.acquire()) {
      assertThatThrownBy(() -> extract(traversal, lease))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
      assertThat(scratchCount(lease, "depth")).isZero();
      org.mockito.Mockito.verify(step).getLocalChildren();
    }
    // Full extraction requires a finite traversal. Restore the controlled child before replay.
    org.mockito.Mockito.doCallRealMethod().when(step).getLocalChildren();
    TextShapeOracle.verify(traversal);
    assertAppliedBoundary(traversal, false);
    assertScratchCleared();
  }

  /** Observer-retained warm extractions fail loudly after close and after a later lease starts. */
  @Test
  public void retainedWarmExtractionRejectsClosedAndReusedLease() {
    var strategy = new GremlinToMatchStrategy(t -> null, GremlinToMatchStrategy::buildPlan, true);
    strategy.apply(graph.traversal().V().has("age", 1).asAdmin());
    var observed = new ArrayList<GremlinShapeExtractor.Extraction>();
    strategy.extractionObserver = observed::add;
    strategy.apply(graph.traversal().V().has("age", 2).asAdmin());
    var retained = observed.getFirst();
    assertThatThrownBy(retained::key).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(retained::bindings).isInstanceOf(IllegalStateException.class);
    try (var lease = GremlinShapeBuffer.acquire()) {
      lease.append("foreign");
      assertThatThrownBy(retained::key).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(retained::lookupKey).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(retained::hasContributions).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(retained::nativeOperands).isInstanceOf(IllegalStateException.class);
    }
  }

  /** Normal, duplicate and exceptional scans release the same scratch without retaining labels. */
  @Test
  public void earlyLabelScratchIsExactClearedAndReentrant() throws Exception {
    var scratch = LabelScanScratch.acquire();
    scratch.close();
    try {
      var unique = graph.traversal().V().as("shared").asAdmin();
      unique.getStartStep().addLabel(null);
      assertThat(GremlinStepWalker.hasDuplicateStepLabels(unique)).isFalse();
      assertReleasedScratch(scratch);
      var duplicate = graph.traversal().V().filter(__.out().as("shared"))
          .filter(__.in().as("shared")).asAdmin();
      assertThat(GremlinStepWalker.hasDuplicateStepLabels(duplicate)).isTrue();
      assertReleasedScratch(scratch);

      var child = org.mockito.Mockito.spy(__.out().asAdmin());
      var throwing = graph.traversal().V().as("retained-on-failure").filter(child).asAdmin();
      var failure = new IllegalStateException("scan failure");
      org.mockito.Mockito.doThrow(failure).when(child).getSteps();
      assertThatThrownBy(() -> GremlinStepWalker.hasDuplicateStepLabels(throwing))
          .isSameAs(failure);
      assertReleasedScratch(scratch);

      try (var outer = LabelScanScratch.acquire()) {
        assertThat(outer).isSameAs(scratch);
        assertThat(outer.add("shared")).isTrue();
        assertThat(GremlinStepWalker.hasDuplicateStepLabels(unique)).isFalse();
        assertThat(outer.add("shared")).isFalse();
        for (int i = 0; i <= LabelScanScratch.LIMIT; i++) {
          assertThat(outer.add("key" + i)).isTrue();
        }
        assertThat(outer.add("key64")).isFalse();
        assertThat(outer.add("shared")).isFalse();
      }
      assertReleasedScratch(scratch);
      var large = graph.traversal().V();
      for (int i = 0; i <= LabelScanScratch.LIMIT; i++) {
        large.out().as("label" + i);
      }
      assertThat(GremlinStepWalker.hasDuplicateStepLabels(large.asAdmin())).isFalse();
      assertReleasedScratch(scratch);
      large.filter(__.in().as("label64"));
      assertThat(GremlinStepWalker.hasDuplicateStepLabels(large.asAdmin())).isTrue();
      assertReleasedScratch(scratch);
    } finally {
      scratch.close();
    }
  }

  private static void assertReleasedScratch(LabelScanScratch scratch) throws Exception {
    // Inspect before acquire, which sets leased and can hide a failure by returning a fresh object.
    for (var name : List.of("labels", "size", "overflow", "leased")) {
      var field = LabelScanScratch.class.getDeclaredField(name);
      field.setAccessible(true);
      switch (name) {
        case "labels" -> assertThat((Object[]) field.get(scratch)).containsOnlyNulls();
        case "size" -> assertThat(field.getInt(scratch)).isZero();
        case "overflow" -> assertThat(field.get(scratch)).isNull();
        case "leased" -> assertThat(field.getBoolean(scratch)).isFalse();
        default -> throw new AssertionError(name);
      }
    }
    try (var reused = LabelScanScratch.acquire()) {
      assertThat(reused).isSameAs(scratch);
    }
  }

  /** Ordered synthetic union arms reuse one scanned list rather than repeat getStrategy lookups. */
  @Test
  public void orderedUnionForkReusesSharedStrategyReferences() {
    var traversal = graph.traversal().V().union(
        __.order().by(
            new com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal<>())
            .out("e").limit(1),
        __.out("e")).asAdmin();
    var analyses = new java.util.concurrent.atomic.AtomicInteger();
    traversal.getStrategies().addStrategies(
        new TraverserMergeFacts.AnalysisObserver(analyses::incrementAndGet));
    var strategies = org.mockito.Mockito.spy(traversal.getStrategies());
    traversal.setStrategies(strategies);
    org.mockito.Mockito.clearInvocations(strategies);
    GremlinToMatchStrategy.instance().apply(traversal);
    assertThat(analyses.get()).isPositive();
    org.mockito.Mockito.verify(strategies).iterator();
    org.mockito.Mockito.verify(strategies, org.mockito.Mockito.never())
        .getStrategy(org.mockito.ArgumentMatchers.any());
  }

  /** Only a safety view over the captured list can reuse references, not clones or replacements. */
  @Test
  public void safetyEvidenceReusesReferencesButClonedAndReplacedListsDoNot() {
    var traversal = graph.traversal().V().barrier(2500).valueMap("name").asAdmin();
    var environment = TranslationEnvironment.resolve(traversal, graphSession());
    assertThat(QuerySafetyAdmission.prepare(traversal).reason())
        .isEqualTo(QuerySafetyAdmission.Reason.SAFE);
    assertThat(TranslationEnvironment.references(traversal, environment))
        .isSameAs(environment.references());
    var clone = traversal.clone();
    clone.setStrategies(traversal.getStrategies().clone());
    assertThat(TranslationEnvironment.references(clone, environment)).isNull();
    traversal.setStrategies(
        new org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies());
    assertThat(TranslationEnvironment.references(traversal, environment)).isNull();
  }

  /** Disabled counting ignores even installed counters, while enabled counting stays thread-local. */
  @Test
  public void testCountersAreGatedBeforeThreadLocalAccess() {
    var counters = new GremlinShapeBuffer.Counters();
    GremlinShapeBuffer.startCounting(counters);
    GremlinShapeBuffer.count(c -> c.emissions++);
    assertThat(counters.emissions).isEqualTo(1);
    GremlinShapeBuffer.countingForTests = false;
    GremlinShapeBuffer.count(c -> {
      throw new AssertionError("disabled instrumentation ran");
    });
    GremlinShapeBuffer.countingForTests = true;
    GremlinShapeBuffer.TEST_COUNTERS.remove();
    GremlinShapeBuffer.count(c -> {
      throw new AssertionError("missing thread counters ran");
    });
  }

  /** Root calls scan once and warm native hits need no MATCH-only objects or value replay. */
  @Test
  public void warmNativeHitScansOnceAndAllocatesNoMatchBookkeeping() {
    GremlinToMatchStrategy.instance().apply(graph.traversal().V().has("age", 1).asAdmin());
    var warm = graph.traversal().V().has("age", 2).asAdmin();
    var strategies = org.mockito.Mockito.spy(warm.getStrategies());
    warm.setStrategies(strategies);
    org.mockito.Mockito.clearInvocations(strategies);
    var counters = new GremlinShapeBuffer.Counters();
    GremlinShapeBuffer.startCounting(counters);
    var metrics = GremlinTranslationMetrics.of(graphSession());
    var nativeBefore = metrics.getRuleNative();
    GremlinToMatchStrategy.instance().apply(warm);
    assertThat(metrics.getRuleNative()).isEqualTo(nativeBefore + 1);
    org.mockito.Mockito.verify(strategies).iterator();
    org.mockito.Mockito.verify(strategies, org.mockito.Mockito.never())
        .getStrategy(org.mockito.ArgumentMatchers.any());
    assertThat(counters.emissions).isEqualTo(1);
    assertThat(counters.hits).isEqualTo(1);
    assertThat(counters.extractions).isZero();
    assertThat(counters.bindingMaps).isZero();
    assertThat(counters.contributions).isZero();
  }

  /** A GraphStep child keeps root settings but uses its own environment, requirements and sack. */
  @Test
  public void separateChildStrategyListKeepsRootAndCurrentScopes() {
    var child = __.V().barrier(2500).valueMap("name").asAdmin();
    var root = graph.traversal().with("polymorphicQuery", false)
        .withStrategies(
            org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy
                .instance())
        .V().filter(child).asAdmin();
    var childList =
        new org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies();
    childList.addStrategies(
        org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.EdgeLabelVerificationStrategy
            .build().create(),
        org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy
            .build().productiveKeys("name").create(),
        org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy.build()
            .initialValue(() -> 7).create());
    org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.RequirementsStrategy
        .addRequirements(childList);
    var analyses = new java.util.concurrent.atomic.AtomicInteger();
    childList.addStrategies(new TraverserMergeFacts.AnalysisObserver(analyses::incrementAndGet));
    child.setStrategies(childList);
    var env = TranslationEnvironment.resolve(child, graphSession());
    var settings = YTDBStrategyUtil.translationSettings(
        env.references().options, env.references().standard, graphSession());
    assertThat(settings.polymorphic()).isFalse();
    assertThat(settings.orderIncludesMissingKey()).isFalse();
    assertThat(env.edgeLabelVerification()).isTrue();
    assertThat(env.productiveKeys()).containsExactly("name");
    assertThat(env.references().requirements).isNotNull();
    assertThat(env.references().sack).isNotNull();
    assertThat(PropertyMapBulkingAdmission.capacity(child, env)).isZero();
    // SackStrategy.apply deliberately does not install a child-local sack on a non-root.
    assertThat(TraverserMergeFacts.from(child, env).sackPresent()).isFalse();
    assertThat(analyses).hasValue(1);
    assertThat(root.getStrategies()).isNotSameAs(child.getStrategies());
    child.setStrategies(
        new org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies());
    assertThat(TranslationEnvironment.resolve(child, graphSession()).edgeLabelVerification())
        .isFalse();
  }

  private Traversal.Admin<?, ?> nestedTraversal(int depth) {
    var child = __.has("age", 1);
    for (int i = 2; i < depth; i++) {
      child = __.filter(child);
    }
    return graph.traversal().V().filter(child).asAdmin();
  }

  private void assertAppliedBoundary(Traversal.Admin<?, ?> traversal, boolean fallback) {
    var full = GremlinStepWalker.extractShape(traversal, graphSession());
    var nativeTraversal = traversal.clone();
    nativeTraversal.setStrategies(traversal.getStrategies().clone());
    nativeTraversal.getStrategies().removeStrategies(GremlinToMatchStrategy.class);
    var expected = nativeTraversal.toList();
    var observed = new ArrayList<GremlinShapeExtractor.Extraction>();
    var strategy = new GremlinToMatchStrategy(t -> null, GremlinToMatchStrategy::buildPlan, true);
    strategy.extractionObserver = extraction -> {
      assertThat(extraction.key()).isEqualTo(full.key());
      observed.add(extraction);
    };
    // Publish once, then require a warm hit for admitted shapes or exactly one full fallback.
    strategy.apply(traversal.clone());
    var counters = new GremlinShapeBuffer.Counters();
    GremlinShapeBuffer.startCounting(counters);
    strategy.apply(traversal);
    assertThat(counters.fallbacks).isEqualTo(fallback ? 1 : 0);
    assertThat(counters.hits).isEqualTo(fallback ? 0 : 1);
    assertThat(observed).hasSize(2);
    assertThat((List<?>) traversal.toList()).isEqualTo(expected);
    assertThat(expected).hasSize(1);
  }

  private static int scratchCount(GremlinShapeBuffer buffer, String name) {
    try {
      var field = GremlinShapeBuffer.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.getInt(buffer);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void assertScratchCleared() {
    try (var lease = GremlinShapeBuffer.acquire()) {
      assertThat(lease).isNotNull();
      assertThat(lease.size()).isZero();
      assertThat(scratchCount(lease, "eventCount")).isZero();
      assertThat(scratchCount(lease, "depth")).isZero();
      for (var name : List.of("tokens", "events", "scopes")) {
        var field = GremlinShapeBuffer.class.getDeclaredField(name);
        field.setAccessible(true);
        assertThat((Object[]) field.get(lease)).containsOnlyNulls();
      }
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** Warm MATCH calls enumerate structure once and copy bindings before a later call reuses scratch. */
  @Test
  public void warmMatchHitHasOneEmissionAndCallOwnedBindings() {
    GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.set(true);
    try {
      var cold = graph.traversal().V().hasLabel("FastVertex").has("age", 1).values("name")
          .asAdmin();
      GremlinToMatchStrategy.instance().apply(cold);
      var counters = new GremlinShapeBuffer.Counters();
      GremlinShapeBuffer.startCounting(counters);
      var warmA = graph.traversal().V().hasLabel("FastVertex").has("age", 1).values("name")
          .asAdmin();
      var has = org.mockito.Mockito.spy((HasStep<?>) warmA.getSteps().get(1));
      warmA.removeStep(1);
      warmA.addStep(1, has);
      org.mockito.Mockito.clearInvocations(has);
      try (var walker = org.mockito.Mockito.mockStatic(GremlinStepWalker.class,
          org.mockito.Mockito.CALLS_REAL_METHODS)) {
        GremlinToMatchStrategy.instance().apply(warmA);
        walker.verify(() -> GremlinStepWalker.hasDuplicateStepLabels(warmA));
        walker.verify(() -> GremlinStepWalker.selectCollidesWithSideEffect(warmA));
      }
      // Root safety inspects predicates once before the single structural token emission.
      org.mockito.Mockito.verify(has, org.mockito.Mockito.times(2)).getHasContainers();
      assertThat(counters.leases).isEqualTo(1);
      assertThat(counters.emissions).isEqualTo(1);
      assertThat(counters.hits).isEqualTo(1);
      assertThat(counters.fallbacks).isZero();
      assertThat(counters.scopes).isEqualTo(1);
      assertThat(counters.countScans).isEqualTo(1);
      var warmB = graph.traversal().V().hasLabel("FastVertex").has("age", 2).values("name")
          .asAdmin();
      GremlinToMatchStrategy.instance().apply(warmB);
      assertThat(warmA.toList()).containsExactly("Alice");
      assertThat(warmB.toList()).containsExactly("Bob");
    } finally {
      GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.remove();
    }
  }

  /** Schema-changing transactions use the uncached path without ever acquiring scratch. */
  @Test
  public void ineligibleSchemaTransactionDoesNotAcquireLeaseOrEmitFastTokens() {
    graphSession().getMetadata().getSchema().createClass("FastSchemaChange");
    var counters = new GremlinShapeBuffer.Counters();
    GremlinShapeBuffer.startCounting(counters);
    GremlinToMatchStrategy.instance().apply(graph.traversal().V().has("age", 1).asAdmin());
    assertThat(counters.leases).isZero();
    assertThat(counters.emissions).isZero();
  }

  /** Allocation accounting isolates Guava recency records from buffer/probe comparison work. */
  @Test
  public void reportGuavaRecencyAllocationSeparatelyFromProbeComparison() {
    var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
        .getThreadMXBean();
    if (!bean.isThreadAllocatedMemoryEnabled()) {
      bean.setThreadAllocatedMemoryEnabled(true);
    }
    Cache<GremlinShapeKey, String> cache = CacheBuilder.newBuilder().maximumSize(8).build();
    var key = new GremlinShapeKey(new Object[] {"allocation"});
    cache.put(key, "resident");
    try (var probe = GremlinShapeBuffer.acquire()) {
      probe.append("allocation");
      for (int i = 0; i < 100000; i++) {
        cache.getIfPresent(probe);
      }
      var thread = Thread.currentThread().threadId();
      var before = bean.getThreadAllocatedBytes(thread);
      for (int i = 0; i < 200000; i++) {
        cache.getIfPresent(probe);
      }
      var guava = bean.getThreadAllocatedBytes(thread) - before;
      before = bean.getThreadAllocatedBytes(thread);
      for (int i = 0; i < 200000; i++) {
        if (!probe.equals(key) || probe.hashCode() != key.hashCode()) {
          throw new AssertionError("Probe identity changed");
        }
      }
      var comparison = bean.getThreadAllocatedBytes(thread) - before;
      System.out.printf(java.util.Locale.ROOT,
          "Guava hit allocation %.3f B/op, isolated probe comparison %.3f B/op%n",
          guava / 200000.0, comparison / 200000.0);
    }
  }

  private static boolean retainsProbe(Object root, Object probe, java.util.Set<Object> seen)
      throws IllegalAccessException {
    if (root == probe) {
      return true;
    }
    if (root == null || !seen.add(root)) {
      return false;
    }
    if (root instanceof Iterable<?> values) {
      for (var value : values) {
        if (retainsProbe(value, probe, seen)) {
          return true;
        }
      }
    } else if (root instanceof java.util.concurrent.atomic.AtomicReferenceArray<?> values) {
      for (int i = 0; i < values.length(); i++) {
        if (retainsProbe(values.get(i), probe, seen)) {
          return true;
        }
      }
    } else if (root instanceof Object[] values) {
      for (var value : values) {
        if (retainsProbe(value, probe, seen)) {
          return true;
        }
      }
    }
    for (var type = root.getClass(); type != null
        && type.getName().startsWith("com.google.common."); type = type.getSuperclass()) {
      for (var field : type.getDeclaredFields()) {
        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
            && !field.getType().isPrimitive()) {
          field.setAccessible(true);
          if (retainsProbe(field.get(root), probe, seen)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** Publication races run the actual generation-stamped cache with thread-safe session doubles. */
  @Test
  public void actualCacheKeepsFirstWinnerPerExactKeyUnderConcurrentForcedCollisions()
      throws Exception {
    GremlinShapeBuffer.hasherForTests = value -> 5;
    var db = org.mockito.Mockito.mock(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.class);
    org.mockito.Mockito.when(db.getConfiguration())
        .thenReturn(new com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration());
    var cache = new GremlinPlanCache(8);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 4; i++) {
        final int index = i;
        jobs.add(executor.submit(() -> {
          start.await();
          try (var lease = GremlinShapeBuffer.acquire()) {
            lease.append(index % 2);
            var winner = cache.putTranslationInternal(lease.freeze(),
                new GremlinTranslationTemplate.Decline(), db, cache.getGeneration());
            assertThat(cache.getTranslationInternal(lease, db)).isSameAs(winner);
          }
          return null;
        }));
      }
      start.countDown();
      for (var job : jobs) {
        job.get(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
    var first = cache.getTranslationInternal(new GremlinShapeKey(new Object[] {0}), db);
    var second = cache.getTranslationInternal(new GremlinShapeKey(new Object[] {1}), db);
    assertThat(first).isNotNull().isNotSameAs(second);
    assertThat(second).isNotNull();
  }

  /** An exception after emission clears every retained reference before the next compilation. */
  @Test
  public void exceptionDuringStrategyApplicationClearsUsedTokenEventAndScopeReferences()
      throws Exception {
    var strategy = new GremlinToMatchStrategy(traversal -> null,
        GremlinToMatchStrategy::buildPlan, true);
    strategy.extractionObserver = extraction -> {
      throw new IllegalStateException("test failure after emission");
    };
    strategy.apply(graph.traversal().V().has("age", 1).asAdmin());
    try (var lease = GremlinShapeBuffer.acquire()) {
      assertThat(lease).isNotNull();
      for (var fieldName : List.of("tokens", "events", "scopes")) {
        var field = GremlinShapeBuffer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        assertThat((Object[]) field.get(lease)).containsOnlyNulls();
      }
    }
  }

  /** Label scratch accepts exactly its finite bound and falls back at the next label. */
  @Test
  public void labelScratchBoundaryKeepsExactTokensAndRejectsFirstOverflow() {
    var traversal = graph.traversal().V().asAdmin();
    for (int i = 0; i < GremlinShapeBuffer.EVENT_LIMIT; i++) {
      traversal.getStartStep().addLabel("label" + i);
    }
    try (var lease = GremlinShapeBuffer.acquire()) {
      var fast = extract(traversal, lease);
      assertThat(fast.key()).isEqualTo(
          GremlinStepWalker.extractShape(traversal, graphSession()).key());
    }
    traversal.getStartStep().addLabel("overflow");
    try (var lease = GremlinShapeBuffer.acquire()) {
      assertThatThrownBy(() -> extract(traversal, lease))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
    }
  }

  /** Equal generation numbers never stand in for database identity or live per-database defaults. */
  @Test
  public void twoDatabasesOnOneThreadKeepSettingsBindingsAndClosedSessionOwnershipSeparate() {
    var config = getBaseConfiguration();
    config.setProperty(
        com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory.CONFIG_DB_NAME,
        databaseName + "Other");
    config.setProperty(
        com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory.CONFIG_DB_TYPE, dbType);
    config.setProperty(
        com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory.CONFIG_USER_ROLE, "admin");
    config.setProperty(
        com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory.CONFIG_CREATE_IF_NOT_EXISTS,
        true);
    var dbA = graphSession();
    var cacheA = GremlinPlanCache.instance(dbA);
    GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.set(true);
    try {
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> compiledA;
      try (var other =
          (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph) org.apache.tinkerpop.gremlin.structure.util.GraphFactory
              .open(config)) {
        other.addVertex(T.label, "FastVertex", "name", "Other", "age", 1);
        other.tx().commit();
        var txB = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction) other.tx();
        txB.readWrite();
        var dbB = txB.getDatabaseSession();
        var cacheB = GremlinPlanCache.instance(dbB);
        dbA.getConfiguration().setValue(
            GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, true);
        dbB.getConfiguration().setValue(
            GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, false);
        dbB.getConfiguration().setValue(
            GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
        while (cacheA.getGeneration() < cacheB.getGeneration()) {
          cacheA.invalidate();
        }
        while (cacheB.getGeneration() < cacheA.getGeneration()) {
          cacheB.invalidate();
        }
        assertThat(cacheA.getGeneration()).isEqualTo(cacheB.getGeneration());
        dbA.activateOnCurrentThread();
        var sourceA = graph.traversal().V().hasLabel("FastVertex").has("age", 1).values("name")
            .asAdmin();
        assertThat(YTDBStrategyUtil.translationSettings(sourceA, dbA).polymorphic()).isTrue();
        GremlinToMatchStrategy.instance().apply(sourceA);
        compiledA = graph.traversal().V().hasLabel("FastVertex").has("age", 1).values("name")
            .asAdmin();
        GremlinToMatchStrategy.instance().apply(compiledA);
        dbB.activateOnCurrentThread();
        var compiledB = other.traversal().V().hasLabel("FastVertex").has("age", 1).values("name")
            .asAdmin();
        assertThat(YTDBStrategyUtil.translationSettings(compiledB, dbB).polymorphic()).isFalse();
        GremlinToMatchStrategy.instance().apply(compiledB);
        assertThat(compiledB.toList()).containsExactly("Other");
      }
      dbA.activateOnCurrentThread();
      assertThat(compiledA.toList()).isEqualTo(List.of("Alice"));
      graph.tx().commit();
      var afterPoolReuse = graph.traversal().V().hasLabel("FastVertex").has("age", 2)
          .values("name");
      assertThat(afterPoolReuse.toList()).containsExactly("Bob");
    } finally {
      GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.remove();
    }
  }

  /** Unknown regex subclasses and comparators abort before invoking user conversion or flags. */
  @Test
  public void unknownRegexOperatorAndComparatorAbortBeforeUserCode() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var regex =
        new org.apache.tinkerpop.gremlin.process.traversal.Text.RegexPredicate(".*", false) {
          @Override
          public boolean isNegate() {
            calls.incrementAndGet();
            return false;
          }
        };
    var predicate = new TextP(regex, ".*");
    try (var lease = GremlinShapeBuffer.acquire()) {
      assertThatThrownBy(() -> extract(
          graph.traversal().V().has("name", predicate).asAdmin(), lease))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
    }
    java.util.Comparator<Object> comparator = new java.util.Comparator<>() {
      @Override
      public int compare(Object first, Object second) {
        return 0;
      }

      @Override
      public String toString() {
        calls.incrementAndGet();
        return "custom";
      }
    };
    try (var lease = GremlinShapeBuffer.acquire()) {
      assertThatThrownBy(() -> extract(
          graph.traversal().V().order().by("name", comparator).asAdmin(), lease))
          .isSameAs(GremlinShapeBuffer.UNSUPPORTED);
    }
    assertThat(calls).hasValue(0);
  }

  /** Unsliced ordered hops retain deferred class narrowing across transparent barriers. */
  @Test
  public void unslicedOrderedHopAcrossBarriersMatchesFrozenDeferredBoundaryContexts() {
    graph.addVertex(T.label, "OtherFastVertex", "name", "Other");
    graph.tx().commit();
    var traversal = graph.traversal().V().order().by("name").out("edge")
        .hasLabel("FastVertex").barrier(2500).hasLabel("OtherFastVertex")
        .barrier(2500).has("name", TextP.startingWith("A")).asAdmin();
    TextShapeOracle.verify(traversal);
    var reference = GremlinStepWalker.extractShape(traversal, graphSession());
    try (var lease = GremlinShapeBuffer.acquire()) {
      var fast = extract(traversal, lease);
      assertThat(fast.lookupKey()).isInstanceOf(GremlinShapeBuffer.class);
      assertThat(fast.hasContributions()).isEqualTo(reference.hasContributions());
      assertThat(fast.bindings()).isEqualTo(reference.bindings());
    }
  }

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private GremlinShapeExtractor.Extraction extract(Traversal.Admin<?, ?> traversal,
      GremlinShapeBuffer buffer) {
    var settings = YTDBStrategyUtil.translationSettings(traversal, graphSession());
    return GremlinShapeExtractor.extract(GremlinStepWalker.PRODUCTION_RECOGNISERS,
        GremlinStepWalker.TRANSPARENT_STEPS, traversal, settings.orderIncludesMissingKey(),
        settings.nullPlacements(), settings.polymorphic(),
        TranslationEnvironment.resolve(traversal, graphSession()),
        new GremlinShapeEncoder(graphSession().getSchema(), buffer));
  }
}
