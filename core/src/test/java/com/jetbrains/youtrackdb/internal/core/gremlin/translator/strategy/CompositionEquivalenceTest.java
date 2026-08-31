package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Translator-on / translator-off equivalence for <em>compositions</em> of Phase-1 Gremlin shapes —
 * hops, filters, labels, connectives, projections, aggregates, order, pagination, union, dedup —
 * excluding {@code optional()}.
 *
 * <p>Sibling suites cover one surface at a time. This class pins cross-surface stacks: a shape that
 * each recogniser would accept alone must still match native when stacked, and documented
 * step-order / argument-order exceptions must keep declining (so a future walker change that
 * silently re-admits a diverging composition fails here). Every case asserts full translator-on /
 * translator-off result equality (sorted multiset, or sequence for ordered shapes) and requires a
 * non-empty native arm so empty==empty cannot pass vacuously.
 *
 * <h2>Documented composition declines (outside optional)</h2>
 *
 * <ul>
 *   <li><b>Slice before hop</b> — {@code limit}/{@code skip}/{@code range} then {@code out} declines;
 *       hop then slice translates.
 *   <li><b>Order then hop then slice</b> — {@code order().by(...).out(...).limit(n)} declines (tie cut
 *       after fan-out). Terminal {@code order().by(...).limit(n)} translates on this branch (ordered
 *       MATCH / index-ordered path).
 *   <li><b>Pre-aggregate cardinality</b> — {@code limit}/{@code skip}/{@code dedup} then
 *       {@code count}/{@code sum}/… declines.
 *   <li><b>Edge {@code as(k)}</b> — binding the edge segment declines ({@code select(k)} would emit
 *       the edge-as-node vertex alias).
 *   <li><b>{@code bothE(L).has(...).otherV()}</b> — declines (self-loop RID rewrite is wrong); directed
 *       {@code outE.has.inV} / {@code inE.has.outV} translate.
 *   <li><b>Polymorphic multi-label {@code hasLabel}</b> — {@code hasLabel(L1,L2)} under default
 *       polymorphic mode declines; non-polymorphic {@code @class IN} and multi-label hops translate.
 *   <li><b>Edge-bearing combinator child</b> — {@code and}/{@code or}/{@code where}/{@code filter}
 *       with a hop inside declines (existence would join-fan-out); pure property children translate.
 *   <li><b>Labelled {@code where(as(a)…)}</b> — scope steps unregistered → decline.
 *   <li><b>{@code where(P).by(...)}</b> — modulateBy property projection out of Phase 1.
 *   <li><b>{@code dedup().by(property)}</b> on an element boundary translates (post-projection dedup);
 *       values-then-dedup, prior-label {@code dedup(a)}, and post-union {@code dedup().by(...)} decline;
 *       bare element {@code dedup()} and {@code RETURN DISTINCT} translate.
 *   <li><b>Keyless {@code valueMap()}/{@code elementMap()}</b> — on the generic {@code V} root declines;
 *       on a typed boundary ({@code hasLabel(L)} with schema-declared properties) translates; keyed
 *       forms always translate.
 *   <li><b>Post-union hop/filter/order/positional slice</b> — declines; union+{@code count}/early
 *       {@code dedup} translate.
 *   <li><b>Bare RID point-lookup</b> — {@code g.V(id)} / {@code hasId} with no hop declines (native
 *       seek); the same id with a hop translates.
 *   <li><b>{@code Order.shuffle}</b> / second {@code order()} / order after {@code group} — decline.
 * </ul>
 */
