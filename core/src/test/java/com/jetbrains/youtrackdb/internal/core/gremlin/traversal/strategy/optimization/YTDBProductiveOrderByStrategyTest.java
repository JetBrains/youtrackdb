package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/**
 * Absolute-value pins for {@link YTDBProductiveOrderByStrategy}, the native half of the productive
 * order-by semantics: a global-scope {@code order()} step keeps a record that does not carry the
 * ordered property and orders it as YQL {@code ORDER BY} orders a null key.
 *
 * <p><b>Every assertion here names the rows it expects, rather than comparing two arms.</b> An
 * arm-to-arm comparison cannot detect a change that moves both arms, and the whole point of this
 * track is that the semantics move. Null PLACEMENT is the one thing that is not hardcoded: it is
 * read back from an equivalent YQL {@code ORDER BY} in the same fixture, because the sibling
 * null-placement work makes placement configurable and this suite must follow whatever the dialect
 * is configured to do rather than freeze one answer.
 *
 * <p>Every case runs with the Gremlin-to-MATCH translator switched OFF. A recognized shape loses
 * its {@code OrderGlobalStep} to the translated boundary, so only native execution exercises this
 * strategy. The translated half of the semantics is a separate change with its own coverage.
 *
 * <p>The suite writes the translator switch and the order setting on the session's
 * {@code ContextConfiguration}, which is storage-scoped, and restores both afterwards. It never
 * writes process-wide {@link GlobalConfiguration} state, so it needs no sequential-test category.
 */
public class YTDBProductiveOrderByStrategyTest extends GraphBaseTest {

