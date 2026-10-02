package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Before;
import org.junit.Test;

/**
 * The detached-child step grammar crossed with all nine combinator positions. Every cell checks
 * boundary engagement and sorted row multisets against the native traversal. The fixture includes
 * zero, one and two edges, a missing property and a two-hop return to an outer label.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DetachedChildGrammarEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);
  private Vertex linked;
  private Vertex target;

  private record Case(String name, Supplier<GraphTraversal> child, boolean emptyPossible) {
  }

  private record Position(String name,
      BiFunction<GraphTraversal, GraphTraversal, GraphTraversal> wrap,
      boolean forwards) {
  }

  @Before
  public void seed() {
    graph.addVertex(T.label, "Source", "name", "Isolated");
    linked = graph.addVertex(T.label, "Source", "name", "Linked");
    var doubleSource = graph.addVertex(T.label, "Source", "name", "Double");
    target = graph.addVertex(T.label, "Target", "v", 1);
    var other = graph.addVertex(T.label, "Target", "v", 2);
    linked.addEdge("link", target, "weight", 2);
    doubleSource.addEdge("link", target, "weight", 3);
    doubleSource.addEdge("link", other, "weight", 2);
    target.addEdge("link", other);
    target.addEdge("link", linked);
    graph.tx().commit();
  }

  private List<Position> positions() {
    return List.of(
        new Position("where", (g, c) -> g.where(c), true),
        new Position("filter", (g, c) -> g.filter(c), true),
        new Position("not", (g, c) -> g.not(c), true),
        new Position("and", (g, c) -> g.and(c, __.has("name")), true),
        new Position("or", (g, c) -> g.or(c, __.has("name", "zzz")), false),
        new Position("nested", (g, c) -> g.where(__.where(c)), true),
        new Position("deep", (g, c) -> g.where(__.where(__.where(c))), true),
        new Position("not-nested", (g, c) -> g.not(__.where(c)), false),
        new Position("and-nested", (g, c) -> g.and(__.where(c), __.has("name")), true));
  }

  private void check(Case shape, Position position, boolean admitted) {
    GremlinPlanCache.instance(session).invalidate();
    // A nested positive check cannot be negated by the NOT recogniser's captured-pattern branch.
    boolean nestedNot = position.name.equals("not")
        && (shape.name.equals("nested-where") || shape.name.equals("nested-and"));
    var expected = admitted && position.forwards && !nestedNot
        ? Recognition.RECOGNIZED : Recognition.DECLINED;
    // An isolated source keeps the NOT arm nonempty. A missing property keeps positive arms empty.
    var cardinality = shape.emptyPossible ? Cardinality.MAY_BE_EMPTY : Cardinality.NON_EMPTY;
    support.assertEquivalent(shape.name + "/" + position.name, expected, cardinality,
        TranslatorEquivalenceSupport::sortedIdsOrValues,
        () -> position.wrap.apply(graph.traversal()
            .withStrategies(StandardOrderSemanticsStrategy.instance())
            .V().hasLabel("Source").as("x"), shape.child.get()));
  }

  /** Accepted hop, target-filter, presence, edge and nested-wrapper chains retain their rows. */
  @Test
  public void supportedChains_translateAtEveryConjunctivePosition() {
    var accepted = List.of(
        new Case("hop", () -> __.out("link"), false),
        new Case("target-has", () -> __.out("link").has("v", 1), false),
        new Case("presence", () -> __.out("link").has("v"), false),
        new Case("missing-presence", () -> __.out("link").has("missing"), true),
        new Case("hasNot", () -> __.out("link").hasNot("v"), true),
        new Case("edge-has", () -> __.outE("link").has("weight", 2).inV(), false),
        new Case("barrier-positive", () -> __.out("link").barrier(1).out("link"), false),
        new Case("nested-where", () -> __.where(__.out("link")), false),
        new Case("nested-and", () -> __.and(__.out("link"), __.has("name")), true),
        new Case("not-target", () -> __.out("link").not(__.has("v", 1)), false),
        new Case("and-target", () -> __.out("link").and(__.has("v"), __.has("v", 1)), false));
    for (var shape : accepted) {
      for (var position : positions()) {
        check(shape, position, true);
      }
    }
  }

  /** Terminal existence-preserving steps retain translation in NOT and positive EXISTS positions. */
  @Test
  public void terminalSteps_andLabelledHops_translateWithEqualMultisets() {
    var accepted = List.of(
        new Case("dedup", () -> __.out("link").dedup(), false),
        new Case("limit-one", () -> __.out("link").limit(1), false),
        new Case("limit-two", () -> __.out("link").limit(2), false),
        new Case("values", () -> __.out("link").values("v"), false),
        new Case("properties", () -> __.out("link").properties("v"), false),
        new Case("missing-values", () -> __.out("link").values("missing"), true),
        new Case("missing-properties", () -> __.out("link").properties("missing"), true),
        new Case("labelled-hop", () -> __.out("link").as("m").out("link"), false),
        // Linked reaches itself through Target. This keeps the equality arm nonempty.
        new Case("where-outer-label", () -> __.out("link").out("link")
            .where(P.eq("x")), false));
    for (var shape : accepted) {
      for (var position : positions()) {
        check(shape, position, true);
      }
    }
    // A single labelled hop is independently admitted in a NOT child.
    check(new Case("terminal-labelled-hop", () -> __.out("link").as("m"), false),
        positions().get(2), true);
    assertThat(graph.traversal().V().hasLabel("Source").has("name", "Double").count().next())
        .isEqualTo(1);
  }

  /** An independent source, payload-changing step, zero barrier or label reader always declines. */
  @Test
  public void invalidChains_declineBeforeForwardingAtEveryDepth() {
    var declined = List.of(
        new Case("independent-hop", () -> __.V().out("link"), true),
        new Case("independent-id-hop", () -> __.V(linked.id()).out("link"), true),
        new Case("nested-independent", () -> __.V().where(__.out("link")), true),
        new Case("nested-id", () -> __.V(linked.id()).where(__.out("link")), true),
        new Case("project-hop", () -> __.out("link").project("r").by("v"), true),
        new Case("project-after-nested", () -> __.where(__.out("link"))
            .project("r").by("name").order().by("r"), true),
        new Case("zero-barrier", () -> __.out("link").barrier(0).out("link"), true),
        new Case("zero-barrier-end", () -> __.out("link").barrier(0), true),
        new Case("nested-zero", () -> __.where(__.out("link")).barrier(0), true),
        new Case("nonterminal-limit", () -> __.out("link").limit(1).out("link"), true),
        new Case("limit-zero", () -> __.out("link").limit(0), true),
        new Case("skip", () -> __.out("link").skip(1), true),
        new Case("dedup-by", () -> __.out("link").dedup().by("v"), true),
        new Case("label-reader", () -> __.out("link").as("m").select("m"), true),
        new Case("label-predicate", () -> __.out("link").as("m")
            .where(P.eq("m")), true),
        new Case("nested-select", () -> __.where(__.out("link")).select("x"), true),
        new Case("source-only", () -> __.V().has("name", "Linked"), true),
        new Case("source-id", () -> __.V(linked.id()).has("name", "Linked"), true),
        new Case("project-order", () -> __.project("r").by("name").order().by("r"), true));
    for (var shape : declined) {
      for (var position : positions()) {
        check(shape, position, false);
      }
    }
  }

  /** An independent source inside a positive child's where wrapper must decline before capture. */
  @Test
  public void nestedIndependentSource_declinesBeforePositiveCapture() {
    check(new Case("nested-source-with-id", () -> __.V(target.id())
        .where(__.out("link")), false), positions().getFirst(), false);
  }

  /** Both polarities keep translating when the terminal limit is finite or unbounded. */
  @Test
  public void terminalMaximumLimit_translatesInExistsAndNotWithNativeRows() {
    for (long limit : List.of(Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
      var shape = new Case("limit-" + limit, () -> __.out("link").limit(limit), false);
      check(shape, positions().getFirst(), true);
      check(shape, positions().get(2), true);
    }
  }
}
