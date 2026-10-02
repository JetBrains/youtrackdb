package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.apache.tinkerpop.gremlin.structure.Column;
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
 *   <li><b>Order then hop then slice</b> — {@code order().by(...).out(...).limit(n)} translates via
 *       ordered-expand list-shaping (VertexStep neighbour order + positional cut). Terminal
 *       {@code order().by(...).limit(n)} translates on the statement-{@code LIMIT} path.
 *   <li><b>Pre-aggregate cardinality</b> — {@code limit}/{@code skip}/{@code dedup} then
 *       {@code count}/{@code sum}/… declines.
 *   <li><b>{@code bothE(L).has(...).otherV()}</b> — declines (self-loop RID rewrite is wrong); directed
 *       {@code outE.has.inV} / {@code inE.has.outV} translate. Edge {@code as(k)} + {@code select(k)}
 *       or bare multi-label {@code select(e, v)} emit an {@code Edge} for the edge alias
 *       ({@code ResultShaping.edgeMapKeys}).
 *   <li><b>Edge-bearing combinator child</b> — {@code and}/{@code or}/{@code where}/{@code filter}
 *       with a hop inside declines (existence would join-fan-out); pure property children translate.
 *   <li><b>Labelled {@code where(as(a)…)}</b> — scope steps unregistered → decline.
 *   <li><b>{@code where(P).by(...)}</b> — modulateBy property projection out of Phase 1.
 *   <li><b>{@code dedup().by(property)}</b> / prior-label {@code dedup(a)} — decline (first-wins
 *       survivor is MATCH-order-dependent vs native); {@code values(k).dedup()}, bare element
 *       {@code dedup()}, and boundary-named {@code dedup(v)} translate.
 *   <li><b>Keyless {@code valueMap()}/{@code elementMap()}</b> — declines (schema keys alone
 *       under-project vs native schemaless enumeration); keyed forms translate.
 *   <li><b>Post-union hop/filter/positional slice</b> — declines; union+{@code count}/early
 *       {@code dedup}/{@code order} translate. {@code union(...).order().by(k).limit(n)} still
 *       declines (slice after post-concat sort is not yet on the allow path).
 *   <li><b>{@code Order.shuffle}</b> / second {@code order()} / order after {@code group} — decline.
 *   <li><b>Nested {@code by(__.order().by(Column.values|keys))}</b> after {@code groupCount().unfold()}
 *       — declines; direct {@code by(Column.values)} / {@code by(Column.keys)} translate.
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

  /** Order, hop, then limit — ordered-expand list-shaping translates and matches native. */
  @Test
  public void order_then_out_then_limit_matchesNativeOrdered() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "g.V().order().by(name).out(created).limit(1)",
        Recognition.RECOGNIZED,
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

  /**
   * Multi-label hop translates. A decoy third edge label must not appear — otherwise dropping both
   * labels would still match {@code out()} on this fixture.
   */
  @Test
  public void multiLabel_out_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    var marko = graph.traversal().V().has("name", "marko").next();
    var decoy = graph.addVertex(T.label, "Person", "name", "Decoy");
    marko.addEdge("hates", decoy);
    graph.tx().commit();
    assertEquivalent(
        "g.V().out(knows,created) with hates decoy",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows", "created"));
  }

  /**
   * Multi-label hop closing a {@code where(eq(label))} cycle must not under-match: MATCH back-ref
   * semi-join / EdgeRidLookup key a single LinkBag from the first edge label only, so they skip
   * multi-label hops and leave the generic path. Without that opt-out,
   * {@code out(knows,created)} would keep only {@code knows} and drop the {@code created} cycle.
   */
  @Test
  public void multiLabel_out_whereEqLabel_cycle_matchesNative() {
    session.createVertexClass("Person");
    session.createEdgeClass("knows");
    session.createEdgeClass("created");
    var a = graph.addVertex(T.label, "Person", "name", "a");
    var b = graph.addVertex(T.label, "Person", "name", "b");
    var c = graph.addVertex(T.label, "Person", "name", "c");
    a.addEdge("knows", b);
    b.addEdge("created", a);
    b.addEdge("knows", c);
    c.addEdge("knows", a);
    graph.tx().commit();

    assertEquivalent(
        "g.V().hasLabel(Person).as(x).out(knows).out(knows,created).where(eq(x)).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").as("x")
            .out("knows")
            .out("knows", "created")
            .where(P.eq("x"))
            .values("name"));
    // Same hop with labels swapped — still on/off equal; pin must not depend on first-label luck.
    assertEquivalent(
        "g.V().hasLabel(Person).as(x).out(knows).out(created,knows).where(eq(x)).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").as("x")
            .out("knows")
            .out("created", "knows")
            .where(P.eq("x"))
            .values("name"));
    assertThat(
        graph.traversal().V().hasLabel("Person").as("x")
            .out("knows")
            .out("knows", "created")
            .where(P.eq("x"))
            .values("name")
            .toList())
        .as("created closes a→b→a; first-label-only semi-join would return empty")
        .containsExactly("a");
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

  /** Root multi-label hasLabel re-types to LCA + {@code @class IN}; on/off match. */
  @Test
  public void hasLabel_multi_then_out_matches() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    session.createEdgeClass("knows");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var eve = graph.addVertex(T.label, "Employee", "name", "Eve");
    alice.addEdge("knows", eve);
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person,Employee).out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person", "Employee").out("knows"));
  }

  /** Non-polymorphic root multi-label hasLabel also translates via LCA + exact {@code @class IN}. */
  @Test
  public void hasLabel_multi_then_out_nonPolymorphic_matches() {
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

  /** Edge as() + select translates with edge property projection. */
  @Test
  public void outE_as_inV_selectEdge_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();
    assertEquivalent(
        "g.V(alice).outE(knows).as(k).inV().select(k).by(since)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id())
            .outE("knows").as("k").inV()
            .select("k").by("since"));
  }

  /**
   * TinkerPop DedupTest compliance shape: select edge, order by weight, re-select vertex,
   * values+dedup. Repinning select("v") to the vertex alias is required so values("name") reads
   * the target vertex rather than the prior edge boundary.
   */
  @Test
  public void outE_selectEdge_order_selectVertex_values_dedup_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().outE().as(e).inV().as(v).select(e).order().by(weight).select(v).values(name).dedup()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .outE().as("e")
            .inV().as("v")
            .select("e").order().by("weight", Order.asc)
            .select("v").values("name")
            .dedup());
  }

  /** Cross-alias order by select(edge) modulator. */
  @Test
  public void orderBy_selectEdge_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010);
    alice.addEdge("knows", carol, "since", 2012);
    graph.tx().commit();
    assertEquivalentOrdered(
        "…outE.as(k).inV.order().by(select(k).by(since))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id())
            .outE("knows").as("k")
            .inV()
            .order().by(__.select("k").by("since"), Order.asc));
  }

  /** dedup().by(property) declines — survivor identity is MATCH-order-dependent vs native. */
  @Test
  public void dedup_by_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(created).dedup().by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("created").dedup().by("name"));
  }

  /** values then dedup collapses duplicate scalars after projection. */
  @Test
  public void values_dedup_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).values(name).dedup()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").values("name").dedup());
  }

  /** Prior-label dedup(a) declines — boundary survivor is MATCH-order-dependent vs native. */
  @Test
  public void priorLabel_dedup_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().as(a).out(knows).dedup(a)",
        Recognition.DECLINED,
        () -> graph.traversal().V().as("a").out("knows").dedup("a"));
  }

  /** Post-union dedup().by(prop) declines for the same order-dependent reason. */
  @Test
  public void union_dedup_by_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().union(out(knows), out(created)).dedup().by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .union(__.out("knows"), __.out("created"))
            .dedup().by("name"));
  }

  /**
   * groupCount().unfold().order().by(Column.values/keys).limit(n) — SQL-native ORDER BY + LIMIT on
   * GROUP BY rows, emitting Map.Entry payloads. Fixture uses unequal counts (Alice=1, Bob=2,
   * Cleo=3) so {@code by(Column.values, desc)} decides the order before {@code keys}; {@code
   * limit(2)} then cuts Cleo+Bob and drops Alice — a modern-graph all-counts-1 seed would never
   * exercise the primary key.
   */
  @Test
  public void groupCount_unfold_order_limit_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Cleo");
    graph.addVertex(T.label, "Person", "name", "Cleo");
    graph.addVertex(T.label, "Person", "name", "Cleo");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().hasLabel(Person).groupCount().by(name).unfold()"
            + ".order().by(Column.values,desc).by(Column.keys).limit(2)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").groupCount().by("name")
            .unfold()
            .order().by(Column.values, Order.desc).by(Column.keys, Order.asc)
            .limit(2));
  }

  /**
   * Nested {@code by(__.order().by(Column.values))} after groupCount unfold declines. Direct
   * {@code by(Column.values)} stays translated; walking into the nested order would sort by value
   * alone while native compares whole map entries.
   */
  @Test
  public void groupCount_unfold_order_byNestedColumnOrder_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).groupCount().by(name).unfold()"
            + ".order().by(__.order().by(Column.values))",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person").groupCount().by("name")
            .unfold()
            .order().by(__.order().by(Column.values)));
  }

  /** groupCount().unfold() alone emits entry multiset (no fold to one map). */
  @Test
  public void groupCount_unfold_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).groupCount().by(name).unfold()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").groupCount().by("name").unfold());
  }

  /** Keyless valueMap declines — schema keys alone under-project vs native schemaless maps. */
  @Test
  public void valueMap_keyless_onHasLabel_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).valueMap()",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person").valueMap());
  }

  /** Bare g.V(id) point-lookup translates and matches native. */
  @Test
  public void bare_V_id_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(marko) — bare RID point-lookup",
        Recognition.RECOGNIZED,
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

  /** Order after union translates via in-memory {@link PostConcatOp.Order}; sequence is pinned. */
  @Test
  public void union_then_order_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "g.V().union(out(knows), out(created)).order().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .union(__.out("knows"), __.out("created"))
            .order().by("name", Order.asc));
  }

  /**
   * Post-union identity {@code order()} over {@code values(name)} arms sorts the projected scalars,
   * not vertex RIDs.
   */
  @Test
  public void union_values_then_order_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "g.V().union(out(knows).values(name), out(created).values(name)).order()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .union(__.out("knows").values("name"), __.out("created").values("name"))
            .order());
  }

  /**
   * Post-union order with a missing sort key matches native null placement (Software vertices have
   * no {@code age}).
   */
  @Test
  public void union_then_orderByMissingKey_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "g.V().union(out(knows), out(created)).order().by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .union(__.out("knows"), __.out("created"))
            .order().by("age", Order.asc));
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

  /** Singleton collection {@code eq([v])} at the folded start normalizes to scalar {@code eq(v)}. */
  @Test
  public void has_eqSingletonCollection_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,eq([marko]))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", P.eq(List.of("marko"))));
  }

  /**
   * After a hop the boundary is still generic {@code V}, so schema-unknown singleton {@code eq}
   * declines (native Compare does not unbox; a schemaless list cell could still match). Both arms
   * stay on native and agree on empty.
   */
  @Test
  public void has_eqSingletonCollection_afterHop_declinesToNativeEmpty() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows).has(name,eq([josh]))",
        Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().out("knows").has("name", P.eq(List.of("josh"))));
  }

  /**
   * After a hop, co-located {@code hasLabel(Person)} makes {@code name} a declared STRING, so
   * unfolded singleton {@code eq} translates to always-false and matches native empty.
   */
  @Test
  public void has_eqSingletonCollection_afterHop_withHasLabel_matchesNativeEmpty() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows).hasLabel(Person).has(name,eq([josh]))",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().out("knows").hasLabel("Person")
            .has("name", P.eq(List.of("josh"))));
  }

  /**
   * Schemaless list cell after a hop: native {@code eq([x])} matches the list; translator must
   * decline (not emit always-false) so ON matches native.
   */
  @Test
  public void has_eqSingletonCollection_afterHop_schemalessList_matchesNative() {
    var a = graph.addVertex(T.label, "Person", "name", "a");
    var b = graph.addVertex(T.label, "Person", "name", "b", "tags", List.of("x"));
    var c = graph.addVertex(T.label, "Person", "name", "c", "tags", "x");
    a.addEdge("knows", b);
    a.addEdge("knows", c);
    graph.tx().commit();
    assertEquivalent(
        "out(knows).has(tags, eq([x])) schemaless list",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("knows").has("tags", P.eq(List.of("x"))));
    assertEquivalent(
        "out(knows).has(tags, neq([x])) schemaless list",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("knows").has("tags", P.neq(List.of("x"))));
  }

  /**
   * Edge select then multi-key values flat-maps edge property values in declaration order. Fixture
   * puts both keys on the edge so a key-order swap cannot hide behind an absent second key.
   */
  @Test
  public void selectEdge_thenMultiKeyValues_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    for (var edge : graph.traversal().E().hasLabel("knows").toList()) {
      edge.property("since", 2012);
    }
    graph.tx().commit();
    assertEquivalentOrdered(
        "g.V().has(name,marko).outE(knows).as(e).inV().select(e).values(since,weight)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "marko").outE("knows").as("e").inV().select("e")
            .values("since", "weight"));
  }

  /** Edge select then elementMap includes Direction.IN/OUT endpoint maps. */
  @Test
  public void selectEdge_thenElementMap_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().outE(knows).as(e).inV().select(e).elementMap(weight)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().outE("knows").as("e").inV().select("e")
            .elementMap("weight"));
  }

  /** Edge select then valueMap projects edge properties without Direction endpoints. */
  @Test
  public void selectEdge_thenValueMap_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().outE(knows).as(e).inV().select(e).valueMap(weight)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().outE("knows").as("e").inV().select("e")
            .valueMap("weight"));
  }

  /**
   * Edge {@code select(e).project(...).by(weight)} — project after an edge boundary must read edge
   * properties, not the hop-target vertex. A regression that left the boundary on the {@code inV}
   * would look for {@code weight} on the Person and diverge.
   */
  @Test
  public void selectEdge_thenProject_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).outE(knows).as(e).inV().select(e).project(w).by(weight)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "marko").outE("knows").as("e").inV()
            .select("e").project("w").by("weight"));
  }

  /**
   * {@code filter(__.has(...))} after a hop — root {@code filter(has)} is covered elsewhere; the
   * mid-path form must still match native (inline hoist bugs can hide at the root).
   */
  @Test
  public void filterHas_afterHop_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows).filter(__.has(age, gte 30))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").filter(__.has("age", P.gte(30))));
  }

  /**
   * Edge {@code valueMap(true, weight)} keeps id/label tokens and must not grow
   * {@code Direction.IN}/{@code OUT} entries — those belong to {@code elementMap} only. A regression
   * that reuses the elementMap endpoint path would diverge from native here.
   */
  @Test
  public void selectEdge_thenValueMapWithTokens_matchesNative_noEndpoints() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().outE(knows).as(e).inV().select(e).valueMap(true, weight)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().outE("knows").as("e").inV().select("e")
            .valueMap(true, "weight"));
  }

  /**
   * {@code groupCount().skip(1).unfold()} must stay empty on both arms: skip drops the sole map,
   * so unfold has nothing to emit. A regression that clears {@code emptyBarrier} when enabling
   * entry emit would stream every group entry.
   */
  @Test
  public void groupCount_skip_thenUnfold_matchesNativeEmpty() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().groupCount().by(name).skip(1).unfold()",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().groupCount().by("name").skip(1).unfold());
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

  /** Vertex as() and edge as() both translate with the same select spelling. */
  @Test
  public void asPlacement_vertexVsEdge_bothTranslate() {
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
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id())
            .outE("knows").as("k").inV()
            .select("k").by("since"));
  }

  /** Bare V(id) and V(id).out both translate. */
  @Test
  public void ridLookup_bareAndWithHop_matchNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "bare: g.V(marko)",
        Recognition.RECOGNIZED,
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
    // Map.Entry before Map: SimpleEntry implements Map but must render as one pair, not a fold.
    if (value instanceof Map.Entry<?, ?> entry) {
      return "E:" + canonicalizeOne(entry.getKey()) + "=" + canonicalizeOne(entry.getValue());
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
