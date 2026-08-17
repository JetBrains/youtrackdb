package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ConstantTraversal;
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
    var fp = GremlinPlanFingerprint.fingerprint(walked.inputs());
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

  private String shapeKey(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    var extraction = GremlinStepWalker.extractShape(supplier.get().asAdmin(), graphSession());
    assertThat(extraction.complete()).isTrue();
    return extraction.key();
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
}