  /**
   * Seeds the split every case needs: two people carrying {@code age} and exactly ONE without it.
   * One ageless record is deliberate — two would tie on the null key and the resulting order would
   * be unpinnable, which is what an absolute assertion must not rest on.
   */
  private void seedAgedAndAgeless() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.tx().commit();
  }

  /**
   * {@code order().by("age")} keeps Nobody, who carries no {@code age}, and places it exactly where
   * YQL {@code ORDER BY age} places a null key. Under portable TinkerPop the modulator is a filter
   * and Nobody never reaches the sort.
   */
  @Test
  public void globalOrderByMissingKey_keepsRecordAndPlacesItAsYqlDoes() {
    seedAgedAndAgeless();

    var names = nativeNames(() -> graph.traversal().V().hasLabel("Person")
        .order().by("age").values("name"));

    assertThat(names)
        .as("the ageless record is ordered as a null key, exactly as YQL ORDER BY age orders it")
        .isEqualTo(yqlOrderedNames("age"));
    assertThat(names)
        .as("no record is dropped and the two aged records keep ascending age order")
        .containsExactlyInAnyOrder("Alice", "Bob", "Nobody")
        .containsSubsequence("Bob", "Alice");
  }

  /**
   * The descending spelling keeps the same record and again agrees with YQL, which flips null
   * placement together with the direction. This is the case a comparator-level rewrite gets wrong,
   * because direction has to be read off the comparator identity.
   */
  @Test
  public void globalOrderByMissingKeyDescending_placesNullAsYqlDoes() {
    seedAgedAndAgeless();

    var names = nativeNames(() -> graph.traversal().V().hasLabel("Person")
        .order().by("age", Order.desc).values("name"));

    assertThat(names)
        .as("descending order agrees with YQL ORDER BY age DESC on null placement too")
        .isEqualTo(yqlOrderedNames("age DESC"));
    assertThat(names)
        .as("no record is dropped and the two aged records keep descending age order")
        .containsExactlyInAnyOrder("Alice", "Bob", "Nobody")
        .containsSubsequence("Alice", "Bob");
  }

  /**
   * The kept record has to reach a following {@code count()}: three records go in and three come
   * out, where the portable contract counted the two key bearers only.
   */
  @Test
  public void countAfterGlobalOrderByMissingKey_countsEveryRecord() {
    seedAgedAndAgeless();

    var count = nativeRun(() -> graph.traversal().V().hasLabel("Person")
        .order().by("age").count().next());

    assertThat(count).as("order() no longer filters, so every seeded person is counted").isEqualTo(
        3L);
  }

  /**
   * A traversal modulator, {@code by(__.values("age"))}, is made productive the same way the
   * property-key form is. It takes the other rewrite branch: a real traversal is wrapped in a
   * coalesce, while {@code by("age")} is redirected through the lambda bypass.
   */
  @Test
  public void globalOrderByTraversalModulator_keepsRecordMissingKey() {
    seedAgedAndAgeless();

    var names = nativeNames(() -> graph.traversal().V().hasLabel("Person")
        .order().by(__.values("age")).values("name"));

    assertThat(names)
        .as("a traversal modulator keeps the ageless record and places it as YQL does")
        .isEqualTo(yqlOrderedNames("age"));
  }

  /**
   * Both modulators of a two-key order are rewritten, so the ageless record survives the first key
   * and the second key breaks no ties here: {@code name} is unique in the fixture.
   */
  @Test
  public void globalOrderByTwoKeys_keepsRecordMissingTheFirstKey() {
    seedAgedAndAgeless();

    var names = nativeNames(() -> graph.traversal().V().hasLabel("Person")
        .order().by("age").by("name").values("name"));

    assertThat(names)
        .as("a multi-key order keeps the ageless record and agrees with YQL on placement")
        .isEqualTo(yqlOrderedNames("age, name"));
  }

  /**
   * {@code by(__.out("knows").count())} ends in a reducing barrier, which emits a value even for a
   * record with no such edges. The strategy must leave it alone; a coalesce around a barrier would
   * add nothing and only nest a step. All three records stay, ordered by a count of zero.
   */
  @Test
  public void globalOrderByReducingBarrierModulator_isUnchangedAndKeepsEveryRecord() {
    seedAgedAndAgeless();

    var names = nativeNames(() -> graph.traversal().V().hasLabel("Person")
        .order().by(__.out("knows").count()).values("name"));

    assertThat(names)
        .as("a barrier modulator was already productive, so every record is still ordered")
        .containsExactlyInAnyOrder("Alice", "Bob", "Nobody");
  }

  /**
   * {@code by(T.label)} is a token traversal, which holds no steps and always produces a value. The
   * strategy must skip it rather than attempt the coalesce rewrite, which would throw.
   */
  @Test
  public void globalOrderByTokenModulator_isSkippedAndKeepsEveryRecord() {
    seedAgedAndAgeless();

    var names = nativeNames(() -> graph.traversal().V().hasLabel("Person")
        .order().by(T.label).values("name"));

    assertThat(names)
        .as("a token modulator is productive already and every record is ordered")
        .containsExactlyInAnyOrder("Alice", "Bob", "Nobody");
  }

  /**
   * The per-traversal override restores portable TinkerPop filtering for ONE traversal, without
   * touching the deployment-wide setting: the ageless record is dropped and only the two key
   * bearers come back, ascending.
   */
  @Test
  public void perTraversalOptOut_dropsRecordMissingKey() {
    seedAgedAndAgeless();

    var names = nativeNames(() -> graph.traversal()
        .with(YTDBQueryConfigParam.orderIncludesMissingKey, false)
        .V().hasLabel("Person").order().by("age").values("name"));

    assertThat(names)
        .as("the per-traversal opt-out restores the portable drop")
        .containsExactly("Bob", "Alice");
  }

  /**
   * The per-traversal override also wins the other way round: with the deployment-wide setting
   * turned off, one traversal asks for the YouTrackDB semantics and gets the ageless record back.
   */
  @Test
  public void perTraversalOptIn_overridesDisabledDefault() {
    seedAgedAndAgeless();

    var configuration = graphConfiguration();
    var previous =
        configuration.getValueAsBoolean(
            GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, false);
    try {
      var names = nativeNames(() -> graph.traversal()
          .with(YTDBQueryConfigParam.orderIncludesMissingKey, true)
          .V().hasLabel("Person").order().by("age").values("name"));

      assertThat(names)
          .as("the option is read before the setting, so this traversal keeps the ageless record")
          .isEqualTo(yqlOrderedNames("age"));
    } finally {
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY,
          previous);
    }
  }

  /**
   * The deployment-wide setting turned off restores portable TinkerPop filtering for every
   * traversal. The strategy stays registered and reads the value on each compilation, so a value
   * written long after class load still takes effect.
   */
  @Test
  public void deploymentOptOut_dropsRecordMissingKey() {
    seedAgedAndAgeless();

    var configuration = graphConfiguration();
    var previous =
        configuration.getValueAsBoolean(
            GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, false);
    try {
      var names = nativeNames(() -> graph.traversal().V().hasLabel("Person")
          .order().by("age").values("name"));

      assertThat(names)
          .as("the opt-out restores the portable drop for every traversal")
          .containsExactly("Bob", "Alice");
    } finally {
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY,
          previous);
    }
  }

  /**
   * The per-traversal override reaches an order step inside a CHILD traversal. A child carries no
   * options strategy of its own during the strategy pass, so a resolver that reads the child list
   * answers null and the documented opt-out silently fails to restore portable rows.
   *
   * <p>Both directions are pinned on the same nested shape. The default keeps the ageless record
   * inside the union arm, and the opt-out drops it.
   */
  @Test
  public void perTraversalOptOut_reachesOrderInsideAChildTraversal() {
    seedAgedAndAgeless();

    var underDefault = nativeNames(() -> graph.traversal().V().hasLabel("Person")
        .union(__.order().by("age").values("name")));
    assertThat(underDefault)
        .as("the default keeps the ageless record inside a union arm")
        .containsExactlyInAnyOrder("Alice", "Bob", "Nobody");

    var underOptOut = nativeNames(() -> graph.traversal()
        .with(YTDBQueryConfigParam.orderIncludesMissingKey, false)
        .V().hasLabel("Person").union(__.order().by("age").values("name")));
    assertThat(underOptOut)
        .as("the option on the source reaches the order step inside the union arm")
        .containsExactly("Bob", "Alice");
  }

  /**
   * LOCAL-scope order is out of scope and still drops the entry missing the key. The divergence is
   * intentional: the two spellings differ by one argument, and no YQL analogue exists for ordering
   * inside a collection, where inclusion would change the size of a row rather than row
   * membership.
   */
  @Test
  public void localScopeOrderByMissingKey_stillDropsEntry() {
    seedAgedAndAgeless();

    var ages = nativeRun(() -> graph.traversal().V().hasLabel("Person")
        .values("age").fold().order(Scope.local).next());

    assertThat(ages)
        .as("local-scope order keeps its filtering behaviour")
        .containsExactly(25, 30);
  }

  /**
   * {@code select} modulators are out of scope and still drop a record missing the key. Only the
   * global order step changed.
   */
  @Test
  public void selectByMissingKey_stillDropsRecord() {
    seedAgedAndAgeless();

    var ages = nativeRun(() -> graph.traversal().V().hasLabel("Person")
        .as("a").select("a").by("age").toList());

    assertThat(ages)
        .as("select modulators keep their filtering behaviour")
        .containsExactlyInAnyOrder(25, 30);
  }

  /**
   * {@code group} modulators are out of scope: the ageless record forms no null bucket.
   */
  @Test
  public void groupByMissingKey_formsNoNullBucket() {
    seedAgedAndAgeless();

    var groups = nativeRun(() -> graph.traversal().V().hasLabel("Person")
        .groupCount().by("age").next());

    assertThat(groups.keySet())
        .as("group modulators keep their filtering behaviour, so there is no null bucket")
        .containsExactlyInAnyOrder(25, 30);
  }

  /** Drains a native-execution traversal of vertex names into a list, order preserved. */
  private List<String> nativeNames(Supplier<GraphTraversal<?, String>> traversal) {
    return nativeRun(() -> traversal.get().toList());
  }

  /**
   * Runs {@code body} with the Gremlin-to-MATCH translator switched off, restoring the previous
   * value afterwards. Native execution is the only path that reaches this strategy, and restoring
   * the previous value rather than {@code true} keeps a later case from silently running with the
   * switch stuck.
   */
  private <T> T nativeRun(Supplier<T> body) {
    var configuration = graphConfiguration();
    var previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
    try {
      return body.get();
    } finally {
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
          previous);
    }
  }

  /**
   * Reads the names of the seeded people in the order YQL {@code ORDER BY orderBy} returns them.
   * The Gremlin assertions compare against this rather than a hardcoded placement, so a change to
   * the configured null placement moves the expectation with the dialect instead of breaking the
   * suite.
   */
  private List<String> yqlOrderedNames(String orderBy) {
    session.begin();
    try (var result = session.query("SELECT name FROM Person ORDER BY " + orderBy)) {
      return result.stream().map(row -> row.<String>getProperty("name")).toList();
    } finally {
      session.commit();
    }
  }

  /** The storage-scoped configuration the graph's own traversals read. */
  private ContextConfiguration graphConfiguration() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession().getConfiguration();
  }
}
