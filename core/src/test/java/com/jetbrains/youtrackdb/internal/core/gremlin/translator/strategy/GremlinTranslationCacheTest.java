package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ConstantTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Before;
import org.junit.Test;

/**
 * Translation-cache (pre-walk shape key) and copy-on-open behaviour: a second {@code has()} value
 * splices the cached template, a declining shape is cached as decline, schema invalidation clears
 * the map, and {@code getPlan()} before the first open is the shared template.
 */
public class GremlinTranslationCacheTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(this::graphSession);

  @Before
  public void enableTranslator() {
    support.setTranslatorEnabled(true);
    GremlinPlanCache.instance(graphSession()).invalidate();
  }

  /**
   * Two walks that differ only in the {@code has()} value share a translation-cache entry, and the
   * second walk returns the second value's row — the harvested binding rebinds the cached plan.
   */
  @Test
  public void secondHasValue_hitsTranslationCache_andReturnsSecondRow() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var hitsBefore = cache.getTranslationHits();
    var missesBefore = cache.getTranslationMisses();
    var first = apply(() -> graph.traversal().V().has("age", 30));
    assertThat(sortedNames(first)).containsExactly("Alice");
    assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 1);
    assertThat(cache.getTranslationHits()).isEqualTo(hitsBefore);

    var second = apply(() -> graph.traversal().V().has("age", 40));
    assertThat(sortedNames(second)).containsExactly("Bob");
    assertThat(cache.getTranslationHits()).isEqualTo(hitsBefore + 1);
    assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 1);
  }

  /**
   * A walker-declined shape is stored as {@code Decline}; the second apply hits that entry and
   * leaves the native step list untouched.
   */
  @Test
  public void declinedShape_isCached_secondApplyLeavesNativeSteps() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var hitsBefore = cache.getTranslationHits();
    var missesBefore = cache.getTranslationMisses();
    var first = graph.traversal().V().is(P.eq(1)).asAdmin();
    GremlinToMatchStrategy.instance().apply(first);
    assertThat(first.getStartStep()).isNotInstanceOf(YTDBMatchPlanStep.class);
    assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 1);

    var second = graph.traversal().V().is(P.eq(1)).asAdmin();
    GremlinToMatchStrategy.instance().apply(second);
    assertThat(second.getStartStep()).isNotInstanceOf(YTDBMatchPlanStep.class);
    assertThat(cache.getTranslationHits()).isEqualTo(hitsBefore + 1);
    assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 1);
  }

  /**
   * {@code P.lt(true)} and {@code P.lt("m")} must not share a translation-cache entry: the
   * comparability block is part of the shape, and serving the Boolean guard to the String walk
   * would keep extra rows.
   */
  @Test
  public void guardedLiteralTypes_doNotShareTranslationEntry() {
    graph.addVertex(T.label, "Types", "name", "s_alpha", "v", "alpha");
    graph.addVertex(T.label, "Types", "name", "s_zulu", "v", "zulu");
    graph.addVertex(T.label, "Types", "name", "b_true", "v", true);
    graph.addVertex(T.label, "Types", "name", "b_false", "v", false);
    graph.addVertex(T.label, "Types", "name", "n_ten", "v", 10);
    graph.tx().commit();

    var booleanKey =
        GremlinStepWalker.extractShape(
            graph.traversal().V().not(__.has("v", P.lt(true))).asAdmin(), graphSession())
            .key();
    var stringKey =
        GremlinStepWalker.extractShape(
            graph.traversal().V().not(__.has("v", P.lt("m"))).asAdmin(), graphSession())
            .key();
    assertThat(booleanKey).isNotEqualTo(stringKey);

    var booleanRun = apply(() -> graph.traversal().V().not(__.has("v", P.lt(true))));
    assertThat(sortedNames(booleanRun)).containsExactly("b_true", "n_ten", "s_alpha", "s_zulu");

    var stringRun = apply(() -> graph.traversal().V().not(__.has("v", P.lt("m"))));
    assertThat(sortedNames(stringRun)).containsExactly("b_false", "b_true", "n_ten", "s_zulu");
  }

  /**
   * After {@code apply} without iterating, {@code getPlan()} is the shared closed template. After
   * {@code toList()}, it is a live copy — copy-on-open, not copy-during-apply.
   */
  @Test
  public void getPlan_beforeIterate_isSharedTemplate_afterIterate_isCopy() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    var admin = graph.traversal().V().has("age", 30).asAdmin();
    GremlinToMatchStrategy.instance().apply(admin);
    assertThat(admin.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    @SuppressWarnings("unchecked")
    var step = (YTDBMatchPlanStep<?, Vertex>) admin.getStartStep();
    var template = step.getPlan();
    var walked = GremlinStepWalker.production()
        .walk(graph.traversal().V().has("age", P.eq(30)).asAdmin());
    assertThat(walked).isNotNull();
    assertThat(walked.inputs()).isNotNull();
    var fp = GremlinPlanFingerprint.fingerprint(walked.inputs(), walked.shaping());
    assertThat(GremlinPlanCache.instance(graphSession()).peekStored(fp)).isSameAs(template);

    admin.toList();
    assertThat(step.getPlan()).isNotSameAs(template);
  }

  /**
   * Step-local tokens the walker reads (select/project keys, Pop, valueMap keys, tail window, where
   * labels, dedup scope) must discriminate the shape key. A shared entry would splice the first
   * walk's MATCH projection / {@code ResultShaping} onto the second query.
   */
  @Test
  public void stepLocalTokens_discriminateShapeKeys() {
    assertThat(shapeKey(() -> graph.traversal().V().as("a").out("knows").as("b").select("a")))
        .isNotEqualTo(
            shapeKey(() -> graph.traversal().V().as("a").out("knows").as("b").select("b")));
    assertThat(shapeKey(() -> graph.traversal().V().as("a").select(Pop.last, "a")))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().as("a").select(Pop.first, "a")));
    assertThat(shapeKey(() -> graph.traversal().V().valueMap("name")))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().valueMap("age")));
    assertThat(shapeKey(() -> graph.traversal().V().elementMap("name")))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().elementMap("age")));
    assertThat(shapeKey(() -> graph.traversal().V().project("x").by("name")))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().project("y").by("name")));
    assertThat(shapeKey(() -> graph.traversal().V().values("name").tail(1)))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().values("name").tail(2)));
    assertThat(
        shapeKey(
            () -> graph.traversal().V().as("a").out("knows").as("b").where("a", P.eq("b"))))
        .isNotEqualTo(
            shapeKey(
                () -> graph.traversal().V().as("a").out("knows").as("b").where("b", P.eq("a"))));
    assertThat(shapeKey(() -> graph.traversal().V().as("a").out("knows").as("b").dedup("a")))
        .isNotEqualTo(
            shapeKey(() -> graph.traversal().V().as("a").out("knows").as("b").dedup("b")));
    assertThat(shapeKey(() -> graph.traversal().V().order().by("name")))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().order().by("age")));
    assertThat(shapeKey(() -> graph.traversal().V().groupCount().by("name")))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().groupCount().by("age")));
    assertThat(shapeKey(() -> graph.traversal().V().project("x").by("name")))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().project("x").by("age")));
    assertThat(shapeKey(() -> graph.traversal().V().has("name", TextP.regex("^mar"))))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().has("name", TextP.notRegex("^mar"))));
  }

  /**
   * After {@code select("a")} is cached, {@code select("b")} on the same hop must still return the
   * hop target, not the origin the first walk projected.
   */
  @Test
  public void selectDifferentLabels_doNotShareCachedPlan() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    var origin =
        apply(() -> graph.traversal().V().has("name", "Alice").as("a").out("knows").as("b")
            .select("a"));
    assertThat(sortedNames(origin)).containsExactly("Alice");

    var target =
        apply(() -> graph.traversal().V().has("name", "Alice").as("a").out("knows").as("b")
            .select("b"));
    assertThat(sortedNames(target)).containsExactly("Bob");
  }

  /**
   * After {@code tail(1)} is cached, {@code tail(2)} must keep its own window — the limit lives in
   * the boundary {@code ResultShaping}, not in the MATCH statement.
   */
  @Test
  public void tailWindows_doNotShareCachedShaping() {
    graph.addVertex(T.label, "Person", "name", "Abe");
    graph.addVertex(T.label, "Person", "name", "Zed");
    graph.tx().commit();

    var tail1 =
        apply(() -> graph.traversal().V().order().by("name").values("name").tail(1));
    assertThat(tail1).isEqualTo(List.of("Zed"));

    var tail2 =
        apply(() -> graph.traversal().V().order().by("name").values("name").tail(2));
    assertThat(tail2).isEqualTo(List.of("Abe", "Zed"));
  }

  /**
   * After {@code valueMap("name")} is cached, {@code valueMap("age")} must project age, not name.
   */
  @Test
  public void valueMapKeys_doNotShareCachedProjection() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    apply(() -> graph.traversal().V().has("name", "Alice").valueMap("name"));
    @SuppressWarnings("unchecked")
    var ageMaps =
        (List<java.util.Map<String, Object>>) apply(
            () -> graph.traversal().V().has("name", "Alice").valueMap("age"));
    assertThat(ageMaps).hasSize(1);
    assertThat(ageMaps.getFirst()).containsKey("age");
    assertThat(ageMaps.getFirst()).doesNotContainKey("name");
  }

  /**
   * {@code Text.regex} and {@code Text.notRegex} share {@code RegexPredicate}; only {@code
   * isNegate()} distinguishes them. After {@code regex("^mar")} is cached, {@code notRegex("^mar")}
   * must still return the names that do not match, not splice the positive MATCHES plan.
   */
  @Test
  public void regexAndNotRegex_doNotShareCachedPlan() {
    graph.addVertex(T.label, "Person", "name", "marko");
    graph.addVertex(T.label, "Person", "name", "vadas");
    graph.tx().commit();

    var regex = apply(() -> graph.traversal().V().has("name", TextP.regex("^mar")));
    assertThat(sortedNames(regex)).containsExactly("marko");

    var notRegex = apply(() -> graph.traversal().V().has("name", TextP.notRegex("^mar")));
    assertThat(sortedNames(notRegex)).containsExactly("vadas");
  }

  /**
   * {@code order().by("name")} and {@code order().by("age")} share the hop/start class list and
   * differ only in a {@code ValueTraversal} property key. After the first is cached, the second
   * must still sort by age.
   */
  @Test
  public void orderByPropertyKeys_doNotShareCachedPlan() {
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    var byName = apply(() -> graph.traversal().V().order().by("name").values("name"));
    assertThat(byName).isEqualTo(List.of("Alice", "Bob"));

    var byAge = apply(() -> graph.traversal().V().order().by("age").values("name"));
    assertThat(byAge).isEqualTo(List.of("Bob", "Alice"));
  }

  /**
   * A lambda {@code by()} the extractor cannot name marks the extraction incomplete, so apply
   * neither reads nor writes the translation cache — fail-closed rather than a colliding template.
   */
  @Test
  public void unknownLambdaModulator_doesNotTouchTranslationCache() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var hitsBefore = cache.getTranslationHits();
    var missesBefore = cache.getTranslationMisses();
    var admin = graph.traversal().V().order().by(new ConstantTraversal<>("x")).asAdmin();
    var extraction = GremlinStepWalker.extractShape(admin, graphSession());
    assertThat(extraction.complete()).isFalse();

    GremlinToMatchStrategy.instance().apply(admin);
    assertThat(admin.getStartStep()).isNotInstanceOf(YTDBMatchPlanStep.class);
    assertThat(cache.getTranslationHits()).isEqualTo(hitsBefore);
    assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore);
  }

  /** The per-session polymorphism flag is part of the shape key; toggling it must split entries. */
  @Test
  public void polymorphismFlag_discriminatesShapeKeys() {
    final var polyOn = captureShapeKeyWithPolymorphic(true);
    final var polyOff = captureShapeKeyWithPolymorphic(false);
    assertThat(polyOn).isNotEqualTo(polyOff);
  }

  /**
   * The resolved productive-order setting is part of the shape key. The two values produce
   * different patterns for the same step list — one carries the order-key {@code IS DEFINED}
   * conjunct and the other does not — so they must not share an entry.
   */
  @Test
  public void productiveOrderSetting_discriminatesShapeKeys() {
    final var includingKey = captureOrderShapeKeyWith(true);
    final var portableKey = captureOrderShapeKeyWith(false);
    assertThat(includingKey).isNotEqualTo(portableKey);
  }

  /**
   * The detecting test for the storage-wide cache: translate a shape under one setting, FLIP the
   * setting INSIDE ONE CACHE LIFETIME with no invalidation in between, and translate again.
   *
   * <p>The second translation must MISS. Without the shape-key token it would hit, and the plan
   * built under the first setting would be spliced verbatim into a traversal running under the
   * second — across sessions, because the cache is storage-wide. The rows prove which semantics
   * each run actually got: three rows under the including default, two under the portable opt-out.
   */
  @Test
  public void flippingProductiveOrderSetting_missesTranslationCacheWithinOneLifetime() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.tx().commit();

    var config = graphSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    var cache = GremlinPlanCache.instance(graphSession());
    try {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, true);
      var missesBefore = cache.getTranslationMisses();
      var hitsBefore = cache.getTranslationHits();

      var including = apply(() -> graph.traversal().V().order().by("age").values("name"));
      assertThat(including)
          .as("the including default keeps the record that carries no age")
          .hasSize(3);
      assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 1);

      // Same shape, same cache, no invalidation: a warm hit, which proves the entry is live and
      // that the miss below is caused by the flip rather than by an empty cache.
      var warm = apply(() -> graph.traversal().V().order().by("age").values("name"));
      assertThat(warm).hasSize(3);
      assertThat(cache.getTranslationHits()).isEqualTo(hitsBefore + 1);
      assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 1);

      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, false);

      var portable = apply(() -> graph.traversal().V().order().by("age").values("name"));
      assertThat(cache.getTranslationMisses())
          .as("the flipped setting must key a different entry, so this translation misses")
          .isEqualTo(missesBefore + 2);
      assertThat(cache.getTranslationHits())
          .as("and it must not be served the plan built under the other setting")
          .isEqualTo(hitsBefore + 1);
      assertThat(portable)
          .as("the portable opt-out drops the record that carries no age")
          .hasSize(2);
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, previous);
    }
  }

  /**
   * Translation-cache shape keys are value-independent only within one runtime class. Different
   * String values share a key, but an Integer and a Long do not.
   */
  @Test
  public void runtimeValueClass_partitionsShapeKey() {
    var intThirty = shapeKey(() -> graph.traversal().V().has("age", P.eq(30)));
    var intNinetyNine = shapeKey(() -> graph.traversal().V().has("age", P.eq(99)));
    var longThirty = shapeKey(() -> graph.traversal().V().has("age", P.eq(30L)));

    assertThat(intThirty).isEqualTo(intNinetyNine);
    assertThat(intThirty).isNotEqualTo(longThirty);
  }

  /**
   * Row-level guard on limit literals: after {@code limit(1)} is cached, {@code limit(2)} must still
   * return two rows. A fingerprint that collapsed both limits to {@code ?} would serve the first
   * plan and silently truncate.
   */
  @Test
  public void differentLimits_afterCacheWarm_returnCorrectCardinality() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 1);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 2);
    graph.addVertex(T.label, "Person", "name", "Carol", "age", 3);
    graph.tx().commit();

    assertThat(apply(() -> graph.traversal().V().limit(1))).hasSize(1);
    assertThat(apply(() -> graph.traversal().V().limit(2)))
        .as("limit(2) must not reuse a cached limit(1) plan")
        .hasSize(2);
  }

  /**
   * Row-level guard on projection keys: after {@code values("name")} is cached, {@code values("age")}
   * must emit ages, not names.
   */
  @Test
  public void differentValuesKeys_afterCacheWarm_doNotCrossContaminate() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    var names = apply(() -> graph.traversal().V().order().by("name").values("name"));
    assertThat(names).isEqualTo(List.of("Alice", "Bob"));

    var ages = apply(() -> graph.traversal().V().order().by("name").values("age"));
    assertThat(ages)
        .as("values(age) must not reuse a cached values(name) plan")
        .isEqualTo(List.of(30, 40));
  }

  /**
   * Integer vs Long {@code has(age)} partitions the shape key; after warming the Integer entry, the
   * Long walk must still answer correctly for its own binding (and must not be served the Integer
   * plan).
   */
  @Test
  public void integerThenLongAge_afterCacheWarm_bothReturnCorrectRows() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    assertThat(sortedNames(apply(() -> graph.traversal().V().has("age", 30))))
        .containsExactly("Alice");
    assertThat(sortedNames(apply(() -> graph.traversal().V().has("age", 30L))))
        .as("Long 30 must not reuse the Integer-30 cached plan incorrectly")
        .containsExactly("Alice");
    assertThat(sortedNames(apply(() -> graph.traversal().V().has("age", 40L))))
        .containsExactly("Bob");
  }

  /**
   * Predicate order can differ between Gremlin shapes that still compile to one PQD. Warm with
   * {@code has(age).has(name)}, then run {@code has(name).has(age)} with different bindings — both
   * must return their own row (walk + PQD hit + shape backfill must not cross-bind).
   */
  @Test
  public void swappedHasOrder_afterCacheWarm_returnsOwnRow() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    assertThat(
        sortedNames(
            apply(() -> graph.traversal().V().has("age", 30).has("name", "Alice"))))
        .containsExactly("Alice");
    assertThat(
        sortedNames(apply(() -> graph.traversal().V().has("name", "Bob").has("age", 40))))
        .as("swapped has() order must not return the previously cached person's row")
        .containsExactly("Bob");
  }

  /**
   * A user {@code as(...)} label parked on a transparent barrier is part of the shape, and so is the
   * position of that barrier. {@code out().as(mid)[on barrier].out()} and {@code
   * out().out().as(mid)[on barrier]} leave every significant step unlabelled, so before the barrier
   * labels were encoded both spellings produced one key. The walker binds the label to the boundary
   * reached where the barrier sits, so the two spellings name different hops and must not share a
   * cache entry.
   */
  @Test
  public void barrierLabelPosition_discriminatesShapeKeys() {
    var afterFirstHop = shapeKey(barrierLabelAfterHop(1));
    var afterSecondHop = shapeKey(barrierLabelAfterHop(2));

    assertThat(afterFirstHop)
        .as("a barrier label after the first hop names a different alias than after the second")
        .isNotEqualTo(afterSecondHop);
  }

  /**
   * The row-level half of {@link #barrierLabelPosition_discriminatesShapeKeys}. Over the chain
   * Alice knows Bob knows Carol, {@code out().as(mid)[on barrier].out().select(mid)} must return
   * Bob and {@code out().out().as(mid)[on barrier].select(mid)} must return Carol. Warming the cache
   * with the first shape must not serve its plan to the second.
   */
  @Test
  public void barrierLabelPosition_doNotShareCachedPlan() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    bob.addEdge("knows", carol);
    graph.tx().commit();

    var afterFirstHop = applyAdmin(barrierLabelAfterHop(1));
    assertThat(sortedNames(afterFirstHop))
        .as("a label bound after the first hop selects the middle vertex")
        .containsExactly("Bob");

    var afterSecondHop = applyAdmin(barrierLabelAfterHop(2));
    assertThat(sortedNames(afterSecondHop))
        .as("the second shape must not be served the first shape's cached plan")
        .containsExactly("Carol");
  }

  /**
   * A barrier with no label on it is part of the shape too, because the walker reads a skipped
   * barrier through the fold latch. {@code g.V().has("name", gt(27))} is folded and compares with
   * SQL ordering, which ranks a String above an Integer, while {@code
   * g.V().barrier().has("name", gt(27))} is unfolded and carries the per-record type guard, which
   * keeps only the numeric row. Over one class holding both runtime types the two spellings return
   * different rows, so one cache entry cannot serve both.
   */
  @Test
  public void unlabelledBarrier_doesNotShareCachedPlanWithTheFoldedSpelling() {
    seedMixedRuntimeTypes();

    assertThat(shapeKey(() -> graph.traversal().V().has("name", P.gt(27))))
        .as("a barrier that closes the fold must discriminate the shape key")
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().barrier().has("name", P.gt(27))));

    var folded = apply(() -> graph.traversal().V().has("name", P.gt(27)));
    assertThat(sortedTags(folded))
        .as("the folded comparison keeps the SQL ordering answer, which ranks the String above 27")
        .containsExactly("loose_num", "loose_zulu");

    var unfolded = apply(() -> graph.traversal().V().barrier().has("name", P.gt(27)));
    assertThat(sortedTags(unfolded))
        .as("the unfolded comparison must not be served the folded plan, which drops its guard")
        .containsExactly("loose_num");
  }

  /**
   * Two vertices of one schema-less class holding both runtime types under {@code name}: the String
   * {@code zulu} and the Integer {@code 99}. A comparison that ignores runtime type answers over
   * both, one that respects it answers over the Integer alone.
   */
  private void seedMixedRuntimeTypes() {
    session.createVertexClass("Loose");
    graph.addVertex(T.label, "Loose", "tag", "loose_zulu", "name", "zulu");
    graph.addVertex(T.label, "Loose", "tag", "loose_num", "name", 99);
    graph.tx().commit();
  }

  /** Sorted {@code tag} values of the returned vertices — the mixed-type fixture's row identity. */
  private static List<String> sortedTags(List<?> vertices) {
    return vertices.stream()
        .map(v -> ((Vertex) v).value("tag"))
        .map(Object::toString)
        .sorted()
        .toList();
  }

  /**
   * Documentation-only / pre-existing encoding (TQ1500): a label {@code FilterRankingStrategy}
   * migrates onto the {@code dedup()} step is already part of the shape key through the per-step
   * label section that existed before Track 03. Production rarely reaches the hop-labelled
   * spelling, because the ranking strategy moves the label onto {@code dedup} first. Kept here to
   * record that encoding, not as Track 03 coverage.
   */
  @Test
  public void dedupStepLabel_isPartOfShapeKey() {
    var labelOnHop = shapeKey(dedupSelectShape(/* labelOnDedupStep= */ false));
    var labelOnDedup = shapeKey(dedupSelectShape(/* labelOnDedupStep= */ true));

    assertThat(labelOnHop)
        .as("the dedup step's own labels reach the key through the per-step label section")
        .isNotEqualTo(labelOnDedup);
  }

  /**
   * Documentation-only companion to {@link #dedupStepLabel_isPartOfShapeKey} (TQ1500): the
   * over-keying is harmless because {@code dedup} emits the traverser it received, so both label
   * positions name the same hop target and return the same row.
   */
  @Test
  public void dedupStepLabel_bothPositionsReturnTheSameRow() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    assertThat(sortedNames(applyAdmin(dedupSelectShape(false))))
        .as("the label on the hop selects the hop target")
        .containsExactly("Bob");
    assertThat(sortedNames(applyAdmin(dedupSelectShape(true))))
        .as("the migrated label names the same element the hop bound")
        .containsExactly("Bob");
  }

  /**
   * {@code g.V().out("knows").out("knows").select("mid")} with {@code as("mid")} parked on a
   * transparent {@link NoOpBarrierStep} that follows hop number {@code hop}. This is the placement
   * {@code LazyBarrierStrategy} produces for {@code out().as("mid").out()}: it inserts a barrier
   * after the hop and moves the hop's labels onto it through {@code TraversalHelper.copyLabels}. The
   * barrier is placed by hand so both shapes differ in that one position and nothing else.
   */
  private Traversal.Admin<?, ?> barrierLabelAfterHop(int hop) {
    var admin = graph.traversal().V().out("knows").out("knows").select("mid").asAdmin();
    var barrier = new NoOpBarrierStep<>(admin);
    barrier.addLabel("mid");
    // Step 0 is the GraphStep, so hop n sits at index n and its barrier goes at index n + 1.
    admin.addStep(hop + 1, barrier);
    return admin;
  }

  /**
   * {@code g.V().out("knows").as("t").dedup().select("t")} with {@code as("t")} either left on the
   * hop or moved onto the {@code dedup} step, which is what {@code FilterRankingStrategy} does to
   * this shape in production.
   */
  private Traversal.Admin<?, ?> dedupSelectShape(boolean labelOnDedupStep) {
    var admin = graph.traversal().V().out("knows").as("t").dedup().select("t").asAdmin();
    if (labelOnDedupStep) {
      // Index 1 is the hop, index 2 the DedupGlobalStep — the migration moves the label forward.
      // getSteps() is a raw Step list, and label mutation needs no type argument.
      admin.getSteps().get(1).removeLabel("t");
      admin.getSteps().get(2).addLabel("t");
    }
    return admin;
  }

  private String shapeKey(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    return shapeKey(supplier.get().asAdmin());
  }

  private String shapeKey(Traversal.Admin<?, ?> admin) {
    var extraction = GremlinStepWalker.extractShape(admin, graphSession());
    assertThat(extraction.complete()).isTrue();
    return extraction.key();
  }

  private List<?> apply(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    return applyAdmin(supplier.get().asAdmin());
  }

  /**
   * Runs {@link GremlinToMatchStrategy} over a prepared step list and drains it, pinning that the
   * shape translated. Strategies are not applied first, so a hand-placed barrier stays where the
   * test put it.
   */
  private List<?> applyAdmin(Traversal.Admin<?, ?> admin) {
    GremlinToMatchStrategy.instance().apply(admin);
    assertThat(admin.getSteps()).hasSize(1);
    assertThat(admin.getSteps().getFirst()).isInstanceOf(YTDBMatchPlanStep.class);
    return admin.toList();
  }

  private static List<String> sortedNames(List<?> vertices) {
    return vertices.stream()
        .map(v -> ((Vertex) v).value("name"))
        .map(Object::toString)
        .sorted()
        .toList();
  }

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  /** The order shape's key as extracted with the productive-order setting forced to {@code value}. */
  private String captureOrderShapeKeyWith(boolean value) {
    var config = graphSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, value);
    try {
      return shapeKey(() -> graph.traversal().V().order().by("age").values("name"));
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, previous);
    }
  }

  private String captureShapeKeyWithPolymorphic(boolean value) {
    var config = graphSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, value);
    try {
      return shapeKey(() -> graph.traversal().V().hasLabel("Person"));
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

}
