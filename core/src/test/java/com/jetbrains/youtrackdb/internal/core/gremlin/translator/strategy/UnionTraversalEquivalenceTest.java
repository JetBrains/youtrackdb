package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Translator-on / translator-off equivalence for mid-traversal {@code union(c1, …, cN)}. Happy paths
 * must engage a {@link MultiPlanMatchStep} and return the concatenated multiset (not a cartesian
 * product). Decline paths leave the native pipeline in place.
 */
public class UnionTraversalEquivalenceTest extends GraphBaseTest {

  /**
   * What the translator must do with a shape. {@code RECOGNIZED_MULTI_PLAN} additionally pins that
   * the spliced boundary is a {@link MultiPlanMatchStep} — a shape can be recognised into the
   * single-plan boundary instead, which is a different contract and must not silently satisfy a
   * union test.
   */
  private enum Recognition {
    RECOGNIZED, RECOGNIZED_MULTI_PLAN, DECLINED
  }

  /**
   * {@code g.V().union(out("knows"), in("knows"))} translates to a multi-plan boundary and returns
   * the same vertex multiset as native. Seed is Alice→Bob→Carol: out yields {Bob, Carol}, in yields
   * {Alice, Bob}, concatenation is the four-element multiset.
   */
  @Test
  public void unionOutAndIn_returnsConcatenatedMultisetAsNative() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows))",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")));
  }

  /**
   * Anti-cartesian pin: children whose sizes' product differs from their sum must return the sum.
   * From Alice, {@code out()} yields {Bob, Carol} (size 2) and {@code out().out()} yields {Dave}
   * (size 1); sum is 3 and product is 2. A mistaken cartesian join of the child patterns would
   * return 2 rows, not the concatenated 3.
   */
  @Test
  public void unionAntiCartesian_returnsSumNotProduct() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    var dave = graph.addVertex(T.label, "Person", "name", "Dave");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", carol);
    bob.addEdge("knows", dave);
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "g.V(alice).union(out(), out().out()) — |c1|+|c2| ≠ |c1|·|c2|",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V(aliceId).union(__.out(), __.out().out()));

    // Explicit size pin against a silent cartesian regression that happened to match the native
    // multiset somehow: the concatenated result must have size 3 (2+1), never the product 2.
    setTranslatorEnabled(true);
    var ids =
        sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).toList());
    assertThat(ids).hasSize(3);
  }

  /**
   * Children with different hop counts mint different boundary aliases ({@code $g2m_anon_0} vs
   * {@code $g2m_anon_1}). The agreement gate rewrites every child's RETURN alias to the first
   * child's canonical alias so the multi-plan boundary projects every row.
   */
  @Test
  public void unionDifferentHopCounts_canonicalAliasParity() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), out(knows).out(knows)) — differing hop aliases",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V().union(__.out("knows"), __.out("knows").out("knows")));
  }

  /**
   * A RID-bearing start inside a union child still translates (cache forced off for multi-plan) and
   * returns the same multiset as native.
   */
  @Test
  public void unionWithRidBearingPrefix_returnsSameMultiset() {
    seedKnowsChain();
    var aliceId =
        graph.traversal().V().has("name", "Alice").id().next();
    assertEquivalent(
        "g.V(alice).union(out(knows), in(knows)) — RID-bearing start",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V(aliceId).union(__.out("knows"), __.in("knows")));
  }

  /**
   * Projection-contract mismatch: {@code values("name")} (SINGLE_VALUE) and {@code out()} (ELEMENT)
   * disagree, so the whole union declines to native.
   */
  @Test
  public void unionProjectionContractMismatch_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(values(name), out(knows)) — output-type mismatch",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.values("name"), __.out("knows")));
  }

  /**
   * Nested union inside a child declines the whole union rather than flattening.
   */
  @Test
  public void nestedUnionInsideChild_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), union(in(knows), out(knows))) — nested union",
        Recognition.DECLINED,
        () -> graph
            .traversal()
            .V()
            .union(__.out("knows"), __.union(__.in("knows"), __.out("knows"))));
  }

  /**
   * {@code union(…).count()} translates: push-down {@code RETURN count(*)} per child, sum on the
   * multi-plan boundary — same Long as native.
   */
  @Test
  public void unionThenCount_returnsSameTotalAsNative() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).count()",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).count());
  }

  /**
   * {@code union(…).dedup()} translates: global dedup over the concatenation (cross-child duplicates
   * removed).
   */
  @Test
  public void unionThenDedup_returnsSameMultisetAsNative() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).dedup()",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).dedup());
  }

  /**
   * {@code union(…).limit(n)} translates with early-stop on the concatenator. Seeded from a single
   * start vertex so emission order is stable across native vs MATCH (bare {@code g.V()} order can
   * differ while the full multiset still matches after sort).
   */
  @Test
  public void unionThenLimit_returnsSamePrefixAsNative() {
    seedKnowsChain();
    var aliceId = graph.traversal().V().has("name", "Alice").id().next();
    assertEquivalent(
        "g.V(alice).union(out(knows), in(knows)).limit(2)",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V(aliceId).union(__.out("knows"), __.in("knows")).limit(2));
  }

  /**
   * {@code order()} after union still declines — in-memory post-concat sort is not in this cut.
   */
  @Test
  public void unionThenOrder_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).order()",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).order());
  }

  /**
   * A hop after the union declines. The multi-plan translation carries only the boundary metadata,
   * the shaping, and the post-concat ops, so a trailing {@code out()} would append its hop to the
   * discarded parent pattern and the query would silently return the union's own vertices instead of
   * their neighbours. Native from the Alice→Bob→Carol chain: the concatenation is
   * [Bob, Carol, Alice, Bob] and the trailing hop yields [Carol, Bob, Carol].
   */
  @Test
  public void hopAfterUnion_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).out(knows) — hop after union",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).out("knows"));
  }

  /**
   * A filter after the union declines. {@code has(...)} writes an alias filter onto the parent
   * context, which the multi-plan branch discards, so the query would return the unfiltered union.
   */
  @Test
  public void filterAfterUnion_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).has(name, Bob) — filter after union",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).has("name", "Bob"));
  }

  /**
   * A projection after the union declines: {@code values(...)} would rewrite the discarded parent
   * RETURN projection while the child plans keep emitting whole elements.
   */
  @Test
  public void projectionAfterUnion_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).values(name) — projection after union",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).values("name"));
  }

  /**
   * A lone post-union {@code count()} is served by rewriting every child to {@code RETURN count(*)},
   * and that rewrite drops the child's own {@code LIMIT}. A child carrying one must therefore
   * decline, the same way the single-plan path refuses {@code limit(n).count()}. From Alice with two
   * outgoing edges the correct total is 1 (truncated arm) + 2 (full arm) = 3; a push-down that lost
   * the child limit would report 4.
   */
  @Test
  public void unionChildWithLimitThenCount_declines() {
    var aliceId = seedFanOut();
    assertEquivalent(
        "g.V(alice).union(out(knows).limit(1), out(knows)).count() — child LIMIT under count",
        Recognition.DECLINED,
        () -> graph
            .traversal()
            .V(aliceId)
            .union(__.out("knows").limit(1), __.out("knows"))
            .count());

    setTranslatorEnabled(true);
    assertThat(
        graph
            .traversal()
            .V(aliceId)
            .union(__.out("knows").limit(1), __.out("knows"))
            .count()
            .next())
        .as("the truncated arm contributes 1, the full arm 2")
        .isEqualTo(3L);
  }

  /**
   * Same gate for a child carrying {@code skip()}: the count push-down clears the child's {@code
   * SKIP}, so the arm would contribute the rows it skipped. From Alice with two outgoing edges the
   * skipped arm contributes 1 and the full arm 2, total 3; a push-down that lost the child skip
   * would report 4.
   */
  @Test
  public void unionChildWithSkipThenCount_declines() {
    var aliceId = seedFanOut();
    assertEquivalent(
        "g.V(alice).union(out(knows).skip(1), out(knows)).count() — child SKIP under count",
        Recognition.DECLINED,
        () -> graph
            .traversal()
            .V(aliceId)
            .union(__.out("knows").skip(1), __.out("knows"))
            .count());

    setTranslatorEnabled(true);
    assertThat(
        graph
            .traversal()
            .V(aliceId)
            .union(__.out("knows").skip(1), __.out("knows"))
            .count()
            .next())
        .as("the skipped arm contributes 1, the full arm 2")
        .isEqualTo(3L);
  }

  /**
   * Same gate for a child carrying {@code dedup()}: the count push-down clears {@code RETURN
   * DISTINCT}, so the arm would contribute its duplicates.
   */
  @Test
  public void unionChildWithDedupThenCount_declines() {
    seedFanOut();
    assertEquivalent(
        "g.V().union(out(knows).dedup(), in(knows)).count() — child DISTINCT under count",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows").dedup(), __.in("knows")).count());
  }

  /**
   * {@code count()} after another post-concat reduction takes the stream-count path instead of the
   * per-child push-down: the concatenation is truncated first and the surviving rows are counted.
   * From Alice, {@code out()} yields 2 and {@code out().out()} yields 1, so the untruncated total is
   * 3 and {@code limit(2)} must bring the count down to 2.
   */
  @Test
  public void unionThenLimitThenCount_countsTruncatedConcatenation() {
    var aliceId = seedFanOut();
    assertEquivalent(
        "g.V(alice).union(out(knows), out(knows).out(knows)).limit(2).count()",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph
            .traversal()
            .V(aliceId)
            .union(__.out("knows"), __.out("knows").out("knows"))
            .limit(2)
            .count());

    setTranslatorEnabled(true);
    assertThat(
        graph
            .traversal()
            .V(aliceId)
            .union(__.out("knows"), __.out("knows").out("knows"))
            .limit(2)
            .count()
            .next())
        .as("the stream count sees at most the limit, never the pushed-down child totals")
        .isEqualTo(2L);
  }

  /**
   * {@code dedup()} before {@code count()} also disables the push-down: cross-child duplicates
   * collapse before the count. From the Alice→Bob→Carol chain the concatenation is
   * [Bob, Carol, Alice, Bob] — four rows, three distinct.
   */
  @Test
  public void unionThenDedupThenCount_countsDistinctConcatenation() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).dedup().count()",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).dedup().count());

    setTranslatorEnabled(true);
    assertThat(graph.traversal().V().union(__.out("knows"), __.in("knows")).dedup().count().next())
        .as("three distinct vertices out of a four-row concatenation")
        .isEqualTo(3L);
  }

  /**
   * {@code limit(n)} after a union truncates the concatenation to {@code n} rows. The four-row
   * fixture is the point: with a concatenation no larger than the limit the truncation is invisible
   * and dropping it entirely still passes. Emission order across arms is not pinned, so the
   * assertion is cardinality plus membership in the untruncated union rather than an exact list.
   */
  @Test
  public void unionThenLimit_truncatesConcatenationToLimit() {
    var aliceId = seedWideFanOut();
    assertMultiPlanEngaged(
        () -> graph.traversal().V(aliceId).union(__.out(), __.out().out()).limit(2));

    setTranslatorEnabled(true);
    var full = sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).toList());
    var limited =
        sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).limit(2).toList());

    assertThat(full).as("the untruncated union is 3 + 1 rows").hasSize(4);
    assertThat(limited).as("limit(2) truncates the concatenation").hasSize(2);
    assertThat(full).containsAll(limited);
  }

  /**
   * {@code skip(n)} and {@code range(low, high)} after a union slice the concatenation. {@code
   * skip} is the only shape that reaches the skipping stream at all, and {@code range(1, 3)} on a
   * four-row concatenation is the smallest case that separates a {@code high - low} row budget
   * (2 rows) from a {@code high} one (3 rows). Order across arms is not pinned, so the assertions
   * are cardinality plus membership against the full union.
   */
  @Test
  public void unionThenSkipAndRange_sliceTheConcatenation() {
    var aliceId = seedWideFanOut();
    assertMultiPlanEngaged(
        () -> graph.traversal().V(aliceId).union(__.out(), __.out().out()).skip(1));
    assertMultiPlanEngaged(
        () -> graph.traversal().V(aliceId).union(__.out(), __.out().out()).range(1, 3));

    setTranslatorEnabled(true);
    var full = sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).toList());
    assertThat(full).hasSize(4);

    var skipped =
        sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).skip(1).toList());
    assertThat(skipped).as("skip(1) drops exactly one row").hasSize(3);
    assertThat(full).containsAll(skipped);

    var ranged =
        sortedIds(
            graph.traversal().V(aliceId).union(__.out(), __.out().out()).range(1, 3).toList());
    assertThat(ranged).as("range(1, 3) keeps high - low = 2 rows after skipping 1").hasSize(2);
    assertThat(full).containsAll(ranged);

    var openEnded =
        sortedIds(
            graph.traversal().V(aliceId).union(__.out(), __.out().out()).range(1, -1).toList());
    assertThat(openEnded).as("an unbounded high is skip-only").hasSize(3);
    assertThat(full).containsAll(openEnded);
  }

  /**
   * Start-position {@code g.union(...)} has no vertex GraphStep prefix; the strategy and recogniser
   * both decline.
   */
  @Test
  public void startPositionUnion_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.union(V(), V().out(knows)) — start-position union",
        Recognition.DECLINED,
        () -> graph.traversal().union(__.V(), __.V().out("knows")));
  }

  /**
   * The agreement gate's third leg in isolation. {@code values("name")} and {@code values("age")}
   * agree on {@code BoundaryOutputType} (SINGLE_VALUE) and on return class and differ only in their
   * {@code ResultShaping} presence key, so only a shaping comparison can tell them apart. Accepting
   * would project child two's rows under child one's presence key: the ages would surface as names
   * or be dropped as absent. The output-type mismatch test above fires on the first leg and says
   * nothing about this one.
   */
  @Test
  public void unionShapingOnlyMismatch_declines() {
    seedKnowsChainWithAges();
    assertEquivalent(
        "g.V().union(values(name), values(age)) — same output type, different presence key",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.values("name"), __.values("age")));
  }

  /**
   * Two children whose positional slot {@code 0} holds different literals must each resolve their
   * own slot. Every other union child in this class is {@code out()} / {@code in()} / {@code
   * values(...)}, none of which binds a literal, so without this shape no end-to-end union execution
   * has ever carried a non-empty parameter map on any child. If the children shared one context or
   * one parameter map the union would return one name twice instead of both.
   */
  @Test
  public void unionChildrenWithDistinctPositionalParams_resolveTheirOwnSlots() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(has(name,Alice), has(name,Bob)) — distinct slot 0 per child",
        Recognition.RECOGNIZED_MULTI_PLAN,
        () -> graph.traversal().V().union(__.has("name", "Alice"), __.has("name", "Bob")));

    setTranslatorEnabled(true);
    var names =
        graph
            .traversal()
            .V()
            .union(__.has("name", "Alice"), __.has("name", "Bob"))
            .values("name")
            .toList();
    assertThat(names)
        .as("each child resolves its own slot 0; neither literal leaks into the other child")
        .containsExactlyInAnyOrder("Alice", "Bob");
  }

  /**
   * A range after a count has nothing left to slice — the count already collapsed the concatenation
   * to a single scalar row — so the suffix declines instead of truncating the count itself.
   */
  @Test
  public void postUnionRangeAfterCount_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).count().limit(1) — range after count",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).count().limit(1));
  }

  /**
   * A second post-union dedup, and a dedup after a count, both decline. The second dedup is
   * redundant; the dedup after a count would deduplicate one scalar row.
   */
  @Test
  public void secondPostUnionDedupAndDedupAfterCount_decline() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).dedup().dedup() — second post-union dedup",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).dedup().dedup());
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).count().dedup() — dedup after count",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).count().dedup());
  }

  /**
   * A second post-union count declines: the first count already reduced the concatenation to one
   * row, so a second one would report 1 rather than re-count anything.
   */
  @Test
  public void secondPostUnionCount_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).count().count() — second post-union count",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).count().count());
  }

  /**
   * A child that the walker cannot translate (unsupported {@code flatMap}) declines the whole union.
   */
  @Test
  public void decliningChild_declinesWholeUnion() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), flatMap(out(knows))) — declining child",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.flatMap(__.out("knows"))));
  }

  /**
   * Seeds Alice -knows-> Bob, Alice -knows-> Carol, Bob -knows-> Dave and returns Alice's id. From
   * Alice, {@code out()} yields two vertices and {@code out().out()} yields one, so the two arms
   * have different sizes and a lost per-arm clause changes the total.
   */
  private Object seedFanOut() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    var dave = graph.addVertex(T.label, "Person", "name", "Dave");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", carol);
    bob.addEdge("knows", dave);
    graph.tx().commit();
    return alice.id();
  }

  /**
   * Seeds Alice -knows-> Bob, Alice -knows-> Carol, Alice -knows-> Dave, Bob -knows-> Erin and
   * returns Alice's id. From Alice, {@code union(out(), out().out())} concatenates 3 + 1 = 4 rows —
   * wide enough that a post-union {@code limit(2)} genuinely truncates and that {@code range(1, 3)}
   * yields a different row count under a {@code high - low} budget than under a {@code high} one.
   */
  private Object seedWideFanOut() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    var dave = graph.addVertex(T.label, "Person", "name", "Dave");
    var erin = graph.addVertex(T.label, "Person", "name", "Erin");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", carol);
    alice.addEdge("knows", dave);
    bob.addEdge("knows", erin);
    graph.tx().commit();
    return alice.id();
  }

  /**
   * Seeds Alice -knows-> Bob -knows-> Carol with every vertex carrying both {@code name} and {@code
   * age}, so {@code values("name")} and {@code values("age")} each yield a full row set and differ
   * only in which property key they project.
   */
  private void seedKnowsChainWithAges() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    var carol = graph.addVertex(T.label, "Person", "name", "Carol", "age", 50);
    alice.addEdge("knows", bob);
    bob.addEdge("knows", carol);
    graph.tx().commit();
  }

  /** Seeds Alice -knows-> Bob -knows-> Carol. */
  private void seedKnowsChain() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    bob.addEdge("knows", carol);
    graph.tx().commit();
  }

  /**
   * Runs the shape with translator on and off; asserts boundary engagement and multiset equality.
   * Under {@link Recognition#RECOGNIZED_MULTI_PLAN} the on-side boundary must be a {@link
   * MultiPlanMatchStep}.
   */
  private void assertEquivalent(
      String scenario, Recognition expected, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var original =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      setTranslatorEnabled(true);
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var multiPlanOn = countMultiPlanSteps(onAdmin.getSteps());
      var onIds = drainSortedIds(onAdmin);

      setTranslatorEnabled(false);
      var offAdmin = traversalSupplier.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offIds = drainSortedIds(offAdmin);

      if (expected != Recognition.DECLINED) {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must engage exactly one boundary step")
            .isEqualTo(1);
        if (expected == Recognition.RECOGNIZED_MULTI_PLAN) {
          assertThat(multiPlanOn)
              .as(scenario + " (translator on) must splice MultiPlanMatchStep")
              .isEqualTo(1);
        }
        assertThat(onIds)
            .as(scenario + ": RECOGNIZED fixture must return a non-empty result")
            .isNotEmpty();
      } else {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must decline — no boundary step")
            .isEqualTo(0);
        assertThat(multiPlanOn).isEqualTo(0);
      }
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onIds)
          .as(scenario + ": translator-on and translator-off result multisets must match")
          .isEqualTo(offIds);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Asserts the shape translates to a multi-plan boundary. Slicing tests read row counts rather than
   * comparing against native, so without this the whole assertion set would still pass if the shape
   * quietly declined to the native pipeline.
   */
  private void assertMultiPlanEngaged(Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    setTranslatorEnabled(true);
    var admin = traversalSupplier.get().asAdmin();
    admin.applyStrategies();
    assertThat(countMultiPlanSteps(admin.getSteps()))
        .as("shape must splice exactly one MultiPlanMatchStep")
        .isEqualTo(1);
  }

  private void setTranslatorEnabled(boolean enabled) {
    session
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
  }

  /**
   * Drains a strategy-applied traversal to a sorted RID multiset. Count terminators emit a Long, so
   * those are stringified directly; element paths use vertex ids.
   */
  private static List<String> drainSortedIds(GraphTraversal.Admin<?, ?> admin) {
    var results = admin.toList();
    return results.stream()
        .map(
            v -> {
              if (v instanceof Vertex vertex) {
                return vertex.id().toString();
              }
              return String.valueOf(v);
            })
        .sorted()
        .toList();
  }

  private static List<String> sortedIds(List<?> results) {
    return results.stream().map(v -> ((Vertex) v).id().toString()).sorted().toList();
  }

  private static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }

  private static int countMultiPlanSteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof MultiPlanMatchStep<?, ?>) {
        count++;
      }
    }
    return count;
  }
}
