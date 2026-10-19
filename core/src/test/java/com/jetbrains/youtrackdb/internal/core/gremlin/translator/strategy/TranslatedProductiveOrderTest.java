package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.GremlinValueComparator;
import org.junit.Test;

/**
 * Covers standard order semantics on the translated path.
 * The shipped default omits the order-key {@code IS DEFINED} conjunct.
 * A record without the ordered property then survives and sorts as a null key.
 * Standard order semantics emit the conjunct and remove the record.
 *
 * <p><b>Rows are named, not compared arm to arm alone.</b> Arm-to-arm equality cannot detect a
 * change that moves both arms, and this change moves both. Each case therefore pins the absolute
 * row set and the absolute ordering FIRST, and only then pins that the translated arm and the
 * native arm agree. Null placement is the one value read back from the dialect, through an
 * equivalent YQL {@code ORDER BY} over the same fixture, because the sibling placement work makes
 * placement configurable.
 *
 * <p>The suite writes the translator switch and the order setting on the session's storage-scoped
 * {@code ContextConfiguration} and restores both, so it never touches process-wide
 * {@link GlobalConfiguration} state.
 */
public class TranslatedProductiveOrderTest extends GraphBaseTest {

  /**
   * Two people carrying {@code age} and exactly ONE without it. One ageless record is deliberate:
   * two would tie on the null key and no absolute ordering could be pinned.
   */
  private void seedAgedAndAgeless() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.tx().commit();
  }

  /**
   * The translated plan keeps the ageless record under the shipped default and places it exactly
   * where YQL {@code ORDER BY age} places a null key. Before this change the plan carried an
   * {@code age IS DEFINED} conjunct and the record never reached the sort.
   */
  @Test
  public void translatedOrderByMissingKey_underDefault_keepsRecordAndPlacesItAsYqlDoes() {
    seedAgedAndAgeless();

    var names = withOrderIncludesMissingKey(true, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person").order().by("age").values("name")));

    assertThat(names)
        .as("the translated plan orders the ageless record as YQL orders a null key")
        .isEqualTo(yqlOrderedNames("age"));
    assertThat(names)
        .as("no record is dropped and the aged records keep ascending order")
        .containsExactlyInAnyOrder("Alice", "Bob", "Nobody")
        .containsSubsequence("Bob", "Alice");
  }

  /**
   * The descending spelling agrees with YQL too, which moves null placement with the direction.
   */
  @Test
  public void translatedOrderByMissingKeyDescending_underDefault_placesNullAsYqlDoes() {
    seedAgedAndAgeless();

    var names = withOrderIncludesMissingKey(true, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person")
            .order().by("age", org.apache.tinkerpop.gremlin.process.traversal.Order.desc)
            .values("name")));

    assertThat(names)
        .as("the translated descending plan agrees with YQL ORDER BY age DESC")
        .isEqualTo(yqlOrderedNames("age DESC"));
  }

  /**
   * Under the standard order semantics mode the conjunct comes back and the translated plan drops the ageless
   * record, which is the pre-change contract expressed as absolute rows.
   */
  @Test
  public void translatedOrderByMissingKey_underStandardOrderSemantics_dropsRecord() {
    seedAgedAndAgeless();

    var names = withOrderIncludesMissingKey(false, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person").order().by("age").values("name")));

    assertThat(names)
        .as("the opt-out restores the order-key IS DEFINED conjunct, so the ageless record drops")
        .containsExactly("Bob", "Alice");
  }

  /**
   * The kept record reaches a following {@code count()}, which reads the pattern rather than the
   * projected stream: three under the default, two under the opt-out.
   */
  @Test
  public void translatedCountAfterOrderByMissingKey_countsPerSetting() {
    seedAgedAndAgeless();

    var underDefault = withOrderIncludesMissingKey(true, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person").order().by("age").count()));
    var underOptOut = withOrderIncludesMissingKey(false, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person").order().by("age").count()));

    assertThat(underDefault).as("the default counts every record").containsExactly("3");
    assertThat(underOptOut).as("the opt-out counts the key bearers only").containsExactly("2");
  }

  /**
   * The translated arm and the native arm return the same rows in the same order under BOTH
   * settings. Absolute values are pinned first so the agreement cannot be satisfied by two arms
   * that moved together in the wrong direction.
   */
  @Test
  public void bothArmsAgree_underBothSettings() {
    seedAgedAndAgeless();

    Supplier<GraphTraversal<?, ?>> shape =
        () -> graph.traversal().V().hasLabel("Person").order().by("age").values("name");

    withOrderIncludesMissingKey(true, () -> {
      var translated = namesFromArm(true, shape);
      var native0 = namesFromArm(false, shape);
      assertThat(translated).isEqualTo(yqlOrderedNames("age"));
      assertThat(native0)
          .as("under the default both arms keep the ageless record in the same place")
          .isEqualTo(translated);
      return null;
    });

    withOrderIncludesMissingKey(false, () -> {
      var translated = namesFromArm(true, shape);
      var native0 = namesFromArm(false, shape);
      assertThat(translated).containsExactly("Bob", "Alice");
      assertThat(native0)
          .as("under the opt-out both arms drop the ageless record")
          .isEqualTo(translated);
      return null;
    });
  }

  /**
   * The per-traversal override reaches the translated arm as well: one traversal opts out and its
   * plan carries the conjunct again, while the deployment-wide setting stays on.
   */
  @Test
  public void perTraversalOptOut_reachesTranslatedArm() {
    seedAgedAndAgeless();

    var names = withOrderIncludesMissingKey(true, () -> namesFromArm(true,
        () -> graph.traversal()
            .with(YTDBQueryConfigParam.orderIncludesMissingKey, false)
            .V().hasLabel("Person").order().by("age").values("name")));

    assertThat(names)
        .as("the option is read before the session default on the translated path too")
        .containsExactly("Bob", "Alice");
  }

  /**
   * A hop whose order key carries a default (null-keeping) unique index still emits the target
   * that lacks the key, sorted as a null key. The translator no longer plants {@code id IS
   * DEFINED}, so the planner may root an index-ordered scan; that scan must not drop the record.
   */
  @Test
  public void translatedOrderByIndexedHopTarget_underDefault_keepsTheRecordLackingTheKey() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING)
        .createIndex(INDEX_TYPE.UNIQUE);
    var ann = graph.addVertex(T.label, "Person", "id", "a", "name", "Ann");
    var bea = graph.addVertex(T.label, "Person", "id", "b", "name", "Bea");
    var nemo = graph.addVertex(T.label, "Person", "name", "Nemo");
    ann.addEdge("knows", bea);
    ann.addEdge("knows", nemo);
    graph.tx().commit();

    var names = withOrderIncludesMissingKey(true, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person").as("src").out("knows").as("dst")
            .hasLabel("Person").order().by("id").values("name")));

    assertThat(names)
        .as("the index-ordered scan still emits the key-less target, sorted as a null key")
        .startsWith("Nemo")
        .contains("Bea");
  }

  /** A user strategy selects standard order semantics on the translated path. */
  @Test
  public void translatedUserStrategy_selectsStandardOrderSemantics() {
    seedAgedAndAgeless();

    var names = namesFromArm(true, () -> graph.traversal()
        .withStrategies(StandardOrderSemanticsStrategy.instance())
        .V().hasLabel("Person").order().by("age").values("name"));

    assertThat(names).containsExactly("Bob", "Alice");
  }

  /** Contradictory explicit instructions remain visible through translator fallback handling. */
  @Test
  public void translatedContradictoryInstructions_raiseDedicatedError() {
    seedAgedAndAgeless();

    assertThatThrownBy(() -> namesFromArm(true, () -> graph.traversal()
        .with(YTDBQueryConfigParam.orderIncludesMissingKey, true)
        .withStrategies(StandardOrderSemanticsStrategy.instance())
        .V().hasLabel("Person").order().by("age").values("name")))
        .isInstanceOf(ContradictoryOrderSemanticsException.class)
        .hasMessageContaining("withoutStrategies(StandardOrderSemanticsStrategy.class)");
  }

  /** Loaded vertices precede loaded edges in full and bounded single-plan and union sorts. */
  @Test
  public void loadedGraphLinksMatchNativeTypeOrder() {
    var rows = seedLinkSortRows();
    var edge = graph.traversal().E().hasLabel("sortLink").next();
    rows.get(0).property("key", rows.get(0));
    rows.get(1).property("key", edge);
    assertThat(rawKey(rows.get(0))).isInstanceOf(Entity.class);
    assertThat(rawKey(rows.get(1))).isInstanceOf(Entity.class);
    assertLinkSortOrders("one", "two");
  }

  /** Lazy reads expose bare RIDs. Both vertex/edge priority and vertex/list priority must match. */
  @Test
  public void unloadedGraphLinksMatchNativeTypeOrder() {
    var rows = seedLinkSortRows();
    var edge = graph.traversal().E().hasLabel("sortLink").next();
    rows.get(0).property("key", rows.get(0));
    rows.get(1).property("key", edge);
    graph.tx().commit();
    graph.tx().readWrite();
    for (var row : rows) {
      ((EntityImpl) ((YTDBElementImpl) row).getRawEntity()).setLazyLoad(false);
      assertThat(rawKey(row)).isInstanceOf(RecordId.class);
    }
    assertLinkSortOrders("one", "two");
    rows.get(1).property("key", List.of(1));
    assertThat(rawKey(rows.get(0))).isInstanceOf(RID.class);
    assertLinkSortOrders("one", "two");
  }

  /** Native identifier order keeps pending ChangeableRecordId separate from committed RecordId. */
  @Test
  public void pendingAndCommittedLinkIdentitiesMatchNative() {
    var rows = seedLinkSortRows();
    var committed = rows.get(0);
    graphSession().createVertexClass("PendingLinkTarget");
    var pending = graph.addVertex(T.label, "PendingLinkTarget");
    assertThat(pending.id()).isInstanceOf(ChangeableRecordId.class);
    var committedId = ((YTDBElementImpl) committed).getRawEntity().getIdentity();
    assertThat(committedId).isInstanceOf(RecordId.class);
    assertThat(GremlinValueComparator.ORDERABILITY.compare(pending.id(), committedId))
        .isNegative();
    rows.get(0).property("key", committed);
    rows.get(1).property("key", pending);
    assertLinkSortOrders("two", "one");
  }

  /** Link lists and embedded documents keep native comparison in single-plan and union sorts. */
  @Test
  public void listsAndEmbeddedValuesKeepNativeOrder() {
    var rows = seedLinkSortRows();
    rows.get(0).property("key", List.of(rows.get(0).id()));
    rows.get(1).property("key", List.of(rows.get(1).id()));
    var listComparison = GremlinValueComparator.ORDERABILITY.compare(
        List.of(rows.get(0).id()), List.of(rows.get(1).id()));
    assertThat(listComparison).isNotZero();
    assertLinkSortOrders(listComparison < 0 ? "one" : "two",
        listComparison < 0 ? "two" : "one");
    var first = graphSession().newEmbeddedEntity();
    first.setProperty("x", 1);
    var second = graphSession().newEmbeddedEntity();
    second.setProperty("x", 2);
    ((YTDBElementImpl) rows.get(0)).getRawEntity().setProperty("key", first);
    ((YTDBElementImpl) rows.get(1)).getRawEntity().setProperty("key", second);
    var comparison = GremlinValueComparator.ORDERABILITY.compare(
        rows.get(0).property("key").value(), rows.get(1).property("key").value());
    assertThat(comparison).isNotZero();
    assertLinkSortOrders(comparison < 0 ? "one" : "two", comparison < 0 ? "two" : "one");
  }

  private List<Vertex> seedLinkSortRows() {
    var source = graph.addVertex(T.label, "LinkSortSource");
    var one = graph.addVertex(T.label, "LinkSortRow", "name", "one");
    var two = graph.addVertex(T.label, "LinkSortRow", "name", "two");
    source.addEdge("sortLink", one);
    source.addEdge("sortLink", two);
    graph.tx().commit();
    // Reload committed rows so their link identities are RecordId, not retained mutable wrappers.
    return List.of(
        graph.traversal().V().hasLabel("LinkSortRow").has("name", "one").next(),
        graph.traversal().V().hasLabel("LinkSortRow").has("name", "two").next());
  }

  private Object rawKey(Vertex row) {
    return ((YTDBElementImpl) row).getRawEntity().getProperty("key");
  }

  /** Pins exact rows as well as on/off parity and the single-plan versus multi-plan boundary. */
  private void assertLinkSortOrders(String first, String second) {
    for (var order : List.of(Order.asc, Order.desc)) {
      var expected = order == Order.asc ? List.of(first, second) : List.of(second, first);
      for (var union : List.of(false, true)) {
        for (var bounded : List.of(false, true)) {
          Supplier<GraphTraversal<?, ?>> shape = () -> {
            GraphTraversal<?, Vertex> traversal = union
                ? graph.traversal().V().hasLabel("LinkSortSource")
                    .union(__.out("sortLink"), __.out("sortLink"))
                : graph.traversal().V().hasLabel("LinkSortRow");
            traversal = traversal.order().by("key", order);
            if (bounded) {
              traversal = traversal.limit(union ? 2 : 1);
            }
            return traversal;
          };
          var all = union
              ? List.of(expected.get(0), expected.get(0), expected.get(1), expected.get(1))
              : expected;
          var wanted = bounded ? all.subList(0, union ? 2 : 1) : all;
          new TranslatorEquivalenceSupport(this::graphSession).assertEquivalent(
              "link order: " + order + ", union=" + union + ", bounded=" + bounded,
              union ? TranslatorEquivalenceSupport.Recognition.RECOGNIZED_MULTI_PLAN
                  : TranslatorEquivalenceSupport.Recognition.RECOGNIZED,
              TranslatorEquivalenceSupport.Cardinality.NON_EMPTY,
              rows -> {
                var names = rows.stream().map(row -> ((Vertex) row).<String>value("name")).toList();
                assertThat(names).isEqualTo(wanted);
                return names;
              }, shape);
        }
      }
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  /**
   * Drains one arm and pins its engagement: the translated arm must splice exactly one boundary
   * step, the native arm none. Rows are rendered through {@code String.valueOf} so a {@code count}
   * terminator and a {@code values} projection share one renderer, and ORDER IS PRESERVED.
   */
  private List<String> namesFromArm(boolean translated, Supplier<GraphTraversal<?, ?>> supplier) {
    var configuration = graphConfiguration();
    var previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    configuration.setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, translated);
    try {
      var admin = supplier.get().asAdmin();
      admin.applyStrategies();
      assertThat(countBoundarySteps(admin))
          .as(translated
              ? "the translator-on arm must engage exactly one boundary step"
              : "the translator-off arm must engage no boundary step")
          .isEqualTo(translated ? 1 : 0);
      return admin.toList().stream().map(String::valueOf).toList();
    } finally {
      configuration.setValue(
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, previous);
    }
  }

  /** Runs {@code body} with the order mode forced, then restores the previous value. */
  private <T> T withOrderIncludesMissingKey(boolean value, Supplier<T> body) {
    var configuration = graphConfiguration();
    var previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, value);
    try {
      return body.get();
    } finally {
      configuration.setValue(
          GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, previous);
    }
  }

  /**
   * The names of the seeded people in the order YQL {@code ORDER BY orderBy} returns them. Every
   * placement assertion compares against this rather than a hardcoded position.
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
    return graphSession().getConfiguration();
  }

  private DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }
}
