package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
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

  /**
   * {@code hasId(...)} marks the walk RID-bearing and bypasses the plan cache. A hop follows the
   * RID filter so the walk still translates: a BARE RID point-lookup ({@code V().hasId(id)} with no
   * hop) declines to native (native resolves the RID directly), so it is the RID-start-plus-hop
   * shape that exercises the cache-bypass path here.
   */
  @Test
  public void hasId_bypassesPlanCache() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var result = walk(() -> graph.traversal().V().hasId(alice.id()).out("knows"));
    assertThat(result.cacheEligible()).isFalse();

    apply(() -> graph.traversal().V().hasId(alice.id()).out("knows"));
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
    var shapeKey =
        GremlinStepWalker.extractShape(
            graph.traversal().V().has("age", P.eq(30)).asAdmin(), graphSession())
            .key();
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isTrue();
    assertThat(GremlinPlanCache.instance(graphSession()).containsTranslation(shapeKey)).isTrue();

    var before = GremlinPlanCache.getLastInvalidation(graphSession());
    GremlinPlanCache.instance(graphSession()).onSchemaUpdate(null, "test", null);
    assertThat(GremlinPlanCache.getLastInvalidation(graphSession())).isGreaterThan(before);
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isFalse();
    assertThat(GremlinPlanCache.instance(graphSession()).containsTranslation(shapeKey))
        .as("schema invalidation must clear the translation cache as well as the plan cache")
        .isFalse();
  }

  /**
   * First apply of a shape records a plan-cache miss and a translation-cache miss; the second apply
   * of the same shape hits the translation cache and never consults the plan cache. Proof the
   * production {@code apply} path skipped the walker, not silently rebuilt.
   */
  @Test
  public void secondApply_recordsCacheHit() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var hitsBefore = cache.getHits();
    var missesBefore = cache.getMisses();
    var translationHitsBefore = cache.getTranslationHits();
    var translationMissesBefore = cache.getTranslationMisses();

    apply(() -> graph.traversal().V().has("age", 30));
    assertThat(cache.getMisses()).isEqualTo(missesBefore + 1);
    assertThat(cache.getHits()).isEqualTo(hitsBefore);
    assertThat(cache.getTranslationMisses()).isEqualTo(translationMissesBefore + 1);
    assertThat(cache.getTranslationHits()).isEqualTo(translationHitsBefore);

    apply(() -> graph.traversal().V().has("age", 40));
    assertThat(cache.getTranslationHits()).isEqualTo(translationHitsBefore + 1);
    assertThat(cache.getTranslationMisses()).isEqualTo(translationMissesBefore + 1);
    assertThat(cache.getHits())
        .as("a translation-cache hit must not look up the plan cache")
        .isEqualTo(hitsBefore);
    assertThat(cache.getMisses()).isEqualTo(missesBefore + 1);
  }

  /**
   * A committed control traversal populates both caches. After a schema write in the same graph
   * session, a previously cached plan must not be served, and a new traversal must not populate
   * either shared cache or change its counters. Outside the transaction the new shape caches again.
   */
  @Test
  public void schemaTransactionSkipsExistingAndNewPlanAndTranslationEntries() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var oldFp = fingerprint(walk(() -> graph.traversal().V().has("age", 30)));
    var newFp = fingerprint(walk(() -> graph.traversal().V().has("name", "Alice")
        .has("age", 30)));
    var oldShape = GremlinStepWalker.extractShape(
        graph.traversal().V().has("age", 30).asAdmin(), session).key();
    var newShape = GremlinStepWalker.extractShape(
        graph.traversal().V().has("name", "Alice").has("age", 30).asAdmin(), session).key();
    assertThat(sortedNames(apply(() -> graph.traversal().V().has("age", 30))))
        .containsExactly("Alice");
    assertThat(cache.contains(oldFp)).as("the committed traversal must cache its plan").isTrue();
    assertThat(cache.containsTranslation(oldShape))
        .as("the committed traversal must cache its translation").isTrue();
    assertThat(cache.contains(newFp)).isFalse();
    assertThat(cache.containsTranslation(newShape)).isFalse();
    var oldTemplate = cache.peekStored(oldFp);
    var hits = cache.getHits();
    var misses = cache.getMisses();
    var translationHits = cache.getTranslationHits();
    var translationMisses = cache.getTranslationMisses();

    session.getMetadata().getSchema().createClass("TxGremlinOnly");
    assertThat(session.getTxSchemaState()).isNotNull();
    var inTx = graph.traversal().V().has("age", 30).asAdmin();
    GremlinToMatchStrategy.instance().apply(inTx);
    assertThat(inTx.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    var txStep = (YTDBMatchPlanStep<?, ?>) inTx.getStartStep();
    var txPlan = txStep.getPlan();
    assertThat(txPlan)
        .as("the tx-local traversal must own a fresh plan, not the shared template")
        .isNotSameAs(oldTemplate);
    assertThat(sortedNames(inTx.toList())).containsExactly("Alice");
    assertThat(txStep.getPlan())
        .as("a fresh tx plan must be owned by the step, not copied as a shared template on open")
        .isSameAs(txPlan);
    assertThat(sortedNames(apply(() -> graph.traversal().V().has("name", "Alice")
        .has("age", 30)))).containsExactly("Alice");
    assertThat(cache.getHits()).isEqualTo(hits);
    assertThat(cache.getMisses()).isEqualTo(misses);
    assertThat(cache.getTranslationHits()).isEqualTo(translationHits);
    assertThat(cache.getTranslationMisses()).isEqualTo(translationMisses);
    assertThat(cache.contains(newFp)).as("the tx-built plan must not leak").isFalse();
    assertThat(cache.containsTranslation(newShape))
        .as("the tx-built translation must not leak").isFalse();

    graph.tx().rollback();
    assertThat(cache.contains(newFp)).isFalse();
    assertThat(cache.containsTranslation(newShape)).isFalse();
    assertThat(sortedNames(apply(() -> graph.traversal().V().has("name", "Alice")
        .has("age", 30)))).containsExactly("Alice");
    assertThat(cache.contains(newFp)).as("outside the schema tx the plan caches again").isTrue();
    assertThat(cache.containsTranslation(newShape))
        .as("outside the schema tx the translation caches again").isTrue();
  }

  /**
   * Direct plan lookups must not return a committed template or record a hit during schema DDL.
   * Direct plan publication must not leave a new entry behind after the transaction rolls back.
   */
  @Test
  public void schemaTransactionSkipsDirectPlanReadsAndWrites() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var fp = fingerprint(walk(() -> graph.traversal().V().has("age", 30)));
    var newFp = fingerprint(walk(() -> graph.traversal().V().has("name", "Alice")));
    apply(() -> graph.traversal().V().has("age", 30));
    var stored = cache.peekStored(fp);
    assertThat(stored).as("committed control supplies a real plan template").isNotNull();
    assertThat(newFp).isNotEqualTo(fp);
    assertThat(cache.contains(newFp)).isFalse();
    var hits = cache.getHits();
    var misses = cache.getMisses();

    session.getMetadata().getSchema().createClass("TxDirectPlanOnly");
    assertThat(session.getTxSchemaState()).isNotNull();
    assertThat(GremlinPlanCache.template(fp, session)).isNull();
    var ctx = new BasicCommandContext(session);
    assertThat(GremlinPlanCache.get(fp, ctx, session)).isNull();
    GremlinPlanCache.put(newFp, stored, session);
    assertThat(cache.contains(newFp)).as("direct tx publication must be refused").isFalse();
    assertThat(cache.getHits()).isEqualTo(hits);
    assertThat(cache.getMisses()).isEqualTo(misses);
    graph.tx().rollback();
    var resumed = graphSession(); // rollback replaces the graph's active session
    assertThat(cache.contains(newFp)).isFalse();
    assertThat(GremlinPlanCache.template(fp, resumed)).isSameAs(stored);
  }

  /**
   * Direct translation reads and writes must bypass the shared map during schema DDL, including
   * decline templates that would otherwise skip the walker altogether.
   */
  @Test
  public void schemaTransactionSkipsDirectTranslationReadsAndWrites() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var shape = GremlinStepWalker.extractShape(
        graph.traversal().V().has("age", 30).asAdmin(), session).key();
    var newShape = GremlinStepWalker.extractShape(
        graph.traversal().V().has("name", "Alice").asAdmin(), session).key();
    apply(() -> graph.traversal().V().has("age", 30));
    assertThat(cache.containsTranslation(shape)).isTrue();
    assertThat(newShape).isNotEqualTo(shape);
    assertThat(cache.containsTranslation(newShape)).isFalse();
    var translationHits = cache.getTranslationHits();
    var translationMisses = cache.getTranslationMisses();

    session.getMetadata().getSchema().createClass("TxDirectTranslationOnly");
    assertThat(session.getTxSchemaState()).isNotNull();
    assertThat(GremlinPlanCache.getTranslation(shape, session)).isNull();
    GremlinPlanCache.putTranslation(newShape, GremlinTranslationTemplate.DECLINE, session);
    assertThat(cache.containsTranslation(newShape))
        .as("direct tx publication must not write a decline template")
        .isFalse();
    assertThat(cache.getTranslationHits()).isEqualTo(translationHits);
    assertThat(cache.getTranslationMisses()).isEqualTo(translationMisses);
    graph.tx().rollback();
    var resumed = graphSession(); // rollback replaces the graph's active session
    assertThat(cache.containsTranslation(newShape)).isFalse();
    assertThat(GremlinPlanCache.getTranslation(shape, resumed)).isNotNull();
  }

  /**
   * Even if the counted lookup refuses a stored template, the uncounted peek after planning must
   * not replace a tx-built plan with the stale stored instance of the same fingerprint.
   */
  @Test
  public void schemaTransactionBuildPlanDoesNotPeekStoredTemplate() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var translation = walk(() -> graph.traversal().V().has("age", 30));
    var fp = fingerprint(translation);
    apply(() -> graph.traversal().V().has("age", 30));
    var stored = cache.peekStored(fp);
    assertThat(stored).isNotNull();
    var hits = cache.getHits();
    var misses = cache.getMisses();

    session.getMetadata().getSchema().createClass("TxPeekOnly");
    var txPlan = GremlinToMatchStrategy.buildPlan(session, translation, System.nanoTime());
    assertThat(txPlan).as("the tx builder must not return the uncounted stored plan")
        .isNotSameAs(stored);
    assertThat(cache.getHits()).isEqualTo(hits);
    assertThat(cache.getMisses()).isEqualTo(misses);
    txPlan.close();
    graph.tx().rollback();
  }

  /**
   * A committed union stores each child under its own fingerprint. Compiling a union with a new
   * child during schema DDL must neither publish that child nor evict or replace the committed
   * children. The tx-local subclass test below pins that committed children are not served.
   */
  @Test
  public void schemaTransactionSkipsCachedUnionChildPlans() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var translation = walk(() -> graph.traversal().V()
        .union(__.out("knows"), __.in("knows")));
    assertThat(translation.isMultiPlan()).isTrue();
    assertThat(translation.childPlans()).hasSize(2);
    var childFingerprints = translation.childPlans().stream()
        .map(child -> GremlinPlanFingerprint.fingerprint(child.inputs(), translation.shaping()))
        .toList();
    assertThat(childFingerprints).doesNotHaveDuplicates();
    for (var fp : childFingerprints) {
      assertThat(cache.contains(fp)).isFalse();
    }
    var committed = graph.traversal().V().union(__.out("knows"), __.in("knows")).asAdmin();
    GremlinToMatchStrategy.instance().apply(committed);
    assertThat(committed.getStartStep()).isInstanceOf(MultiPlanMatchStep.class);
    assertThat(sortedNames(committed.toList())).containsExactly("Alice", "Bob");
    var misses = cache.getMisses();
    assertThat(misses).as("the committed union must cache child plans").isGreaterThan(0);
    var storedChildren = childFingerprints.stream().map(cache::peekStored).toList();
    for (var stored : storedChildren) {
      assertThat(stored)
          .as("the committed union must actually store each child, not merely look it up")
          .isNotNull();
    }
    var hits = cache.getHits();
    var translationHits = cache.getTranslationHits();
    var translationMisses = cache.getTranslationMisses();

    graphSession().getMetadata().getSchema().createClass("TxUnionOnly");
    var newTranslation = walk(() -> graph.traversal().V()
        .union(__.out("knows"), __.in("knows"), __.out("likes")));
    var newChild = newTranslation.childPlans().get(2);
    var newChildFp = GremlinPlanFingerprint.fingerprint(
        newChild.inputs(), newTranslation.shaping());
    assertThat(childFingerprints).doesNotContain(newChildFp);
    assertThat(cache.contains(newChildFp)).isFalse();
    var inTx = graph.traversal().V().union(__.out("knows"), __.in("knows"),
        __.out("likes")).asAdmin();
    GremlinToMatchStrategy.instance().apply(inTx);
    assertThat(inTx.getStartStep()).isInstanceOf(MultiPlanMatchStep.class);
    var txChildren = ((MultiPlanMatchStep<?, ?>) inTx.getStartStep()).getPlans();
    assertThat(txChildren).hasSize(3);
    assertThat(sortedNames(inTx.toList())).containsExactly("Alice", "Bob");
    for (var i = 0; i < childFingerprints.size(); i++) {
      assertThat(cache.peekStored(childFingerprints.get(i)))
          .as("tx compilation must neither evict nor replace committed child " + i)
          .isSameAs(storedChildren.get(i));
    }
    assertThat(cache.contains(newChildFp))
        .as("a new tx-only union child must not publish its plan")
        .isFalse();
    assertThat(cache.getHits()).isEqualTo(hits);
    assertThat(cache.getMisses()).isEqualTo(misses);
    assertThat(cache.getTranslationHits()).isEqualTo(translationHits);
    assertThat(cache.getTranslationMisses()).isEqualTo(translationMisses);
    graph.tx().rollback();
  }

  /**
   * A committed union caches child plans with the current polymorphic scan set. A tx-local Person
   * subclass adds a provisional collection to that set. Each fresh child must see its new vertex,
   * while a stored child served through any number of copies would miss it. Both the query result
   * and the unchanged stored templates pin the schema-transaction cache boundary.
   */
  @Test
  public void schemaTransactionUnionChildrenScanTxLocalSubclassRecords() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var translation = walk(() -> graph.traversal().V()
        .union(__.has("name", "Alice"), __.has("age", 40)));
    assertThat(translation.isMultiPlan()).isTrue();
    var childFingerprints = translation.childPlans().stream()
        .map(child -> GremlinPlanFingerprint.fingerprint(child.inputs(), translation.shaping()))
        .toList();
    assertThat(childFingerprints).hasSize(2).doesNotHaveDuplicates();
    var committed = graph.traversal().V()
        .union(__.has("name", "Alice"), __.has("age", 40)).asAdmin();
    GremlinToMatchStrategy.instance().apply(committed);
    assertThat(committed.getStartStep()).isInstanceOf(MultiPlanMatchStep.class);
    assertThat(sortedNames(committed.toList())).containsExactly("Alice", "Bob");
    var storedChildren = childFingerprints.stream().map(cache::peekStored).toList();
    assertThat(storedChildren).doesNotContainNull();
    var hits = cache.getHits();
    var misses = cache.getMisses();

    var session = graphSession();
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxUnionPerson", schema.getClass("Person"));
    assertThat(session.getTxSchemaState()).isNotNull();
    // graph.addVertex would resolve the label through a separate session, which cannot see the
    // tx-local class. Create the vertex through this session's transaction instead.
    var txVertex = session.getActiveTransaction().newVertex("TxUnionPerson");
    txVertex.setProperty("name", "Alice");
    txVertex.setProperty("age", 40);
    for (var i = 0; i < childFingerprints.size(); i++) {
      assertThat(cache.peekStored(childFingerprints.get(i)))
          .as("the stored child " + i + " must survive the tx setup, or nothing could be served")
          .isSameAs(storedChildren.get(i));
    }

    var inTx = graph.traversal().V()
        .union(__.has("name", "Alice"), __.has("age", 40)).asAdmin();
    GremlinToMatchStrategy.instance().apply(inTx);
    assertThat(inTx.getStartStep()).isInstanceOf(MultiPlanMatchStep.class);
    var rows = inTx.toList();
    var txRows = rows.stream()
        .map(Vertex.class::cast)
        .filter(v -> "Alice".equals(v.value("name")) && Integer.valueOf(40).equals(v.value("age")))
        .count();
    assertThat(txRows)
        .as("each union child must scan the tx-local subclass collection; a miss means a stored"
            + " pre-transaction child plan was served")
        .isEqualTo(2);
    assertThat(sortedNames(rows)).containsExactly("Alice", "Alice", "Alice", "Bob");
    assertThat(cache.getHits()).isEqualTo(hits);
    assertThat(cache.getMisses()).isEqualTo(misses);
    for (var i = 0; i < childFingerprints.size(); i++) {
      assertThat(cache.peekStored(childFingerprints.get(i)))
          .as("tx compilation must neither evict nor replace committed child " + i)
          .isSameAs(storedChildren.get(i));
    }
    graph.tx().rollback();
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

    var hitsBefore = cache.getTranslationHits();
    var warm = sortedNames(apply(() -> graph.traversal().V().has("age", 30)));
    assertThat(cache.getTranslationHits()).isEqualTo(hitsBefore + 1);
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
    var translationHitsBefore = cache.getTranslationHits();
    var translationMissesBefore = cache.getTranslationMisses();

    apply(() -> graph.traversal().V().count());
    apply(() -> graph.traversal().V().count());

    assertThat(cache.getHits()).isEqualTo(hitsBefore);
    assertThat(cache.getMisses()).isEqualTo(missesBefore + 2);
    assertThat(cache.getTranslationHits())
        .as("a non-cacheable count plan must not be stored as a translation template")
        .isEqualTo(translationHitsBefore);
    assertThat(cache.getTranslationMisses()).isEqualTo(translationMissesBefore + 2);
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

  /** A timeout flip invalidates once before the generation is captured, then a warm hit stays warm. */
  @Test
  public void changedTimeoutThenSameShapeTwiceHasOnePlanMiss() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();
    var config = graphSession().getConfiguration();
    var old = config.getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    var cache = GremlinPlanCache.instance(graphSession());
    try {
      config.setValue(GlobalConfiguration.COMMAND_TIMEOUT, old + 1);
      var generation = cache.getInvalidationCounter();
      var misses = cache.getMisses();
      var translationHits = cache.getTranslationHits();
      // Apply without executing: this test counts cache/planner lookups, not result projection.
      var first = graph.traversal().V().has("age", 30).asAdmin();
      GremlinToMatchStrategy.instance().apply(first);
      assertThat(first.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
      var second = graph.traversal().V().has("age", 40).asAdmin();
      GremlinToMatchStrategy.instance().apply(second);
      assertThat(second.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
      assertThat(cache.getInvalidationCounter()).isEqualTo(generation + 1);
      assertThat(cache.getMisses()).as("one cold planner lookup, not two").isEqualTo(misses + 1);
      assertThat(cache.getTranslationHits()).isEqualTo(translationHits + 1);
    } finally {
      config.setValue(GlobalConfiguration.COMMAND_TIMEOUT, old);
    }
  }

  /** A store before invalidation is cleared, while a late store removes its own stale entry. */
  @Test
  public void translationStoreBeforeAndAfterInvalidationNeverServesOldGeneration() {
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    cache.prepare(session);
    var old = cache.getInvalidationCounter();
    cache.putTranslationInternal("before", GremlinTranslationTemplate.DECLINE, session, old);
    assertThat(cache.getTranslationInternal("before", session, old)).isNotNull();
    cache.invalidate();
    assertThat(cache.containsTranslation("before")).isFalse();
    cache.putTranslationInternal("after", GremlinTranslationTemplate.DECLINE, session, old);
    assertThat(cache.containsTranslation("after")).isFalse();
    cache.putTranslationInternal("after", GremlinTranslationTemplate.DECLINE, session,
        cache.getInvalidationCounter());
    assertThat(cache.containsTranslation("after")).isTrue();
    assertThat(cache.getTranslationInternal("after", session, old))
        .as("old bindings must reject even a new live entry").isNull();
  }

  /** A real apply parked in value harvesting misses both entries installed after schema DDL. */
  @Test
  public void parkedReaderRejectsNewEntryAfterInvalidation() throws Exception {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var shape = GremlinStepWalker.extractShape(
        graph.traversal().V().has("age", 30).has("age", 30).asAdmin(), session).key();
    var fp = fingerprint(walk(() -> graph.traversal().V().has("age", 30).has("age", 30)));
    var harvesting = new CountDownLatch(1);
    var published = new CountDownLatch(1);
    var armed = new java.util.concurrent.atomic.AtomicBoolean();
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    // The first predicate binding is already harvested. Park the second predicate after
    // its value class is encoded, before either cache lookup.
    var predicate = new P<Integer>(P.<Integer>eq(30).getBiPredicate(), 30) {
      @Override
      public Integer getValue() {
        if (armed.get() && reads.incrementAndGet() == 2) {
          harvesting.countDown();
          awaitLatch(published);
        }
        return super.getValue();
      }
    };
    var reader = graph.traversal().V().has("age", 30).has("age", predicate).asAdmin();
    var failure = new AtomicReference<Throwable>();
    var worker = new Thread(() -> {
      try {
        awaitLatch(harvesting);
        // This is real committed schema DDL, not a direct call to the cache listener.
        var tx = (YTDBTransaction) graph.tx();
        tx.readWrite();
        tx.getDatabaseSession().getMetadata().getSchema().createClass("ReaderNewSchema");
        tx.commit();
        var fresh = graph.traversal().V().has("age", 40).has("age", 40).asAdmin();
        GremlinToMatchStrategy.instance().apply(fresh);
        assertThat(fresh.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
        assertThat(cache.containsTranslation(shape)).isTrue();
        assertThat(cache.contains(fp)).isTrue();
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        published.countDown();
      }
    });
    var translationMisses = cache.getTranslationMisses();
    var planMisses = cache.getMisses();
    var before = cache.getInvalidationCounter();
    worker.start();
    try {
      armed.set(true);
      GremlinToMatchStrategy.instance().apply(reader);
    } finally {
      published.countDown();
      worker.join(TimeUnit.SECONDS.toMillis(10));
    }
    assertThat(worker.isAlive()).isFalse();
    assertThat(failure.get()).isNull();
    assertThat(reads.get()).isGreaterThanOrEqualTo(2);
    assertThat(cache.getInvalidationCounter()).isGreaterThan(before);
    assertThat(reader.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    assertThat(cache.getTranslationMisses()).isEqualTo(translationMisses + 2);
    assertThat(cache.getMisses()).isEqualTo(planMisses + 2);
  }

  /** A reader rejects stale publications before their writers reach guarded removal. */
  @Test
  public void readersRejectPublishedStaleEntriesBeforeCleanup() {
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var old = cache.getInvalidationCounter();
    cache.invalidate();
    var plan = mock(InternalExecutionPlan.class);
    when(plan.canBeCached()).thenReturn(true);
    when(plan.copy(any())).thenAnswer(invocation -> mock(InternalExecutionPlan.class));
    cache.afterPlanPublication = () -> {
      assertThat(cache.contains("pending")).isTrue();
      assertThat(cache.planEntry("pending", session, old)).isNull();
    };
    cache.afterTranslationPublication = () -> {
      assertThat(cache.containsTranslation("pending")).isTrue();
      assertThat(cache.getTranslationInternal("pending", session, old)).isNull();
    };
    try {
      cache.putInternal("pending", plan, session, old);
      cache.putTranslationInternal("pending", GremlinTranslationTemplate.DECLINE, session, old);
    } finally {
      cache.afterPlanPublication = null;
      cache.afterTranslationPublication = null;
    }
    assertThat(cache.contains("pending")).isFalse();
    assertThat(cache.containsTranslation("pending")).isFalse();
  }

  /** The real strategy carries a plan hit's generation into its translation publication. */
  @Test
  public void stalePlanCopiedIntoTranslationKeepsPlanGeneration() {
    graph.addVertex(T.label, "Person", "age", 30);
    graph.tx().commit();
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var translation = walk(() -> graph.traversal().V().has("age", 30));
    var shape = GremlinStepWalker.extractShape(
        graph.traversal().V().has("age", 30).asAdmin(), session).key();
    var generation = cache.getInvalidationCounter();
    var seeded = GremlinToMatchStrategy.buildPlanEntry(session, translation, generation);
    assertThat(cache.peekStored(fingerprint(translation))).isSameAs(seeded.value);
    assertThat(cache.containsTranslation(shape)).isFalse();
    var planHits = cache.getHits();
    var reachedPublication = new java.util.concurrent.atomic.AtomicBoolean();
    var builder = new GremlinToMatchStrategy.MatchPlanBuilder() {
      @Override
      public InternalExecutionPlan buildPlan(
          com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
          GremlinToMatchTranslator.TranslationResult result, long captured) {
        return GremlinToMatchStrategy.buildPlan(db, result, captured);
      }

      @Override
      public GremlinPlanCache.Entry<InternalExecutionPlan> buildPlanEntry(
          com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
          GremlinToMatchTranslator.TranslationResult result, long captured) {
        return GremlinToMatchStrategy.buildPlanEntry(db, result, captured);
      }
    };
    var strategy = new GremlinToMatchStrategy(t -> translation, builder, true, () -> {
      reachedPublication.set(true);
      assertThat(cache.contains(fingerprint(translation)))
          .as("the plan-cache hit still exists when the strategy selects copy-on-open")
          .isTrue();
      cache.onSchemaUpdate(null, "after-plan-hit", null);
    });
    var reader = graph.traversal().V().has("age", 40).asAdmin();
    strategy.apply(reader);
    assertThat(reader.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    assertThat(reachedPublication.get()).isTrue();
    assertThat(cache.getHits()).isEqualTo(planHits + 1);
    assertThat(cache.containsTranslation(shape)).isFalse();
    assertThat(cache.getTranslationInternal(shape, session, cache.getInvalidationCounter()))
        .isNull();
  }

  /** A builder parked inside copy publishes only after invalidation and removes its old entry. */
  @Test
  public void planStoreDuringInvalidationRemovesOnlyItsOwnPublication() throws Exception {
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    cache.prepare(session);
    var captured = cache.getInvalidationCounter();
    var copying = new CountDownLatch(1);
    var invalidated = new CountDownLatch(1);
    var plan = mock(InternalExecutionPlan.class);
    when(plan.canBeCached()).thenReturn(true);
    when(plan.copy(any())).thenAnswer(invocation -> {
      copying.countDown();
      awaitLatch(invalidated);
      return mock(InternalExecutionPlan.class);
    });
    var worker = new Thread(() -> {
      awaitLatch(copying);
      cache.invalidate();
      invalidated.countDown();
    });
    worker.start();
    try {
      cache.putInternal("parked", plan, session, captured);
      assertThat(cache.contains("parked")).isFalse();
      assertThat(cache.getInvalidationCounter()).isEqualTo(captured + 1);
    } finally {
      copying.countDown();
      invalidated.countDown();
      worker.join(TimeUnit.SECONDS.toMillis(10));
    }
    assertThat(worker.isAlive()).isFalse();
    // Force a late builder to publish under the old generation, then show that a fresh
    // builder can publish this same fingerprint without being removed by that stale store.
    cache.putInternal("parked", plan, session, captured);
    assertThat(cache.contains("parked")).isFalse();
    var fresh = cache.getInvalidationCounter();
    cache.putInternal("parked", plan, session, fresh);
    assertThat(cache.contains("parked")).isTrue();
    assertThat(cache.planEntry("parked", session, captured)).isNull();
    assertThat(cache.planEntry("parked", session, fresh)).isNotNull();
  }

  /** A stale plan writer cannot remove a replacement published between its put and its check. */
  @Test
  public void stalePlanCleanupPreservesReplacement() {
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var old = cache.getInvalidationCounter();
    cache.invalidate();
    var fresh = cache.getInvalidationCounter();
    var plan = mock(InternalExecutionPlan.class);
    when(plan.canBeCached()).thenReturn(true);
    when(plan.copy(any())).thenAnswer(invocation -> mock(InternalExecutionPlan.class));
    var replacement = new AtomicReference<InternalExecutionPlan>();
    cache.afterPlanPublication = () -> {
      cache.afterPlanPublication = null;
      // A is published with the old generation. B replaces it before A's guarded removal.
      assertThat(cache.peekStored("replacement")).isNotNull();
      cache.putInternal("replacement", plan, session, fresh);
      replacement.set(cache.peekStored("replacement"));
    };
    try {
      cache.putInternal("replacement", plan, session, old);
      assertThat(cache.peekStored("replacement")).isSameAs(replacement.get());
      assertThat(cache.planEntry("replacement", session, fresh).value)
          .isSameAs(replacement.get());
    } finally {
      cache.afterPlanPublication = null;
    }
  }

  /** A Decline payload is shared, but stale cleanup compares publication holders by identity. */
  @Test
  public void staleDeclineCleanupPreservesFreshDecline() {
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var old = cache.getInvalidationCounter();
    cache.invalidate();
    var fresh = cache.getInvalidationCounter();
    cache.afterTranslationPublication = () -> {
      cache.afterTranslationPublication = null;
      assertThat(cache.containsTranslation("decline")).isTrue();
      cache.putTranslationInternal("decline", GremlinTranslationTemplate.DECLINE, session, fresh);
    };
    try {
      cache.putTranslationInternal("decline", GremlinTranslationTemplate.DECLINE, session, old);
      assertThat(cache.containsTranslation("decline")).isTrue();
      assertThat(cache.getTranslationInternal("decline", session, fresh))
          .isSameAs(GremlinTranslationTemplate.DECLINE);
    } finally {
      cache.afterTranslationPublication = null;
    }
  }

  /** A store after counter publication but before either clear is rejected as stale. */
  @Test
  public void storesDuringPausedInvalidationCannotLeaveOldEntries() throws Exception {
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var old = cache.getInvalidationCounter();
    var incremented = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var error = new AtomicReference<Throwable>();
    cache.afterCounterIncrement = () -> {
      incremented.countDown();
      awaitLatch(release);
    };
    var invalidator = new Thread(() -> {
      try {
        cache.onSchemaUpdate(null, "paused-schema-change", null);
      } catch (Throwable t) {
        error.set(t);
      }
    });
    invalidator.start();
    try {
      assertThat(incremented.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(cache.getInvalidationCounter()).isEqualTo(old + 1);
      var plan = mock(InternalExecutionPlan.class);
      when(plan.canBeCached()).thenReturn(true);
      when(plan.copy(any())).thenAnswer(invocation -> mock(InternalExecutionPlan.class));
      cache.putInternal("during", plan, session, old);
      cache.putTranslationInternal("during", GremlinTranslationTemplate.DECLINE, session, old);
      assertThat(cache.contains("during")).isFalse();
      assertThat(cache.containsTranslation("during")).isFalse();
    } finally {
      release.countDown();
      invalidator.join(TimeUnit.SECONDS.toMillis(10));
      cache.afterCounterIncrement = null;
    }
    assertThat(invalidator.isAlive()).isFalse();
    assertThat(error.get()).isNull();
    assertThat(cache.contains("during")).isFalse();
    assertThat(cache.containsTranslation("during")).isFalse();
  }

  /** Two invalidations overlap, and the delayed first clear also drops an intervening store. */
  @Test
  public void twoConcurrentInvalidationsAdvanceCounterTwice() throws Exception {
    var session = graphSession();
    var cache = GremlinPlanCache.instance(session);
    var before = cache.getInvalidationCounter();
    var plan = mock(InternalExecutionPlan.class);
    when(plan.canBeCached()).thenReturn(true);
    when(plan.copy(any())).thenAnswer(invocation -> mock(InternalExecutionPlan.class));
    var firstIncremented = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
    var error = new AtomicReference<Throwable>();
    cache.afterCounterIncrement = () -> {
      if (first.compareAndSet(true, false)) {
        firstIncremented.countDown();
        awaitLatch(releaseFirst);
      }
    };
    var a = new Thread(() -> {
      try {
        cache.invalidate();
      } catch (Throwable t) {
        error.compareAndSet(null, t);
      }
    });
    var b = new Thread(() -> {
      try {
        cache.invalidate();
      } catch (Throwable t) {
        error.compareAndSet(null, t);
      }
    });
    a.start();
    try {
      assertThat(firstIncremented.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(cache.getInvalidationCounter()).isEqualTo(before + 1);
      cache.putInternal("first", plan, session, before);
      cache.putTranslationInternal("first", GremlinTranslationTemplate.DECLINE, session,
          before);
      assertThat(cache.planEntry("first", session, before)).isNull();
      assertThat(cache.getTranslationInternal("first", session, before)).isNull();
      b.start();
      b.join(TimeUnit.SECONDS.toMillis(10));
      assertThat(b.isAlive()).isFalse();
      assertThat(cache.getInvalidationCounter()).isEqualTo(before + 2);
      cache.putInternal("second", plan, session, before + 1);
      cache.putTranslationInternal("second", GremlinTranslationTemplate.DECLINE, session,
          before + 1);
      assertThat(cache.planEntry("second", session, before + 1)).isNull();
      assertThat(cache.getTranslationInternal("second", session, before + 1)).isNull();
      // The second invalidation has cleared. A live store now precedes the first clear.
      cache.putInternal("fresh", plan, session, before + 2);
      cache.putTranslationInternal("fresh", GremlinTranslationTemplate.DECLINE, session,
          before + 2);
      assertThat(cache.contains("fresh")).isTrue();
      assertThat(cache.containsTranslation("fresh")).isTrue();
    } finally {
      releaseFirst.countDown();
      a.join(TimeUnit.SECONDS.toMillis(10));
      b.join(TimeUnit.SECONDS.toMillis(10));
      cache.afterCounterIncrement = null;
    }
    assertThat(a.isAlive()).isFalse();
    assertThat(b.isAlive()).isFalse();
    assertThat(error.get()).isNull();
    assertThat(cache.getInvalidationCounter()).isEqualTo(before + 2);
    assertThat(cache.contains("fresh")).isFalse();
    assertThat(cache.containsTranslation("fresh")).isFalse();
    assertThat(cache.planEntry("first", session, before)).isNull();
    assertThat(cache.planEntry("second", session, before + 1)).isNull();
    assertThat(cache.getTranslationInternal("first", session, before)).isNull();
    assertThat(cache.getTranslationInternal("second", session, before + 1)).isNull();
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for cache worker");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
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
    return GremlinPlanFingerprint.fingerprint(inputs, result.shaping());
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
