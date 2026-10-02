package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Row-multiset and boundary-engagement checks for detached positive existence filters. */
public class PositiveExistsTranslationTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  private void equivalent(String scenario, Recognition recognition,
      Supplier<GraphTraversal<?, ?>> traversal) {
    support.assertEquivalent(scenario, recognition, Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedIdsOrValues, traversal);
  }

  /** A fan-out of three matching edges keeps the origin once, and count excludes an isolated row. */
  @Test
  public void fanOutWhereAndShaping_keepOnce() {
    var hub = graph.addVertex(T.label, "Article", "name", "Hub", "slug", "hub");
    var other = graph.addVertex(T.label, "Article", "name", "Other", "slug", "other");
    for (int i = 0; i < 3; i++) {
      hub.addEdge("category_link",
          graph.addVertex(T.label, "Category", "uid", "u", "name", "Cat" + i));
    }
    graph.tx().commit();

    equivalent("where(out) over fan-out", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article").where(__.out("category_link")));
    equivalent("where(out.has) with three matching edges", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.out("category_link").has("uid", "u")));
    equivalent("where plus order and limit", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.out("category_link").has("uid", "u"))
            .order().by("name").limit(1));
    equivalent("where plus dedup", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.out("category_link")).dedup());
    equivalent("where plus count", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article").where(__.out("category_link")).count());
    assertThat(graph.traversal().V().hasLabel("Article").count().next()).isEqualTo(2L);
    assertThat(graph.traversal().V().hasLabel("Article").where(__.out("category_link")).count()
        .next()).isEqualTo(1L);
  }

  /**
   * A hop target with an undeclared list property must not inherit the source class's scalar
   * declaration. Both detached checks decline rather than type the target against the source.
   */
  @Test
  public void hopTargetSchemaDoesNotInheritSourceType_forExistsAndNot() {
    session.createVertexClass("Source").createProperty("tags", PropertyType.STRING);
    graph.addVertex(T.label, "Source", "name", "Zero");
    var one = graph.addVertex(T.label, "Source", "name", "One");
    one.addEdge("link", graph.addVertex(T.label, "Target", "tags", List.of("x")));
    graph.tx().commit();

    equivalent("exists target with unknown list type", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.out("link").has("tags", P.eq(List.of("x")))));
    equivalent("not target with unknown list type", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source")
            .not(__.out("link").has("tags", P.eq(List.of("x")))));
  }

  /** A child slice changes whether a hop produces any row, so neither polarity can drop it. */
  @Test
  public void childSlices_declineForExistsAndNot() {
    seedSourcesWithZeroOneTwoLinks();
    equivalent("exists skip one", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").where(__.out("link").skip(1)));
    equivalent("not skip one", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").not(__.out("link").skip(1)));
    equivalent("exists range offset", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").where(__.out("link").range(1, 2)));
    equivalent("not range offset", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").not(__.out("link").range(1, 2)));
    equivalent("not limit zero", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").not(__.out("link").limit(0)));
    equivalent("not ordered skip", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source")
            .not(__.out("link").order().by("v").skip(1)));
    equivalent("exists ordered skip", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.out("link").order().by("v").skip(1)));
    support.assertEquivalent("exists limit zero", Recognition.DECLINED, Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedIdsOrValues,
        () -> graph.traversal().V().hasLabel("Source").where(__.out("link").limit(0)));
    support.withTranslator(false, () -> assertThat(graph.traversal().V().hasLabel("Source")
        .where(__.out("link").limit(0)).toList()).isEmpty());
  }

  /** Reducing a child yields a value even for no hop, unlike the bare detached chain. */
  @Test
  public void childReducers_declineForExistsAndNot() {
    seedSourcesWithZeroOneTwoLinks();
    equivalent("exists count", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").where(__.out("link").count()));
    equivalent("exists values count", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.out("link").values("v").count()));
    equivalent("exists group", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").where(__.out("link").group()));
    equivalent("exists group count", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").where(__.out("link").groupCount()));
    support.assertEquivalent("not count", Recognition.DECLINED, Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedIdsOrValues,
        () -> graph.traversal().V().hasLabel("Source").not(__.out("link").count()));
    support.assertEquivalent("not values count", Recognition.DECLINED, Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedIdsOrValues,
        () -> graph.traversal().V().hasLabel("Source")
            .not(__.out("link").values("v").count()));
    support.assertEquivalent("not group", Recognition.DECLINED, Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedIdsOrValues,
        () -> graph.traversal().V().hasLabel("Source").not(__.out("link").group()));
    support.assertEquivalent("not group count", Recognition.DECLINED, Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedIdsOrValues,
        () -> graph.traversal().V().hasLabel("Source").not(__.out("link").groupCount()));
    support.withTranslator(false, () -> {
      assertThat(graph.traversal().V().hasLabel("Source").where(__.out("link").count())
          .count().next()).isEqualTo(3L);
      assertThat(graph.traversal().V().hasLabel("Source").not(__.out("link").count())
          .toList()).isEmpty();
      assertThat(graph.traversal().V().hasLabel("Source").not(__.out("link").groupCount())
          .toList()).isEmpty();
    });
  }

  /** An independent child source must not become a correlated hop, for either polarity. */
  @Test
  public void childGraphSource_declinesForExistsAndNot() {
    graph.addVertex(T.label, "Source", "name", "Isolated");
    var linked = graph.addVertex(T.label, "Source", "name", "Linked");
    linked.addEdge("link", graph.addVertex(T.label, "Target", "v", 1));
    graph.tx().commit();

    equivalent("exists independent V source", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").where(__.V().out("link")));
    equivalent("exists independent V(id) source", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.V(linked.id()).out("link")));
    support.assertEquivalent("not independent V source", Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY, TranslatorEquivalenceSupport::sortedIdsOrValues,
        () -> graph.traversal().V().hasLabel("Source").not(__.V().out("link")));
    support.assertEquivalent("not independent V(id) source", Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY, TranslatorEquivalenceSupport::sortedIdsOrValues,
        () -> graph.traversal().V().hasLabel("Source")
            .not(__.V(linked.id()).out("link")));
    support.withTranslator(false, () -> assertThat(graph.traversal().V().hasLabel("Source")
        .not(__.V(linked.id()).out("link")).toList()).isEmpty());
  }

  /** A projected map's key belongs to the map, not to the vertex before the projection. */
  @Test
  public void projectedChildOrder_declinesForExistsAndNot() {
    graph.addVertex(T.label, "Source", "name", "Isolated");
    var linked = graph.addVertex(T.label, "Source", "name", "Linked");
    linked.addEdge("link", graph.addVertex(T.label, "Target", "v", 1));
    graph.tx().commit();

    equivalent("exists projected map order", Recognition.DECLINED,
        () -> graph.traversal().withStrategies(StandardOrderSemanticsStrategy.instance())
            .V().hasLabel("Source")
            .where(__.out("link").project("renamed").by("v").order().by("renamed")));
    equivalent("not projected map order", Recognition.DECLINED,
        () -> graph.traversal().withStrategies(StandardOrderSemanticsStrategy.instance())
            .V().hasLabel("Source")
            .not(__.out("link").project("renamed").by("v").order().by("renamed")));
  }

  /** Payload-changing steps decline, while terminal presence and dedup preserve existence. */
  @Test
  public void otherHopChildSteps_declineWithoutAffectingPureFilters() {
    graph.addVertex(T.label, "Source", "name", "Isolated");
    var linked = graph.addVertex(T.label, "Source", "name", "Linked");
    linked.addEdge("link", graph.addVertex(T.label, "Target", "v", 1));
    graph.tx().commit();

    equivalent("hop with values", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source").where(__.out("link").values("v")));
    equivalent("hop with valueMap", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source").where(__.out("link").valueMap("v")));
    equivalent("hop with project", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.out("link").project("renamed").by("v")));
    equivalent("hop with dedup", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source").where(__.out("link").dedup()));
    equivalent("hop with order", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.out("link").order().by("v")));
    equivalent("pure filter values remains accepted", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source").where(__.values("name")));
  }

  /** The allowlist retains target presence filters and edge-filtered hops. */
  @Test
  public void allowlistedHopChildren_translate() {
    graph.addVertex(T.label, "Source", "name", "Isolated");
    var linked = graph.addVertex(T.label, "Source", "name", "Linked");
    linked.addEdge("link", graph.addVertex(T.label, "Target", "v", 1), "weight", 2);
    graph.tx().commit();

    equivalent("target hasNot", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.out("link").hasNot("missing")));
    equivalent("filtered edge", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.outE("link").has("weight", 2).inV().has("v", 1)));
    equivalent("not filtered edge", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source")
            .not(__.outE("link").has("weight", 2).inV().has("v", 1)));
  }

  private void seedSourcesWithZeroOneTwoLinks() {
    graph.addVertex(T.label, "Source", "name", "Zero");
    var one = graph.addVertex(T.label, "Source", "name", "One");
    var two = graph.addVertex(T.label, "Source", "name", "Two");
    one.addEdge("link", graph.addVertex(T.label, "Target", "v", 1));
    two.addEdge("link", graph.addVertex(T.label, "Target", "v", 1));
    two.addEdge("link", graph.addVertex(T.label, "Target", "v", 2));
    graph.tx().commit();
  }

  /** The issue's six filter orderings, including both RID-bearing shapes, engage MATCH. */
  @Test
  public void goalShapes_translate() {
    var article = graph.addVertex(T.label, "Article", "name", "Post", "slug", "s");
    var category = graph.addVertex(T.label, "Category", "uid", "u");
    article.addEdge("category_link", category);
    var contact = graph.addVertex(T.label, "JPEmailContact", "email", "e", "verified", true);
    contact.addEdge("user_link", category);
    graph.tx().commit();
    var rid = category.id();

    equivalent("and(has(slug), where(out.has))", Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.has("slug", "s"),
            __.where(__.out("category_link").has("uid", "u")))
            .hasLabel("Article").limit(1));
    equivalent("where before label", Recognition.RECOGNIZED,
        () -> graph.traversal().V().where(__.out("category_link").has("uid", "u"))
            .hasLabel("Article").limit(1));
    equivalent("where after label", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.out("category_link").has("uid", "u")).limit(1));
    equivalent("bare where after slug", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article").has("slug", "s")
            .where(__.out("category_link")));
    equivalent("where hasId", Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("slug", "s")
            .where(__.out("category_link").hasId(rid)).hasLabel("Article"));
    equivalent("Hub nested where", Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.where(__.out("user_link").hasId(rid)),
            __.has("email", "e"), __.has("verified", true)).hasLabel("JPEmailContact"));
  }

  /** Two arms reach different targets and preserve the multiplicity of earlier positive hops. */
  @Test
  public void twoArmsAndEarlierHop_preserveRowMultiplicity() {
    var first = graph.addVertex(T.label, "Source", "name", "First");
    var second = graph.addVertex(T.label, "Source", "name", "Second");
    var hub = graph.addVertex(T.label, "Article", "name", "Hub");
    first.addEdge("entry", hub);
    second.addEdge("entry", hub);
    for (int i = 0; i < 2; i++) {
      hub.addEdge("a", graph.addVertex(T.label, "Target", "uid", "a"));
      hub.addEdge("b", graph.addVertex(T.label, "Target", "uid", "b"));
    }
    graph.tx().commit();

    equivalent("and independent hop arms", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .and(__.out("a"), __.out("b")));
    equivalent("earlier hop and independent arms", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source").out("entry")
            .and(__.out("a"), __.out("b")));
    equivalent("earlier hop and dedup", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source").out("entry")
            .where(__.out("a")).dedup());
    equivalent("filter hop", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article").filter(__.out("a")));
  }

  /** OR and NOT cannot forward the child's conjunctive check to the whole plan. */
  @Test
  public void disjunctionAndNegationOfExists_decline() {
    var origin = graph.addVertex(T.label, "Article", "name", "A");
    origin.addEdge("a", graph.addVertex(T.label, "Target", "name", "B"));
    graph.tx().commit();
    equivalent("or with exists child", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .or(__.where(__.out("a")), __.has("name", "A")));
    equivalent("not with exists child", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .not(__.where(__.out("b"))));
  }

  /** A later positive hop and a following union remain on the native engine. */
  @Test
  public void laterHopAndUnion_decline() {
    var source = graph.addVertex(T.label, "Article", "name", "A");
    var target = graph.addVertex(T.label, "Target", "name", "B");
    source.addEdge("a", target);
    graph.tx().commit();
    equivalent("later hop", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.out("a")).out("a"));
    equivalent("union after exists", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.out("a")).union(__.identity(), __.identity()));
  }

  /** A disconnected or branching capture cannot be flattened into a single chain. */
  @Test
  public void detachedExists_guardsBranchingAndDisconnectedFragments() {
    var ctx = new WalkerContext(true, false, null);
    ctx.addNode("origin", "V");
    var disconnected = new SubTraversalPredicateAdapter(ctx, Map.of());
    disconnected.addEdge("origin", "child", MatchPatternBuilder.Direction.OUT,
        new String[] {"a"});
    disconnected.addNode("child", "V");
    disconnected.addEdge("other", "lost", MatchPatternBuilder.Direction.OUT,
        new String[] {"b"});
    assertThat(ConnectiveStepSupport.detachedExists(ctx, disconnected)).isNull();

    var branching = new SubTraversalPredicateAdapter(ctx, Map.of());
    branching.addEdge("origin", "first", MatchPatternBuilder.Direction.OUT,
        new String[] {"a"});
    branching.addEdge("origin", "second", MatchPatternBuilder.Direction.OUT,
        new String[] {"b"});
    assertThat(ConnectiveStepSupport.detachedExists(ctx, branching)).isNull();

    var missingOrigin = new SubTraversalPredicateAdapter(ctx, Map.of());
    missingOrigin.addEdge("missing", "child", MatchPatternBuilder.Direction.OUT,
        new String[] {"a"});
    assertThat(ConnectiveStepSupport.detachedExists(ctx, missingOrigin)).isNull();
  }

  /** A constrained origin or nested detached check is not a valid detached chain. */
  @Test
  public void unsupportedChildShapes_decline() {
    var source = graph.addVertex(T.label, "Article", "name", "A");
    source.addEdge("a", graph.addVertex(T.label, "Target", "name", "B"));
    graph.tx().commit();
    equivalent("origin filter", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.has("name", "A").out("a")));
    equivalent("origin class", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.hasLabel("Article").out("a")));
    equivalent("nested not check", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.out("a").not(__.out("b"))));
    var second = graph.addVertex(T.label, "Article", "name", "Other");
    var middle = graph.addVertex(T.label, "Target", "name", "Middle");
    second.addEdge("a", middle);
    middle.addEdge("b", graph.addVertex(T.label, "Target", "name", "End"));
    graph.tx().commit();
    equivalent("nested exists check", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.out("a").where(__.out("b"))));
  }
}
