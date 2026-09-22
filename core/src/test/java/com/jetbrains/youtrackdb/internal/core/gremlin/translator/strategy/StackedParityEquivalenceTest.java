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
 * Deep translator-on / translator-off result parity for Phase-1 stacks that are easy to get
 * quietly wrong: multi-hop filters, multiplicity, Text / within / hasNot after hops, ordered
 * slices after hops, post-hop connectives, union+dedup+order, edge-filter chains, and
 * present-null edge properties.
 *
 * <p>Sibling suites ({@link CompositionEquivalenceTest}, {@link EdgeTraversalEquivalenceTest},
 * {@link PredicateTraversalEquivalenceTest}, …) already pin many surfaces. This class adds
 * discriminating fixtures (fan-out, ties, absent keys, parallel edges, self-loops) so empty==empty
 * or filter≡join cannot pass vacuously.
 */
public class StackedParityEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  // ---------------------------------------------------------------------------
  // Multi-hop + filters on both sides / alternating directions
  // ---------------------------------------------------------------------------

  /**
   * Two hops with filters on the source, mid, and far vertex: catches dropped mid-alias filters
   * and wrong root selection under fan-out.
   */
  @Test
  public void has_out_has_out_has_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).out(knows).has(age,gte 30).out(created).has(lang,java)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out("knows")
            .has("age", P.gte(30))
            .out("created")
            .has("lang", "java"));
  }

  /** Alternating in→out from a software vertex; values projection pins the far Person names. */
  @Test
  public void in_out_values_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(lop).in(created).out(knows).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.lop().id())
            .in("created")
            .out("knows")
            .values("name"));
  }

  /** both() then far-side age filter then values — discriminating vs bothE decline. */
  @Test
  public void both_has_values_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().both(knows).has(age,lt 30).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .both("knows")
            .has("age", P.lt(30))
            .values("name"));
  }

  // ---------------------------------------------------------------------------
  // Multiplicity, parallel edges, multi-start
  // ---------------------------------------------------------------------------

  /**
   * Parallel knows edges must inflate {@code out().count()} — a DISTINCT collapse would under-count
   * vs native.
   */
  @Test
  public void parallelEdges_out_count_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", bob);
    graph.tx().commit();
    assertEquivalent(
        "parallel edges g.V(alice).out(knows).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id()).out("knows").count());
  }

  /** Multi-start star + dedup + values — multi-root RID inject must not drop or duplicate. */
  @Test
  public void multiStart_out_dedup_values_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(marko,josh).out(created).dedup().values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.marko().id(), m.josh().id())
            .out("created")
            .dedup()
            .values("name"));
  }

  /** Mid-path hasId then hop — RID filter on non-root must keep the path alive. */
  @Test
  public void out_hasId_out_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(marko).out(knows).hasId(josh).out(created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.marko().id())
            .out("knows")
            .hasId(m.josh().id())
            .out("created"));
  }

  // ---------------------------------------------------------------------------
  // Post-hop predicates (Text, within/without, hasNot, connectives)
  // ---------------------------------------------------------------------------

  /** Post-hop TextP after created hop. */
  @Test
  public void out_textContaining_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(created).has(name,containing o)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .out("created")
            .has("name", TextP.containing("o")));
  }

  /**
   * Post-hop within on a key some neighbours lack — absent rows must not match within, matching
   * native.
   */
  @Test
  public void out_within_onPartiallyAbsentKey_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "lang", "java");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol"); // no lang
    alice.addEdge("knows", bob);
    alice.addEdge("knows", carol);
    graph.tx().commit();
    assertEquivalent(
        "g.V(alice).out(knows).has(lang,within java,python)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id())
            .out("knows")
            .has("lang", P.within("java", "python")));
  }

  /** Post-hop hasNot — only neighbours missing the property. */
  @Test
  public void out_hasNot_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 30);
    var carol = graph.addVertex(T.label, "Person", "name", "Carol"); // no age
    alice.addEdge("knows", bob);
    alice.addEdge("knows", carol);
    graph.tx().commit();
    assertEquivalent(
        "g.V(alice).out(knows).hasNot(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id()).out("knows").hasNot("age"));
  }

  /** Pure nested where after hop (no edge-bearing child). */
  @Test
  public void out_whereNestedHas_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(marko).out(knows).where(has(age,gt 30).has(name,neq x))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.marko().id())
            .out("knows")
            .where(__.has("age", P.gt(30)).has("name", P.neq("x"))));
  }

  /** Pure or of property filters after hop. */
  @Test
  public void out_orAges_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows).or(has(age,27), has(age,32))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .out("knows")
            .or(__.has("age", 27), __.has("age", 32)));
  }

  /** not(has age lt) after hop — presence + compare interaction. */
  @Test
  public void out_notYoung_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows).not(has(age,lt 30))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .out("knows")
            .not(__.has("age", P.lt(30))));
  }

  // ---------------------------------------------------------------------------
  // Edge-filter chains + Text / present-null weight
  // ---------------------------------------------------------------------------

  /** Edge filter + far Text + values. */
  @Test
  public void outE_has_inV_text_values_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().outE(created).has(weight,lt 0.5).inV().has(name,startingWith l).values(lang)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .outE("created").has("weight", P.lt(0.5))
            .inV()
            .has("name", TextP.startingWith("l"))
            .values("lang"));
  }

  /** Two successive filtered edge hops. */
  @Test
  public void outE_has_inV_outE_has_inV_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(marko).outE(knows).has(weight,gt 0.5).inV().outE(created).has(weight,lt 1).inV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.marko().id())
            .outE("knows").has("weight", P.gt(0.5))
            .inV()
            .outE("created").has("weight", P.lt(1.0))
            .inV());
  }

  /**
   * Present-null edge weight vs absent: {@code neq(null)} must exclude absent and present-null
   * alike native, while {@code has(weight)} keeps present-null.
   */
  @Test
  public void outE_presentNullWeight_neqNull_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    var dave = graph.addVertex(T.label, "Person", "name", "Dave");
    alice.addEdge("knows", bob, "weight", 0.5);
    var nullWeight = alice.addEdge("knows", carol);
    nullWeight.property("weight", null);
    alice.addEdge("knows", dave); // weight absent
    graph.tx().commit();
    assertEquivalent(
        "g.V(alice).outE(knows).has(weight,neq null).inV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id())
            .outE("knows").has("weight", P.neq(null))
            .inV());
  }

  /** inE filtered + outV groupCount by name. */
  @Test
  public void inE_has_outV_groupCount_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Software).inE(created).has(weight,gte 0.4).outV().groupCount().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Software")
            .inE("created").has("weight", P.gte(0.4))
            .outV()
            .groupCount().by("name"));
  }

  // ---------------------------------------------------------------------------
  // Order + slice after hop (sequence-sensitive)
  // ---------------------------------------------------------------------------

  /** Hop then order then limit — allowed; order then hop then limit declines (pinned elsewhere). */
  @Test
  public void out_order_limit_matchesNativeOrdered() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "g.V(marko).out(knows).order().by(name).limit(1)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.marko().id())
            .out("knows")
            .order().by("name", Order.asc)
            .limit(1));
  }

  /** Multi-key order + range on tied ages — sequence, not multiset. */
  @Test
  public void orderByAgeThenName_range_matchesNativeOrdered() {
    graph.addVertex(T.label, "Person", "name", "Ann", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Ben", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Cy", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Dee", "age", 30);
    graph.tx().commit();
    assertEquivalentOrdered(
        "g.V().hasLabel(Person).order().by(age).by(name).range(1,3)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Person")
            .order().by("age", Order.asc).by("name", Order.asc)
            .range(1, 3));
  }

  /**
   * {@code values(k).order().limit(n)} declines (sorted slice over a values projection); the same
   * prefix without the slice still translates. Boundary count is the honesty pin — see
   * {@code OrderRangeStepRecogniserTest#sortedSliceOverValues_declines_orderThenRangeTranslates}.
   */
  @Test
  public void values_order_limit_declines_whileValuesOrderTranslates() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "decline: g.V().hasLabel(Person).values(age).order().limit(2)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .hasLabel("Person")
            .values("age")
            .order().by(Order.asc)
            .limit(2));
    assertEquivalentOrdered(
        "control: g.V().hasLabel(Person).values(age).order()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Person")
            .values("age")
            .order().by(Order.asc));
  }

  // ---------------------------------------------------------------------------
  // Select / project / as modulators
  // ---------------------------------------------------------------------------

  /** as + select.by(values) modulator after hop. */
  @Test
  public void out_as_selectByValues_matchesNative() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V(marko).out(knows).as(f).select(f).by(values(age))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(m.marko().id())
            .out("knows").as("f")
            .select("f").by(__.values("age")));
  }

  /**
   * {@code project} after hop with a missing key on some neighbours (Software has no {@code age}).
   * Default ProductiveByStrategy omits the absent key from the map; translator must not emit
   * {@code age=null}.
   */
  @Test
  public void out_project_missingKey_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).out().project(name,age).by(name).by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out()
            .project("name", "age").by("name").by("age"));
  }

  /** Same omit contract when project keys differ from property names. */
  @Test
  public void out_project_mismatchedKeys_missingProp_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).out().project(n,a).by(name).by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out()
            .project("n", "a").by("name").by("age"));
  }

  /** select with hop-bearing by-modulator declines (nested hop in modulator). */
  @Test
  public void select_byOutCount_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().as(a).select(a).by(out(created).count())",
        Recognition.DECLINED,
        () -> graph.traversal().V().as("a")
            .select("a").by(__.out("created").count()));
  }

  // ---------------------------------------------------------------------------
  // Union stacks
  // ---------------------------------------------------------------------------

  /** Three overlapping arms + dedup + count. */
  @Test
  public void unionThree_dedup_count_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().union(out(knows), out(created), in(knows)).dedup().count()",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V()
            .union(__.out("knows"), __.out("created"), __.in("knows"))
            .dedup()
            .count());
  }

  /** Union then dedup then order — combined post-concat stack. */
  @Test
  public void union_dedup_order_matchesNativeOrdered() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "g.V().union(out(knows), out(created)).dedup().order().by(name)",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V()
            .union(__.out("knows"), __.out("created"))
            .dedup()
            .order().by("name", Order.asc));
  }

  /** Union arms at different hop depths then order — projection contract across arms. */
  @Test
  public void union_mixedDepth_order_matchesNativeOrdered() {
    var m = ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "g.V(marko).union(out(knows), out(knows).out(created)).order().by(name)",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V(m.marko().id())
            .union(__.out("knows"), __.out("knows").out("created"))
            .order().by("name", Order.asc));
  }

  // ---------------------------------------------------------------------------
  // Self-loop + directed filtered chain (control beside bothE decline)
  // ---------------------------------------------------------------------------

  /**
   * Self-loop + directed filtered outE.inV + hasLabel — must preserve the loop neighbour when the
   * filter matches; bothE form of the same filter stays declined.
   */
  @Test
  public void selfLoop_outE_has_inV_hasLabel_matchesNative() {
    session.createVertexClass("Person");
    session.createEdgeClass("knows");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", alice, "since", 2009); // self-loop
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();
    assertEquivalent(
        "directed self-loop: g.V(alice).outE(knows).has(since,lt 2015).inV().hasLabel(Person)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id())
            .outE("knows").has("since", P.lt(2015))
            .inV()
            .hasLabel("Person"));
    assertEquivalent(
        "bothE form declines: g.V(alice).bothE(knows).has(since,lt 2015).otherV()",
        Recognition.DECLINED,
        () -> graph.traversal().V(alice.id())
            .bothE("knows").has("since", P.lt(2015))
            .otherV());
  }

  /** Mid-path dedup then hop declines (sibling of out.limit.out). */
  @Test
  public void out_dedup_out_declines() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).out(knows).dedup().out(created)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out("knows")
            .dedup()
            .out("created"));
  }

  /** Non-polymorphic post-hop hasLabel with Employee subclass neighbour. */
  @Test
  public void out_hasLabelPerson_nonPolymorphic_matchesNative() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    session.createEdgeClass("knows");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var eve = graph.addVertex(T.label, "Employee", "name", "Eve");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", eve);
    alice.addEdge("knows", bob);
    graph.tx().commit();
    withPolymorphicDefault(false, () -> assertEquivalent(
        "non-poly g.V(alice).out(knows).hasLabel(Person)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id()).out("knows").hasLabel("Person")));
  }

  /** Schemaless undeclared property filter after hop on Software. */
  @Test
  public void out_schemalessHas_matchesNative() {
    // Modern Software has schema props name/lang; add an undeclared prop on one target.
    var m = ModernGraphFixture.seed(graph, session);
    m.lop().property("extra", "x");
    graph.tx().commit();
    assertEquivalent(
        "g.V().out(created).has(extra,x)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("created").has("extra", "x"));
  }

  // ---------------------------------------------------------------------------
  // Gap fill — step order, order×select×limit, chained select, repeated filters.
  // ---------------------------------------------------------------------------

  /**
   * Spelling pair: one {@code order().by(a).by(b)} merges into a single ORDER BY (translates);
   * two {@code order()} steps decline. Same fixture so empty≠empty cannot hide a wrong merge.
   */
  @Test
  public void multiByOrder_vs_secondOrder_documentsException() {
    seedTiedAges();
    assertEquivalentOrdered(
        "PAIR translate: order().by(age).by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person")
            .order().by("age", Order.asc).by("name", Order.asc));
    assertEquivalentOrdered(
        "PAIR decline: order().by(age).order().by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person")
            .order().by("age", Order.asc)
            .order().by("name", Order.asc));
  }

  /** Hop → as → order → limit → select — common LDBC-ish stack. */
  @Test
  public void out_as_order_limit_select_matchesNativeOrdered() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "g.V().has(name,marko).out(knows).as(f).order().by(name).limit(1).select(f).by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out("knows").as("f")
            .order().by("name", Order.asc)
            .limit(1)
            .select("f").by("age"));
  }

  /**
   * Ordering a map by {@code Column.keys} declines; element {@code order().by.by.select} still
   * translates on the same fixture.
   */
  @Test
  public void selectMap_orderByColumnKeys_declines_whileElementOrderSelectTranslates() {
    seedTiedAges();
    assertEquivalentOrdered(
        "g.V().as(a).as(n).select(a,n).by(age).by(name).order().by(Column.keys)",
        Recognition.DECLINED,
        // order().by(Column.keys) on a Map stream is not a Phase-1 ORDER BY property key.
        () -> graph.traversal().V().hasLabel("Person").as("a").as("n")
            .select("a", "n").by("age").by("name")
            .order().by(org.apache.tinkerpop.gremlin.structure.Column.keys));
    assertEquivalentOrdered(
        "control: g.V().as(p).order().by(age).by(name).select(p).by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").as("p")
            .order().by("age", Order.asc).by("name", Order.asc)
            .select("p").by("name"));
  }

  /**
   * Chained select without cardinality translates (BG2200); the same chain after {@code limit}
   * declines (containment). Paired so a silent re-admit of the limited shape fails here.
   */
  @Test
  public void chainedSelect_withAndWithoutLimit_documentsException() {
    graph.addVertex(T.label, "Person", "name", "Alice", "city", "London", "rank", 1);
    graph.addVertex(T.label, "Person", "name", "Bob", "city", "Paris", "rank", 2);
    graph.tx().commit();
    assertEquivalent(
        "PAIR translate: select(q,r).by.select(q)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").as("q").as("r")
            .select("q", "r").by("name").by("city")
            .select("q"));
    assertEquivalentOrdered(
        "PAIR decline: order.limit.select(q,r).by.select(q)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person").as("q").as("r")
            .order().by("rank", Order.asc)
            .limit(1)
            .select("q", "r").by("name").by("city")
            .select("q"));
  }

  /** Dedup then boundary select translates; overlapping select after dedup+map declines. */
  @Test
  public void dedup_select_boundaryVsOverlapping_documentsException() {
    graph.addVertex(T.label, "Person", "name", "Alice", "city", "London");
    graph.addVertex(T.label, "Person", "name", "Bob", "city", "Paris");
    graph.tx().commit();
    assertEquivalent(
        "PAIR translate: dedup().select(q).by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").as("q")
            .dedup()
            .select("q").by("name"));
    assertEquivalent(
        "PAIR decline: dedup().select(q,r).by.by.select(q)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person").as("q").as("r")
            .dedup()
            .select("q", "r").by("name").by("city")
            .select("q"));
  }

  /**
   * Repeated property filters AND-compose; order of pure {@code has} must not change the multiset
   * under translation.
   */
  @Test
  public void repeatedHas_orderIndependent_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "has(age).has(name) then hop",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("age", P.gte(30))
            .has("name", P.neq("x"))
            .out("created"));
    assertEquivalent(
        "has(name).has(age) then hop — same filters swapped",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", P.neq("x"))
            .has("age", P.gte(30))
            .out("created"));
  }

  /** Two hops with the mid filter before vs after the first hop — both translate. */
  @Test
  public void midFilter_beforeVsAfterFirstHop_bothTranslate() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "filter mid after first hop: out(knows).has(age).out(created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out("knows")
            .has("age", P.gte(30))
            .out("created"));
    assertEquivalent(
        "source filter only: has(name).out(knows).out(created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko")
            .out("knows")
            .out("created"));
  }

  /** {@code skip} then hop declines; hop then skip translates (sibling of hopSlice pair). */
  @Test
  public void hopSkip_orderPair_documentsException() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "PAIR translate: out(created).skip(1)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("created").skip(1));
    assertEquivalent(
        "PAIR decline: skip(1).out(created)",
        Recognition.DECLINED,
        () -> graph.traversal().V().skip(1).out("created"));
  }

  /** {@code range} then hop declines; hop then range translates. */
  @Test
  public void hopRange_orderPair_documentsException() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "PAIR translate: out(knows).range(0,2)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "marko").out("knows").range(0, 2));
    assertEquivalent(
        "PAIR decline: range(0,2).out(knows)",
        Recognition.DECLINED,
        () -> graph.traversal().V().has("name", "marko").range(0, 2).out("knows"));
  }

  /**
   * {@code order().by(name).limit(n).values(name)} translates; {@code values(name).order().limit}
   * declines (sorted slice over values).
   */
  @Test
  public void orderLimitValues_vs_valuesOrderLimit_documentsException() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalentOrdered(
        "PAIR translate: order().by(name).limit(2).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person")
            .order().by("name", Order.asc)
            .limit(2)
            .values("name"));
    assertEquivalentOrdered(
        "PAIR decline: values(name).order().limit(2)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person")
            .values("name")
            .order().by(Order.asc)
            .limit(2));
  }

  /**
   * Project then order by a project key declines (Map traverser, not element property ORDER BY).
   */
  @Test
  public void out_project_orderByProjectKey_declines() {
    ModernGraphFixture.seed(graph, session);
    // Ordering a Map traverser by a string key is not Phase-1 ORDER BY on an element property.
    assertEquivalentOrdered(
        "g.V().out(created).project(n,l).by(name).by(lang).order().by(n)",
        Recognition.DECLINED,
        () -> graph.traversal().V()
            .out("created")
            .project("n", "l").by("name").by("lang")
            .order().by("n", Order.asc));
  }

  /**
   * Double {@code dedup()} declines (second has no MATCH composition rule once DISTINCT is
   * captured); a single {@code dedup()} still translates on the same fixture.
   */
  @Test
  public void out_dedup_dedup_declines_whileSingleDedupTranslates() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "PAIR decline: out(created).dedup().dedup()",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("created").dedup().dedup());
    assertEquivalent(
        "PAIR translate: out(created).dedup()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("created").dedup());
  }

  /** {@code hasLabel} then {@code hasLabel} again (narrower) — AND-compose labels. */
  @Test
  public void hasLabel_then_hasLabel_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().hasLabel(Person).hasLabel(Person).out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .hasLabel("Person")
            .hasLabel("Person")
            .out("knows"));
  }

  /**
   * Bare {@code select(a)} keeps the element; a following hop still translates. Contrasts with
   * modulated {@code select(a).by(name)} which would leave a scalar (not pinned here — both arms
   * throw natively).
   */
  @Test
  public void selectElement_then_out_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().has(name,marko).as(a).select(a).out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .has("name", "marko").as("a")
            .select("a")
            .out("knows"));
  }

  /** {@code hasNot} after hop then another hop — presence filter mid-path. */
  @Test
  public void out_hasNot_out_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob"); // no age
    var carol = graph.addVertex(T.label, "Person", "name", "Carol", "age", 30);
    var soft = graph.addVertex(T.label, "Software", "name", "x", "lang", "java");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", carol);
    bob.addEdge("created", soft);
    carol.addEdge("created", soft);
    graph.tx().commit();
    assertEquivalent(
        "g.V(alice).out(knows).hasNot(age).out(created)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(alice.id())
            .out("knows")
            .hasNot("age")
            .out("created"));
  }

  /** {@code and} of pure filters after hop then values. */
  @Test
  public void out_andHas_values_matchesNative() {
    ModernGraphFixture.seed(graph, session);
    assertEquivalent(
        "g.V().out(knows).and(has(age,gte 30), hasLabel(Person)).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .out("knows")
            .and(__.has("age", P.gte(30)), __.hasLabel("Person"))
            .values("name"));
  }

  private void seedTiedAges() {
    graph.addVertex(T.label, "Person", "name", "Ann", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Ben", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Cy", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Dee", "age", 30);
    graph.tx().commit();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

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
          .map(StackedParityEquivalenceTest::canonicalizeOne)
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