public class CompositionEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  // ---------------------------------------------------------------------------
  // Recognized compositions — filter × hop × projection / aggregate.
  // ---------------------------------------------------------------------------

  /** Source filter + hop + target filter: marko's knows to age≥30 people. */
  @Test
  public void has_out_has_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).out(knows).has(age,gte 30)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out("knows")
            .has("age", P.gte(30)));
  }

  /** Label filter, hop, predicate filter, then values projection. */
  @Test
  public void hasLabel_out_has_values_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).out(created).has(name,lop).values(lang)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Person")
            .out("created")
            .has("name", "lop")
            .values("lang"));
  }

  /** Presence {@code hasNot} on source then hop. */
  @Test
  public void hasNot_out_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "nickname", "Al");
    graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    graph.addVertex(T.label, "Person", "name", "Alice2").addEdge("knows", carol);
    graph.tx().commit();
    // Bob has no nickname and no out-edge; Alice2 has no nickname and one out.
    assertEquivalent(
        "g.V().hasNot(nickname).out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasNot("nickname").out("knows"));
  }

  /** Text predicate + hop + within on the far side. */
  @Test
  public void textHas_out_within_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,containing(ar)).out(created).has(lang,within(java))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", TextP.containing("ar"))
            .out("created")
            .has("lang", P.within("java")));
  }

  /** Between on age, then both-hop, then dedup. */
  @Test
  public void between_both_dedup_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(age,between(27,32)).both(knows).dedup()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("age", P.between(27, 32))
            .both("knows")
            .dedup());
  }

  /**
   * Edge filter + far-side {@code hasLabel}. Unpinned {@code g.V()} lets the planner root at the
   * labelled target and reverse-walk the edge-as-node chain; edge weight must still apply via
   * {@code aliasFilters} on the edge alias (merged in {@code GremlinStepWalker.buildResult}).
   */
  @Test
  public void outE_has_inV_hasLabel_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().outE(knows).has(weight,gte 1.0).inV().hasLabel(Person)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .outE("knows")
            .has("weight", P.gte(1.0d))
            .inV()
            .hasLabel("Person"));
  }

  /** Folded adjacent edge filter without a trailing class filter. */
  @Test
  public void outE_has_inV_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().outE(knows).has(weight,gte 1.0).inV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .outE("knows")
            .has("weight", P.gte(1.0d))
            .inV());
  }

  /** Two-hop path with an as-label and select of the mid vertex. */
  @Test
  public void out_as_out_select_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).out(knows).as(friend).out(created).select(friend)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out("knows").as("friend")
            .out("created")
            .select("friend"));
  }

  /** as + select with by-modulator after a filtered hop. */
  @Test
  public void as_out_has_selectBy_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).as(p).out(created).has(name,lop).select(p).by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Person").as("p")
            .out("created")
            .has("name", "lop")
            .select("p").by("name"));
  }

  /** Pure-property and() before a hop. */
  @Test
  public void andHas_out_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().and(has(age,gte 30), hasLabel(Person)).out(created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .and(__.has("age", P.gte(30)), __.hasLabel("Person"))
            .out("created"));
  }

  /** Pure-property or() then values. */
  @Test
  public void orHas_values_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().or(has(name,marko), has(name,josh)).values(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .or(__.has("name", "marko"), __.has("name", "josh"))
            .values("age"));
  }

  /** not(has) then hop. */
  @Test
  public void notHas_out_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().not(has(age,lt 30)).out(created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .not(__.has("age", P.lt(30)))
            .out("created"));
  }

  /** where(P) comparing step labels after a hop (no by-modulator). */
  @Test
  public void as_out_whereNeqLabel_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().as(a).out(knows).where(neq(a))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("a").out("knows").where(P.neq("a")));
  }

  /** {@code where(P).by(...)} property projection declines (modulateBy out of Phase 1). */
  @Test
  public void wherePredicate_withBy_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().as(a).where(eq(a)).by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V().as("a").where(P.eq("a")).by("name"));
  }

  /** where(has…) property child then hop. */
  @Test
  public void whereHas_out_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().where(has(age,gte 30)).out(created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .where(__.has("age", P.gte(30)))
            .out("created"));
  }

  /**
   * {@code out(L).count()} translates: {@code AdjacentToIncidentStrategy} rewrites the hop to an
   * edge-returning step, and the router claims a folded vertex hop so {@code count(*)} matches native
   * neighbour cardinality.
   */
  @Test
  public void out_count_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").count());
  }

  /** Source filter + hop + count — same AdjacentToIncident rewrite path as bare hop+count. */
  @Test
  public void has_out_count_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).out(knows).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "marko").out("knows").count());
  }

  /** Pinned start + hop + count. */
  @Test
  public void V_id_out_count_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(marko).out(knows).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.marko().id()).out("knows").count());
  }

  /** hasLabel + count (class-size short-circuit path). */
  @Test
  public void hasLabel_count_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").count());
  }

  /** Filtered scan + hop + sum of a numeric property. */
  @Test
  public void has_out_values_sum_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).out(knows).values(age).sum()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Person")
            .out("knows")
            .values("age")
            .sum());
  }

  /** groupCount after a filtered hop. */
  @Test
  public void has_out_groupCount_by_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).out(created).groupCount().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out("created")
            .groupCount().by("name"));
  }

  /** project after as-labels on a two-hop path. */
  @Test
  public void as_out_as_project_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).as(a).out(knows).as(b).project(a,b).by(name).by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko").as("a")
            .out("knows").as("b")
            .project("a", "b").by("name").by("age"));
  }

  /** valueMap(keys) after hop + has. */
  @Test
  public void out_has_valueMap_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(created).has(lang,java).valueMap(name,lang)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .out("created")
            .has("lang", "java")
            .valueMap("name", "lang"));
  }

  /** elementMap(keys) after hasLabel. */
  @Test
  public void hasLabel_elementMap_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Software).elementMap(name,lang)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Software")
            .elementMap("name", "lang"));
  }

  /** Order by property then values (no slice) — stable multiset of values. */
  @Test
  public void hasLabel_order_values_matchesNativeOrdered() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "g.V().hasLabel(Person).order().by(age).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Person")
            .order().by("age", Order.asc)
            .values("name"));
  }

  /** Multi-key order on the same alias after a filter. */
  @Test
  public void has_orderByAgeThenName_matchesNativeOrdered() {
    seedTiedAges();
    assertEquivalentOrdered(
        "g.V().hasLabel(Person).order().by(age).by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Person")
            .order().by("age", Order.asc).by("name", Order.asc));
  }

  /** Hop then limit (translates); result multiset vs native. */
  @Test
  public void out_limit_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(created).limit(2)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("created").limit(2));
  }

  /** Hop then skip+limit via range. */
  @Test
  public void out_range_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(created).range(1,3)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("created").range(1, 3));
  }

  /** values then limit (IS DEFINED promote path). */
  @Test
  public void has_values_limit_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).values(age).limit(2)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").values("age").limit(2));
  }

  /** Union of two filtered arms then count. */
  @Test
  public void union_filteredArms_count_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().union(has(name,marko).out(knows), has(name,josh).out(created)).count()",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V()
            .union(
                __.has("name", "marko").out("knows"),
                __.has("name", "josh").out("created"))
            .count());
  }

  /** Union of hops then dedup. */
  @Test
  public void union_out_dedup_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().union(out(knows), out(created)).dedup()",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V()
            .union(__.out("knows"), __.out("created"))
            .dedup());
  }

  /** inE.has.outV stacked with a source pin and count. */
  @Test
  public void V_id_inE_has_outV_count_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(lop).inE(created).has(weight,lt 0.5).outV().count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.lop().id())
            .inE("created")
            .has("weight", P.lt(0.5d))
            .outV()
            .count());
  }

  /** select two labels after a filtered two-hop. */
  @Test
  public void as_out_as_selectTwo_by_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "…as(a).out(knows).as(b).select(a,b).by(name).by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko").as("a")
            .out("knows").as("b")
            .select("a", "b").by("name").by("age"));
  }

  /** mean after filtered values. */
  @Test
  public void hasLabel_values_mean_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).values(age).mean()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").values("age").mean());
  }

  /** min/max after hop. */
  @Test
  public void out_values_min_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows).values(age).min()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").values("age").min());
  }

  /** hasId within a hop chain (not bare point-lookup). */
  @Test
  public void out_hasId_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows).hasId(josh)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").hasId(m.josh().id()));
  }

  /** Nested and of property filters + hasLabel + hop. */
  @Test
  public void nestedAnd_hasLabel_out_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().and(has(age,gte 29), and(hasLabel(Person), has(name,neq peter))).out(created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .and(
                __.has("age", P.gte(29)),
                __.and(__.hasLabel("Person"), __.has("name", P.neq("peter"))))
            .out("created"));
  }

  // ---------------------------------------------------------------------------
  // Step-order / argument-order exceptions — must DECLINE.
  // ---------------------------------------------------------------------------

  /** {@code limit} before hop declines; reverse spelling translates (see {@link #out_limit_matchesNative}). */
  @Test
  public void limit_then_out_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().limit(2).out(created) — slice-before-hop",
        Recognition.DECLINED,
        () -> graph.traversal().V().limit(2).out("created"));
  }

  /**
   * {@code skip} before hop declines. Seed pins three vertices on a knows chain so whichever one
   * {@code skip(1)} drops, at least one remaining source still has an out-edge — native stays
   * non-empty and on/off equality is not vacuous.
   */
  @Test
  public void skip_then_out_declines() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    bob.addEdge("knows", carol);
    graph.tx().commit();
    assertEquivalent(
        "g.V(alice,bob,carol).skip(1).out(knows) — slice-before-hop",
        Recognition.DECLINED,
        () -> graph.traversal().V(alice.id(), bob.id(), carol.id()).skip(1).out("knows"));
  }

  /**
   * Terminal {@code order().by(...).limit(n)} translates on this branch. Unique {@code name} keeps
   * the cut inside a total order so on/off rows match.
   */
  @Test
  public void order_then_limit_matchesNativeOrdered() {
    seedTiedAges();
    assertEquivalentOrdered(
        "g.V().hasLabel(Person).order().by(name).limit(2)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Person")
            .order().by("name", Order.asc)
            .limit(2));
  }

  /** Order, hop, then limit — tie cut after fan-out still declines. */
  @Test
  public void order_then_out_then_limit_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().order().by(name).out(created).limit(1)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .order().by("name", Order.asc)
            .out("created")
            .limit(1));
  }

  /** Limit then count declines (pre-aggregate cardinality). */
  @Test
  public void limit_then_count_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(created).limit(2).count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("created").limit(2).count());
  }

  /** Dedup then count declines. */
  @Test
  public void dedup_then_count_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(created).dedup().count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("created").dedup().count());
  }

  /** Edge-bearing and() child declines. */
  @Test
  public void and_out_child_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().and(out(knows), hasLabel(Person))",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .and(__.out("knows"), __.hasLabel("Person")));
  }

  /** Edge-bearing where() child declines. */
  @Test
  public void where_out_child_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().where(out(knows)).hasLabel(Person)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .where(__.out("knows"))
            .hasLabel("Person"));
  }

  /** Labelled where(as(a)…) declines (scope steps); native still returns the hop target. */
  @Test
  public void where_asScope_declines() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    alice.addEdge("knows", bob);
    graph.tx().commit();
    assertEquivalent(
        "g.V().as(a).out(knows).where(as(a).has(age,eq 30))",
        Recognition.DECLINED,
        () -> graph.traversal().V().as("a")
            .out("knows")
            .where(__.as("a").has("age", P.eq(30))));
  }

  /** Multi-label hop translates — MATCH {@code out('knows','created')} carries both labels. */
  @Test
  public void multiLabel_out_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows,created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows", "created"));
  }

  /** Multi-label {@code in(knows,created)} on the modern graph. */
  @Test
  public void multiLabel_in_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().in(knows,created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().in("knows", "created"));
  }

  /** Multi-label {@code both(knows,created)} on the modern graph. */
  @Test
  public void multiLabel_both_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().both(knows,created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().both("knows", "created"));
  }

  /** Polymorphic multi-label hasLabel declines under default polymorphic mode. */
  @Test
  public void hasLabel_multi_then_out_declines() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    session.createEdgeClass("knows");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var eve = graph.addVertex(T.label, "Employee", "name", "Eve");
    alice.addEdge("knows", eve);
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person,Employee).out(knows)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person", "Employee").out("knows"));
  }

  /**
   * Non-polymorphic multi-label {@code hasLabel(Person,Employee).out(knows)} translates: {@code @class
   * IN} mirrors native leaf-exact membership.
   */
  @Test
  public void hasLabel_multi_then_out_nonPolymorphic_matchesNative() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    session.createEdgeClass("knows");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var eve = graph.addVertex(T.label, "Employee", "name", "Eve");
    alice.addEdge("knows", eve);
    graph.tx().commit();
    withPolymorphicDefault(false, () -> assertEquivalent(
        "non-polymorphic g.V().hasLabel(Person,Employee).out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person", "Employee").out("knows")));
  }

  /** bothE.has.otherV from a pinned start declines. */
  @Test
  public void bothE_has_otherV_declines() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    bob.addEdge("knows", alice, "since", 2011);
    graph.tx().commit();
    assertEquivalent(
        "g.V(alice).bothE(knows).has(since,lt 2015).otherV()",
        Recognition.DECLINED,
        () -> graph.traversal().V(alice.id())
            .bothE("knows").has("since", P.lt(2015)).otherV());
  }

  /** Edge as() + select declines. */
  @Test
  public void outE_as_inV_selectEdge_declines() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();
    assertEquivalent(
        "g.V(alice).outE(knows).as(k).inV().select(k).by(since)",
        Recognition.DECLINED,
        () -> graph.traversal().V(alice.id())
            .outE("knows").as("k").inV()
            .select("k").by("since"));
  }

  /** Cross-alias order by select(edge) declines. */
  @Test
  public void orderBy_selectEdge_declines() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010);
    alice.addEdge("knows", carol, "since", 2012);
    graph.tx().commit();
    assertEquivalentOrdered(
        "…outE.as(k).inV.order().by(select(k).by(since))",
        Recognition.DECLINED,
        () -> graph.traversal().V(alice.id())
            .outE("knows").as("k")
            .inV()
            .order().by(__.select("k").by("since"), Order.asc));
  }

  /** dedup().by(property) dedups on modulator value while emitting elements. */
  @Test
  public void dedup_by_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(created).dedup().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("created").dedup().by("name"));
  }

  /** values then dedup declines — boundary is no longer ELEMENT. */
  @Test
  public void values_dedup_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).values(name).dedup()",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person").values("name").dedup());
  }

  /** Keyless valueMap on a typed boundary enumerates schema-declared properties. */
  @Test
  public void valueMap_keyless_onHasLabel_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).valueMap()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").valueMap());
  }

  /** Bare g.V(id) point-lookup declines; contrast with V(id).out which translates. */
  @Test
  public void bare_V_id_declines() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(marko) — bare RID point-lookup",
        Recognition.DECLINED,
        () -> graph.traversal().V(m.marko().id()));
  }

  /** Hop after union declines. */
  @Test
  public void union_then_out_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().union(has(name,marko), has(name,josh)).out(created)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .union(__.has("name", "marko"), __.has("name", "josh"))
            .out("created"));
  }

  /** Order after union declines. */
  @Test
  public void union_then_order_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().union(out(knows), out(created)).order().by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .union(__.out("knows"), __.out("created"))
            .order().by("name", Order.asc));
  }

  /** Positional limit after union (without count) declines. */
  @Test
  public void union_then_limit_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().union(out(knows), out(created)).limit(2)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .union(__.out("knows"), __.out("created"))
            .limit(2));
  }

  /** Nested not(not(hop)) declines. */
  @Test
  public void nested_not_out_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().not(not(out(knows)))",
        Recognition.DECLINED,
        () -> graph.traversal().V().not(__.not(__.out("knows"))));
  }

  /** Second order() declines. */
  @Test
  public void second_order_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().order().by(age).order().by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .order().by("age", Order.asc)
            .order().by("name", Order.asc));
  }

  /** Order.shuffle declines. */
  @Test
  public void order_shuffle_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().order().by(shuffle)",
        Recognition.DECLINED,
        () -> graph.traversal().V().order().by(Order.shuffle));
  }

  /** Foreign step inside outE…inV window declines. */
  @Test
  public void outE_dedup_inV_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().outE(knows).dedup().inV()",
        Recognition.DECLINED,
        () -> graph.traversal().V().outE("knows").dedup().inV());
  }

  /**
   * Hop after a slice declines (clause gate). Pin marko and keep both knows neighbours so
   * {@code limit(2).out(created)} still reaches josh's created edges — on/off equality is over a
   * non-empty multiset.
   */
  @Test
  public void out_limit_out_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).out(knows).limit(2).out(created)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out("knows")
            .limit(2)
            .out("created"));
  }

  /** Singleton collection {@code eq([v])} normalizes to scalar {@code eq(v)}. */
  @Test
  public void has_eqSingletonCollection_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,eq([marko]))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", P.eq(List.of("marko"))));
  }

  // ---------------------------------------------------------------------------
  // Spelling pairs — same intent, different step order (translate vs decline).
  // ---------------------------------------------------------------------------

  /**
   * Documents the hop↔slice order exception as a paired case: hop-then-limit matches native under
   * translation; limit-then-hop declines but still matches native via the off arm.
   */
  @Test
  public void hopSlice_orderPair_documentsException() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "PAIR translate: g.V().out(created).limit(3)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("created").limit(3));
    assertEquivalent(
        "PAIR decline: g.V().limit(3).out(created)",
        Recognition.DECLINED,
        () -> graph.traversal().V().limit(3).out("created"));
  }

  /** Filter placement before vs after hop — both should translate and match. */
  @Test
  public void hasPlacement_beforeAndAfterHop_bothTranslate() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "has before hop: g.V().has(name,marko).out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "marko").out("knows"));
    assertEquivalent(
        "has after hop: g.V().out(knows).has(age,gte 30)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").has("age", P.gte(30)));
  }

  /** Directed edge-filter chain translates; bothE form of the same filter declines. */
  @Test
  public void edgeFilter_directedVsBoth_documentsException() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();
    assertEquivalent(
        "directed: g.V(alice).outE(knows).has(since,gte 2010).inV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id())
            .outE("knows").has("since", P.gte(2010)).inV());
    assertEquivalent(
        "bothE: g.V(alice).bothE(knows).has(since,gte 2010).otherV()",
        Recognition.DECLINED,
        () -> graph.traversal().V(alice.id())
            .bothE("knows").has("since", P.gte(2010)).otherV());
  }

  /** Vertex as() translates; edge as() declines — same select spelling. */
  @Test
  public void asPlacement_vertexVsEdge_documentsException() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();
    assertEquivalent(
        "vertex as: g.V(alice).out(knows).as(f).select(f).by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id())
            .out("knows").as("f")
            .select("f").by("name"));
    assertEquivalent(
        "edge as: g.V(alice).outE(knows).as(k).inV().select(k).by(since)",
        Recognition.DECLINED,
        () -> graph.traversal().V(alice.id())
            .outE("knows").as("k").inV()
            .select("k").by("since"));
  }

  /** Bare V(id) declines; V(id).out translates. */
  @Test
  public void ridLookup_bareVsWithHop_documentsException() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "bare: g.V(marko)",
        Recognition.DECLINED,
        () -> graph.traversal().V(m.marko().id()));
    assertEquivalent(
        "with hop: g.V(marko).out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.marko().id()).out("knows"));
  }

  // ---------------------------------------------------------------------------
  // Fixture helpers
  // ---------------------------------------------------------------------------

  private void seedTiedAges() {
    graph.addVertex(T.label, "Person", "name", "Ann", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Ben", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Cy", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Dee", "age", 30);
    graph.tx().commit();
  }

  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, Cardinality.NON_EMPTY, traversalSupplier, false);
  }

  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, cardinality, traversalSupplier, false);
  }

  private void assertEquivalentOrdered(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, Cardinality.NON_EMPTY, traversalSupplier, true);
  }

  private void assertEquivalentInternal(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
      Supplier<GraphTraversal<?, ?>> traversalSupplier,
      boolean ordered) {
    support.assertEquivalent(
        scenario,
        expected,
        cardinality,
        results -> canonicalize(results, ordered),
        traversalSupplier);
  }

  private static List<String> canonicalize(List<?> results, boolean ordered) {
    var mapped = new ArrayList<String>(results.size());
    for (Object result : results) {
      mapped.add(canonicalizeOne(result));
    }
    if (!ordered) {
      mapped.sort(Comparator.naturalOrder());
    }
    return mapped;
  }

  private static String canonicalizeOne(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Vertex vertex) {
      return "V:" + Objects.toString(vertex.id());
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .sorted(Comparator.comparing(e -> Objects.toString(e.getKey())))
          .map(e -> canonicalizeOne(e.getKey()) + "=" + canonicalizeOne(e.getValue()))
          .collect(Collectors.joining(",", "{", "}"));
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream()
          .map(CompositionEquivalenceTest::canonicalizeOne)
          .sorted()
          .collect(Collectors.joining(",", "[", "]"));
    }
    if (value instanceof Number number) {
      if (number.doubleValue() == Math.rint(number.doubleValue())) {
        return "N:" + number.longValue();
      }
      return "N:" + number.doubleValue();
    }
    return value.getClass().getSimpleName() + ":" + value;
  }

  private void withPolymorphicDefault(boolean value, Runnable body) {
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

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }
}
