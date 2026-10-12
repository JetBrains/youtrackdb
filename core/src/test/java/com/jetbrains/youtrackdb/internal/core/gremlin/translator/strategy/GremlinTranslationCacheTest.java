package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsPlacement;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedExpandSliceListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedHopStage;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.PBiPredicate;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.Text;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ConstantTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Translation-cache (pre-walk shape key) and copy-on-open behaviour: a second {@code has()} value
 * splices the cached template, a declining shape is cached as decline, schema invalidation clears
 * the map, and {@code getPlan()} before the first open is the shared template.
 */
@Category(SequentialTest.class)
public class GremlinTranslationCacheTest extends GraphBaseTest {

  private Object previousAscending;
  private Object previousDescending;

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(this::graphSession);

  @Before
  public void enableTranslator() {
    previousAscending = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC.getValue();
    previousDescending = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC.getValue();
    support.setTranslatorEnabled(true);
    GremlinPlanCache.instance(graphSession()).invalidate();
  }

  @After
  public void restoreNullPlacementGlobals() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC.setValue(previousAscending);
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC.setValue(previousDescending);
  }

  /** A barrier(0) never reuses the positive-capacity barrier plan, in children or at top level. */
  @Test
  public void zeroCapacityBarrier_afterCachedPositiveBarrier_declinesInBothPositions() {
    var source = graph.addVertex(T.label, "Source", "name", "Linked");
    graph.addVertex(T.label, "Source", "name", "Isolated");
    source.addEdge("link", graph.addVertex(T.label, "Target"));
    graph.tx().commit();

    support.assertEquivalent("warm child barrier",
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED,
        TranslatorEquivalenceSupport.Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.out("link").barrier(1)));
    support.assertEquivalent("zero child barrier",
        TranslatorEquivalenceSupport.Recognition.DECLINED,
        TranslatorEquivalenceSupport.Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().hasLabel("Source")
            .where(__.out("link").barrier(0)));
    support.assertEquivalent("warm top barrier",
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED,
        TranslatorEquivalenceSupport.Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().hasLabel("Source").barrier(1).out("link"));
    support.assertEquivalent("zero top barrier", TranslatorEquivalenceSupport.Recognition.DECLINED,
        TranslatorEquivalenceSupport.Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().hasLabel("Source").barrier(0).out("link"));
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
   * IS1-like prefix, IS4-like equality and IS5-like filtered hop starts preserve native rows.
   * Alternating literals use one real cold walk and three warm boundaries with no disagreement.
   */
  @Test
  public void ordinaryEqualityStarts_reuseTemplatesWithFreshRowsAndLayouts() {
    graphSession().createVertexClass("OrdinaryPerson")
        .createProperty("name", PropertyType.STRING);
    var alice = graph.addVertex(T.label, "OrdinaryPerson", "name", "alice", "age", 30);
    var bob = graph.addVertex(T.label, "OrdinaryPerson", "name", "bob", "age", 40);
    alice.addEdge("ordinaryKnows", bob);
    bob.addEdge("ordinaryKnows", alice);
    graph.tx().commit();
    for (String kind : List.of("prefix", "equality", "hop", "barrierPrefix")) {
      GremlinPlanCache.instance(graphSession()).invalidate();
      var cache = GremlinPlanCache.instance(graphSession());
      long hits = cache.getTranslationHits();
      var walks = new AtomicInteger();
      var failures = new ArrayList<String>();
      var strategy = countingStrategy(walks, failures::add);
      for (boolean first : List.of(true, false, true, false)) {
        Supplier<GraphTraversal<?, ?>> shape = () -> {
          var start = graph.traversal().V().hasLabel("OrdinaryPerson");
          return switch (kind) {
            case "prefix" -> start.has("name", TextP.startingWith(first ? "al" : "bo"))
                .values("name");
            case "equality" -> start.has("age", first ? 30 : 40).values("name");
            case "hop" -> start.has("age", first ? 30 : 40).out("ordinaryKnows")
                .values("name");
            case "barrierPrefix" -> start.barrier(2)
                .has("name", TextP.startingWith(first ? "al" : "bo")).values("name");
            default -> throw new IllegalArgumentException(kind);
          };
        };
        var admin = shape.get().asAdmin();
        var extraction = GremlinStepWalker.extractShape(admin, graphSession());
        var walk = GremlinStepWalker.production().walk(admin);
        assertThat(walk).isNotNull();
        assertThat(extraction.hasContributions()).isEqualTo(walk.hasContributions());
        assertThat(extraction.bindings()).isEqualTo(walk.inputParameters());
        var expected = List.of(first != kind.equals("hop") ? "alice" : "bob");
        assertThat(runNativeHop(shape)).containsExactlyElementsOf(expected);
        assertThat(runCountedHop(shape, strategy)).containsExactlyElementsOf(expected);
      }
      assertThat(walks.get()).as(kind + " skips the walker on every warm call").isEqualTo(1);
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 3);
      assertThat(failures).isEmpty();
    }
  }

  /** Missing and non-vertex equality labels still decline, cache the decline and return no rows. */
  @Test
  public void provisionalMissingAndEdgeLabels_keepCachedDeclines() {
    graphSession().createEdgeClass("OrdinaryEdge");
    graph.tx().commit();
    for (String label : List.of("OrdinaryMissing", "OrdinaryEdge")) {
      GremlinPlanCache.instance(graphSession()).invalidate();
      var walks = new AtomicInteger();
      var failures = new ArrayList<String>();
      var strategy = countingStrategy(walks, failures::add);
      for (int age : List.of(30, 40, 30, 40)) {
        Supplier<GraphTraversal<?, ?>> shape = () -> graph.traversal().V().hasLabel(label)
            .has("age", age).values("name");
        var admin = shape.get().asAdmin();
        strategy.apply(admin);
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
        support.withTranslator(false, () -> assertThat(admin.toList()).isEmpty());
        assertThat(runNativeHop(shape)).isEmpty();
      }
      assertThat(walks.get()).isEqualTo(1);
      assertThat(failures).isEmpty();
    }
  }

  /**
   * Equal total slot counts must not let two prefix leaves exchange their range and strict slots.
   * Each cache-warming order is checked against native rows through both strategy paths.
   */
  @Test
  public void prefixLeavesWithSwappedSlotLayouts_doNotShareWarmPlans() {
    var person = graphSession().createVertexClass("PrefixPerson");
    person.createProperty("name", PropertyType.STRING);
    person.createProperty("other", PropertyType.STRING);
    for (var name : List.of("", "al", "alice", "alpine", "am", "be", "beryl", "beta",
        "bf", "zeta")) {
      graph.addVertex(T.label, "PrefixPerson", "name", name, "other", name);
    }
    graph.tx().commit();

    for (var kind : List.of("and", "twoKeys", "single")) {
      var finite = prefixShape(kind, "al", kind.equals("single") ? null : "");
      var swapped = prefixShape(kind, "", kind.equals("single") ? null : "be");
      var otherFinite = prefixShape(kind, "be", kind.equals("single") ? null : "");
      assertThat(shapeKey(finite.asAdmin())).as(kind + " different prefix layouts")
          .isNotEqualTo(shapeKey(swapped.asAdmin()));
      assertThat(shapeKey(finite.asAdmin())).as(kind + " equal finite layouts")
          .isEqualTo(shapeKey(otherFinite.asAdmin()));
      if (!kind.equals("single")) {
        assertThat(shapeKey(() -> prefixShape(kind, "al", "be")))
            .isEqualTo(shapeKey(() -> prefixShape(kind, "be", "al")));
      }
      var expectedFinite = List.of("al", "alice", "alpine");
      var expectedSwapped = kind.equals("single")
          ? List.of("", "al", "alice", "alpine", "am", "be", "beryl", "beta", "bf", "zeta")
          : List.of("be", "beryl", "beta");
      for (boolean direct : List.of(false, true)) {
        for (boolean swapFirst : List.of(false, true)) {
          GremlinPlanCache.instance(graphSession()).invalidate();
          for (boolean swappedValue : List.of(swapFirst, !swapFirst, swapFirst, !swapFirst)) {
            var first = swappedValue ? "" : "al";
            var second = kind.equals("single") ? null : (swappedValue ? "be" : "");
            var expected = swappedValue ? expectedSwapped : expectedFinite;
            assertThat(runPrefixShape(kind, first, second, false, direct))
                .as(kind + " native " + swappedValue).containsExactlyElementsOf(expected);
            assertThat(runPrefixShape(kind, first, second, true, direct))
                .as(kind + " translated " + swappedValue + " direct " + direct)
                .containsExactlyElementsOf(expected);
          }
        }
      }
    }
  }

  /** An all-maximum-code-point prefix has no upper bound, including under negation. */
  @Test
  public void maximumCodePointPrefix_usesTheStrictSlotLayout() {
    var person = graphSession().createVertexClass("PrefixPerson");
    person.createProperty("name", PropertyType.STRING);
    person.createProperty("other", PropertyType.STRING);
    var max = "\uDBFF\uDFFF";
    for (var name : List.of("a", "ab", "b", max, max + "a")) {
      graph.addVertex(T.label, "PrefixPerson", "name", name, "other", name);
    }
    graph.addVertex(T.label, "PrefixPerson", "name", "a", "other", max);
    graph.addVertex(T.label, "PrefixPerson", "name", max, "other", "a");
    graph.tx().commit();

    assertThat(shapeKey(() -> graph.traversal().V().hasLabel("PrefixPerson")
        .has("name", TextP.startingWith(max))))
        .isEqualTo(shapeKey(() -> graph.traversal().V().hasLabel("PrefixPerson")
            .has("name", TextP.startingWith(""))));
    assertThat(shapeKey(() -> graph.traversal().V().hasLabel("PrefixPerson")
        .has("name", TextP.notStartingWith(max))))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().hasLabel("PrefixPerson")
            .has("name", TextP.notStartingWith("a"))));
    assertThat(shapeKey(() -> prefixShape("twoKeys", "a", max)))
        .isNotEqualTo(shapeKey(() -> prefixShape("twoKeys", max, "a")));
    for (boolean direct : List.of(false, true)) {
      for (boolean maxFirst : List.of(false, true)) {
        GremlinPlanCache.instance(graphSession()).invalidate();
        for (boolean maxOnFirst : List.of(maxFirst, !maxFirst, maxFirst, !maxFirst)) {
          var first = maxOnFirst ? max : "a";
          var second = maxOnFirst ? "a" : max;
          var expected = maxOnFirst ? List.of(max) : List.of("a");
          assertThat(runPrefixShape("twoKeys", first, second, false, direct))
              .containsExactlyElementsOf(expected);
          assertThat(runPrefixShape("twoKeys", first, second, true, direct))
              .containsExactlyElementsOf(expected);
        }
      }
    }
  }

  /** Alternating ordered filters reuse one template while returning each literal's own rows. */
  @Test
  public void orderedHopFilters_bindFreshLiteralsOnBothSlicePlacements() {
    seedColourHop();
    for (boolean sourceSlice : List.of(false, true)) {
      var red = colourHop("red", sourceSlice).asAdmin();
      var blue = colourHop("blue", sourceSlice).asAdmin();
      var redWalk = GremlinStepWalker.production().walk(red);
      var blueWalk = GremlinStepWalker.production().walk(blue);
      assertThat(redWalk).isNotNull();
      assertThat(blueWalk).isNotNull();
      assertThat(GremlinPlanFingerprint.fingerprint(redWalk.inputs(), redWalk.shaping()))
          .isEqualTo(GremlinPlanFingerprint.fingerprint(blueWalk.inputs(), blueWalk.shaping()));
      var extraction = GremlinStepWalker.extractShape(red, graphSession());
      assertThat(extraction.hasContributions()).isEqualTo(redWalk.hasContributions());
      assertThat(OrderedFilterBinding.fromExtraction(redWalk.shaping(), extraction))
          .isNotNull();
      for (boolean direct : List.of(false, true)) {
        for (boolean blueFirst : List.of(false, true)) {
          GremlinPlanCache.instance(graphSession()).invalidate();
          var cache = GremlinPlanCache.instance(graphSession());
          String first = blueFirst ? "blue" : "red";
          String second = blueFirst ? "red" : "blue";
          assertThat(runColourHop(first, sourceSlice, false, direct))
              .containsExactlyElementsOf(expectedColour(first));
          assertThat(runColourHop(first, sourceSlice, true, direct))
              .containsExactlyElementsOf(expectedColour(first));
          long hits = cache.getTranslationHits();
          assertThat(runColourHop(second, sourceSlice, true, direct))
              .containsExactlyElementsOf(expectedColour(second));
          assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
          assertThat(runColourHop(first, sourceSlice, true, direct))
              .containsExactlyElementsOf(expectedColour(first));
        }
      }
    }
  }

  /** Ordinary layouts contain no native snapshots, and order-only routes need no merge analysis. */
  @Test
  public void absenceControls_skipOrderedExtractionAndPreserveOrdinaryShaping() {
    seedColourHop();
    List<Supplier<GraphTraversal<?, ?>>> controls = List.of(
        () -> graph.traversal().V().hasLabel("ColourSource").has("group", "red").values("group"),
        () -> graph.traversal().V().hasLabel("ColourSource").out("colourEdge")
            .order().by("name").limit(2).values("name"),
        () -> graph.traversal().V().hasLabel("ColourSource").order().by("rank")
            .limit(2).values("group"),
        () -> graph.traversal().V().hasLabel("ColourSource")
            .coalesce(__.values("group"), __.values("rank")));
    for (var shape : controls) {
      GremlinPlanCache.instance(graphSession()).invalidate();
      var analyses = new AtomicInteger();
      var observer = new TraverserMergeFacts.AnalysisObserver(analyses::incrementAndGet);
      var admin = observed(shape, observer);
      var extraction = GremlinStepWalker.extractShape(admin, graphSession());
      assertThat(analyses.get()).as("real extraction performs no merge analysis").isZero();
      assertThat(extraction.key()).doesNotContain("sk:6:absent", "lp:6:absent");
      assertThat(extraction.orderedExpandAbsent()).isTrue();
      assertThat(extraction.nativeOperands()).isEmpty();
      assertThat(StepStreamCursor.requiresOrderedBarriers(admin.getSteps())).isFalse();
      var walk = GremlinStepWalker.production().walk(admin);
      assertThat(analyses.get()).as("real cold walker performs no merge analysis").isZero();
      if (walk != null) {
        assertThat(OrderedFilterBinding.fresh(walk.shaping())).isSameAs(walk.shaping());
        assertThat(OrderedFilterBinding.unbound(walk.shaping())).isSameAs(walk.shaping());
        assertThat(OrderedFilterBinding.fromExtraction(walk.shaping(), extraction))
            .isSameAs(walk.shaping());
      }
      var walks = new AtomicInteger();
      var strategy = countingStrategy(walks);
      for (int call = 0; call < 2; call++) {
        var compiled = observed(shape, observer);
        strategy.apply(compiled);
        assertThat(analyses.get()).as("cold and warm strategy paths skip merge analysis").isZero();
        assertThat(walks.get()).as("the warm call skips the real walker").isEqualTo(1);
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(compiled))
            .isEqualTo(walk == null ? 0 : 1);
        support.withTranslator(false, () -> assertThat(compiled.toList().stream()
            .map(String::valueOf).toList()).containsExactlyElementsOf(runNativeHop(shape)));
      }
    }
    // The same seam must observe real ordered demand on extraction, cold walk, and warm lookup.
    var analyses = new AtomicInteger();
    var observer = new TraverserMergeFacts.AnalysisObserver(analyses::incrementAndGet);
    Supplier<GraphTraversal<?, ?>> ordered = () -> colourHop("red", false);
    GremlinPlanCache.instance(graphSession()).invalidate();
    GremlinStepWalker.extractShape(observed(ordered, observer), graphSession());
    assertThat(analyses.getAndSet(0)).as("ordered extraction analyzes once").isEqualTo(1);
    assertThat(GremlinStepWalker.production().walk(observed(ordered, observer))).isNotNull();
    assertThat(analyses.getAndSet(0)).as("ordered walker analyzes once").isEqualTo(1);
    var walks = new AtomicInteger();
    var strategy = countingStrategy(walks);
    for (int call = 0; call < 2; call++) {
      var compiled = observed(ordered, observer);
      strategy.apply(compiled);
      assertThat(analyses.getAndSet(0)).as("cold extraction and walk, then warm extraction")
          .isEqualTo(call == 0 ? 2 : 1);
      assertThat(walks.get()).isEqualTo(1);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(compiled)).isEqualTo(1);
      assertThat(compiled.toList().stream().map(String::valueOf).toList())
          .containsExactly("A1", "A2");
    }
    var nested = graph.traversal().V().where(
        __.order().by("rank").barrier(3).out("colourEdge").limit(2)).asAdmin();
    assertThat(GremlinStepWalker.extractShape(nested, graphSession()).orderedExpandAbsent())
        .isFalse();
    assertThatThrownBy(() -> GremlinToMatchStrategy.assertAgreement("test disagreement"))
        .isInstanceOf(AssertionError.class).hasMessage("test disagreement");
  }

  private static Traversal.Admin<?, ?> observed(Supplier<GraphTraversal<?, ?>> shape,
      TraverserMergeFacts.AnalysisObserver observer) {
    var admin = shape.get().asAdmin();
    var strategies = admin.getStrategies().clone();
    strategies.addStrategies(observer);
    admin.setStrategies(strategies);
    return admin;
  }

  /** Identity ranges on either side of a hop keep MATCH filters unless an effective slice follows. */
  @Test
  public void identityRanges_matchNativeRowsAndSpliceBeforeAndAfterHop() {
    seedColourHop();
    for (String spelling : List.of("skip", "range", "maximum")) {
      for (boolean beforeHop : List.of(false, true)) {
        for (boolean effectiveSlice : List.of(false, true)) {
          GremlinPlanCache.instance(graphSession()).invalidate();
          var walks = new AtomicInteger();
          var strategy = countingStrategy(walks);
          for (String colour : List.of("red", "blue", "red", "blue")) {
            Supplier<GraphTraversal<?, ?>> shape = () -> {
              var ordered = graph.traversal().V().hasLabel("ColourSource").has("group", colour)
                  .order().by("rank");
              var hop = (beforeHop ? identityRange(ordered, spelling) : ordered)
                  .out("colourEdge").hasLabel("ColourTarget").barrier(3);
              var filtered = (beforeHop ? hop : identityRange(hop, spelling))
                  .has("colour", colour);
              return (effectiveSlice ? filtered.limit(2) : filtered).values("name");
            };
            var admin = shape.get().asAdmin();
            var extraction = GremlinStepWalker.extractShape(admin, graphSession());
            var walk = GremlinStepWalker.production().walk(admin);
            assertThat(walk).isNotNull();
            assertThat(extraction.hasContributions()).isEqualTo(walk.hasContributions());
            assertThat(extraction.hasContributions().get(1).slots())
                .as("the separated target label contributes no parameter slots").isEmpty();
            assertThat(extraction.nativeOperands()).hasSize(effectiveSlice ? 2 : 0);
            assertThat(runCountedHop(shape, strategy))
                .containsExactlyElementsOf(runNativeHop(shape));
          }
          assertThat(walks.get()).as("identity ranges allow three real warm splices").isEqualTo(1);
        }
      }
    }
  }

  private static <S, E> GraphTraversal<S, E> identityRange(GraphTraversal<S, E> traversal,
      String spelling) {
    return switch (spelling) {
      case "skip" -> traversal.skip(0);
      case "range" -> traversal.range(0, -1);
      case "maximum" -> traversal.range(0, Long.MAX_VALUE);
      default -> throw new IllegalArgumentException(spelling);
    };
  }

  /** A later barrier label does not prevent an unsliced ordered hop from translating to one boundary. */
  @Test
  public void unslicedOrderedHopWithLaterBarrierLabel_matchesNativeAndUsesOneBoundary() {
    seedColourHop();
    Supplier<GraphTraversal<?, ?>> shape = () -> graph.traversal().V().hasLabel("ColourSource")
        .order().by("rank").barrier(3).out("colourEdge").as("z").barrier(3).values("name");
    var walks = new AtomicInteger();
    var strategy = countingStrategy(walks);
    for (int call = 0; call < 2; call++) {
      assertThat(runCountedHop(shape, strategy)).containsExactlyElementsOf(runNativeHop(shape));
    }
    assertThat(walks.get()).as("one cold walk and one warm splice").isEqualTo(1);
    support.assertEquivalent("unsliced ordered hop with a later barrier label",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        rows -> rows.stream().map(String::valueOf).toList(), shape);
  }

  /** Mixed MATCH, label-only native, and property native filters align on real alternating splices. */
  @Test
  public void orderedOperandPositions_includeZeroSlotsButExcludeMatchFilters() {
    seedColourHop();
    for (boolean sourceSlice : List.of(false, true)) {
      var walks = new AtomicInteger();
      var strategy = countingStrategy(walks);
      GremlinPlanCache.instance(graphSession()).invalidate();
      for (String colour : List.of("red", "blue", "red", "blue")) {
        Supplier<
            org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape =
                () -> {
                  var ordered = graph.traversal().V().hasLabel("ColourSource").has("group", colour)
                      .order().by("rank");
                  var hop = sourceSlice ? ordered.limit(2).out("colourEdge")
                      : ordered.out("colourEdge");
                  var filtered = hop.hasLabel("ColourTarget").barrier(3).has("colour", colour);
                  return (sourceSlice ? filtered : filtered.limit(2)).values("name");
                };
        var admin = shape.get().asAdmin();
        var extraction = GremlinStepWalker.extractShape(admin, graphSession());
        var walk = GremlinStepWalker.production().walk(admin);
        assertThat(extraction.hasContributions()).isEqualTo(walk.hasContributions());
        assertThat(extraction.nativeOperands()).hasSize(2);
        assertThat(extraction.hasContributions().get(1).slots()).isEmpty();
        var missing = new GremlinShapeExtractor.Extraction(extraction.key(), extraction.bindings(),
            true, extraction.hasContributions(), List.of(), false);
        assertThat(OrderedFilterBinding.fromExtraction(walk.shaping(), missing)).isNull();
        assertThat(OrderedFilterBinding.fromExtraction(walk.shaping(), extraction)).isNotNull();
        assertThat(runCountedHop(shape, strategy)).containsExactlyElementsOf(runNativeHop(shape));
      }
      assertThat(walks.get()).as("three actual warm splices for mixed destinations").isEqualTo(1);
    }
  }

  /** Merged duplicate labels use the ordinary first group but retain all deferred native groups. */
  @Test
  public void repeatedLabelContainers_matchWalkLayoutsAndSpliceAlternatingValues() {
    seedColourHop();
    for (boolean ordered : List.of(false, true)) {
      GremlinPlanCache.instance(graphSession()).invalidate();
      var walks = new AtomicInteger();
      var strategy = countingStrategy(walks);
      for (String colour : List.of("red", "blue", "red", "blue")) {
        Supplier<
            org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape =
                () -> {
                  var source = graph.traversal().V();
                  var filtered = (ordered ? source.hasLabel("ColourSource").order().by("rank")
                      .out("colourEdge") : source).hasLabel("ColourTarget");
                  Traversal.Admin<?, ?> filterAdmin = filtered.asAdmin();
                  ((HasStep<?>) filterAdmin.getEndStep()).addHasContainer(
                      new HasContainer(T.label.getAccessor(), P.eq("ColourTarget")));
                  var result = filtered.has("colour", colour);
                  return (ordered ? result.limit(2) : result).values("name");
                };
        var admin = shape.get().asAdmin();
        var extraction = GremlinStepWalker.extractShape(admin, graphSession());
        var walk = GremlinStepWalker.production().walk(admin);
        assertThat(walk).isNotNull();
        assertThat(extraction.hasContributions()).isEqualTo(walk.hasContributions());
        assertThat(runCountedHop(shape, strategy).stream().sorted().toList())
            .containsExactlyElementsOf(runNativeHop(shape).stream().sorted().toList());
      }
      assertThat(walks.get()).as("identical merged labels must allow three warm splices")
          .isEqualTo(1);
    }
  }

  /** Missing operands or extra operands against stored stages trigger a real walk with native rows. */
  @Test
  public void mismatchedOrderedStageCounts_fallBackToFreshWalkWithoutDecline() {
    seedColourHop();
    for (boolean extraStage : List.of(false, true)) {
      GremlinPlanCache.instance(graphSession()).invalidate();
      Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> first =
              () -> colourHop("red", false);
      assertThat(runCountedHop(first, countingStrategy(new AtomicInteger())))
          .containsExactly("A1", "A2");
      var extraction = GremlinStepWalker.extractShape(first.get().asAdmin(), graphSession());
      var stored = (GremlinTranslationTemplate.Translate) GremlinPlanCache.getTranslation(
          extraction.key(), graphSession());
      assertThat(stored).isNotNull();
      var expand = (OrderedExpandSliceListShapingOp) stored.shaping().listShapingOps().getFirst();
      var stages = new ArrayList<>(expand.stages());
      var filter = stages.stream().filter(OrderedHopStage.Filter.class::isInstance)
          .findFirst().orElseThrow();
      if (extraStage) {
        stages.add(stages.indexOf(filter), filter);
      } else {
        stages.remove(filter);
      }
      var wrongShaping = stored.shaping().withListShapingOps(
          List.of(new OrderedExpandSliceListShapingOp(stages)));
      assertThat(OrderedFilterBinding.fromExtraction(wrongShaping, extraction)).isNull();
      GremlinPlanCache.putTranslation(extraction.key(), new GremlinTranslationTemplate.Translate(
          stored.planTemplate(), stored.boundaryAlias(), stored.outputType(), stored.returnClass(),
          wrongShaping, stored.bindingCount(), stored.hasContributions()), graphSession());
      var walks = new AtomicInteger();
      var failures = new ArrayList<String>();
      var strategy = countingStrategy(walks, failures::add);
      Supplier<org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> next =
          () -> colourHop("blue", false);
      assertThat(runCountedHop(next, strategy)).containsExactlyElementsOf(runNativeHop(next));
      assertThat(walks.get()).as("binding disagreement must invoke the translator").isEqualTo(1);
      assertThat(failures).containsExactly("Translation cache binding agreement failed");
      assertThat(runCountedHop(first, strategy)).containsExactlyElementsOf(runNativeHop(first));
      assertThat(walks.get()).as("the fresh template must splice on the next call").isEqualTo(1);
    }
  }

  /** Prefix flattening can build ordered union arms that decline without a top-level disagreement. */
  @Test
  public void discardedOrderedUnionFork_doesNotTriggerAbsenceAgreement() {
    seedColourHop();
    Supplier<org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape =
        () -> graph.traversal().V().hasLabel("ColourSource").order().by("rank").limit(2)
            .union(__.out("colourEdge"), __.out("colourEdge"));
    var extraction = GremlinStepWalker.extractShape(shape.get().asAdmin(), graphSession());
    assertThat(extraction.orderedExpandAbsent()).isTrue();
    assertThat(extraction.complete()).as("union end markers keep this shape incomplete").isFalse();
    var fork = GremlinStepWalker.production().walk(graph.traversal().V()
        .hasLabel("ColourSource").order().by("rank").limit(2).out("colourEdge").asAdmin());
    assertThat(fork).isNotNull();
    assertThat(OrderedFilterBinding.hasOrderedExpand(fork.shaping())).isTrue();
    var walks = new AtomicInteger();
    var strategy = countingStrategy(walks);
    for (int call = 0; call < 2; call++) {
      var admin = shape.get().asAdmin();
      strategy.apply(admin);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
      support.withTranslator(false, () -> assertThat(TranslatorEquivalenceSupport.sortedIds(
          admin.toList())).containsExactlyElementsOf(TranslatorEquivalenceSupport.sortedIds(
              shape.get().toList())));
    }
    assertThat(walks.get()).as("incomplete union shapes walk without caching the decline")
        .isEqualTo(2);
  }

  /** Coalesce absence remains a cached native decline with one real walk for alternating values. */
  @Test
  public void absentCoalesce_reusesDeclineWithoutOrderedOperandsOrRepeatedWalks() {
    seedColourHop();
    var walks = new AtomicInteger();
    var strategy = countingStrategy(walks);
    for (String colour : List.of("red", "blue", "red", "blue")) {
      Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape =
              () -> graph.traversal().V().hasLabel("ColourSource").has("group", colour)
                  .coalesce(__.values("group"), __.values("rank"));
      var admin = shape.get().asAdmin();
      var extraction = GremlinStepWalker.extractShape(admin, graphSession());
      assertThat(extraction.orderedExpandAbsent()).isTrue();
      assertThat(extraction.nativeOperands()).isEmpty();
      strategy.apply(admin);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
      support.withTranslator(false, () -> assertThat(admin.toList().stream()
          .map(String::valueOf).toList()).containsExactlyElementsOf(runNativeHop(shape)));
    }
    assertThat(walks.get()).as("all three warm calls must skip the walker").isEqualTo(1);
  }

  /** A missed extraction route still derives sack and live-path facts once and never publishes. */
  @Test
  public void missedAbsence_runsFreshOrderedPlanWithoutPublishingWrongIdentity() {
    var hub = graph.addVertex(T.label, "MissedHub", "name", "Hub");
    for (String name : List.of("One", "Two")) {
      graph.addVertex(T.label, "MissedSource", "name", name).addEdge("missedHub", hub);
    }
    for (String name : List.of("A", "B")) {
      hub.addEdge("missedChild", graph.addVertex(T.label, "MissedTarget", "name", name));
    }
    graph.tx().commit();
    var suppliers = new AtomicInteger();
    Supplier<
        org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> ordered =
            () -> graph.traversal().withSack(() -> {
              suppliers.incrementAndGet();
              return 1;
            })
                .V().hasLabel("MissedSource").as("before").out("missedHub")
                .order().by("name").as("sorted").barrier(3).out("missedChild")
                .limit(3).values("name");
    var admin = ordered.get().asAdmin();
    var ctx = new WalkerContext(true, false, graphSession().getSchema());
    ctx.setWalkedTraversal(admin);
    assertThat(ctx.hasComputedMergeFacts()).isFalse();
    var facts = ctx.traverserMergeFacts();
    ctx.userLabelToAlias.put("before", "source");
    ctx.userLabelToAlias.put("sorted", "hub");
    ctx.recordOrderByCapture("hub", true);
    ctx.orderedBarrierStage(new NoOpBarrierStep<>(admin, 3));
    ctx.orderedSourceMergeKey("hub");
    assertThat(facts.labelsAtBarriers()).hasSize(1);
    assertThat(ctx.traverserMergeFacts()).as("successive ordered demands share one analysis")
        .isSameAs(facts);
    assertThat(facts.sackGated()).isTrue();
    assertThat(facts.labelsAtOrder()).contains("before", "sorted");
    assertThat(suppliers.get()).as("compilation must not call user sack suppliers").isZero();
    var failures = new ArrayList<String>();
    var strategy = new GremlinToMatchStrategy(
        traversal -> GremlinStepWalker.production().walk(ordered.get().asAdmin()),
        GremlinToMatchStrategy::buildPlan, true, failures::add);
    Supplier<org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> absent =
        () -> graph.traversal().withSack(1).V().hasLabel("MissedSource");
    String key = GremlinStepWalker.extractShape(absent.get().asAdmin(), graphSession()).key();
    var expected = runNativeHop(ordered);
    assertThat(expected).as("live paths keep the two converging sources distinct").hasSize(3);
    assertThat(expected.getFirst()).isEqualTo(expected.getLast()).isNotEqualTo(expected.get(1));
    assertThat(runCountedHop(absent, strategy)).containsExactlyElementsOf(expected);
    assertThat(failures).containsExactly("Ordered expand contradicts extraction absence");
    assertThat(GremlinPlanCache.getTranslation(key, graphSession())).isNull();
  }

  /** Empty and finite deferred prefixes use their own layouts on either side of the slice. */
  @Test
  public void deferredPrefixes_matchNativeRowsAndReuseEachWarmLayout() {
    graphSession().createVertexClass("PrefixHopTarget")
        .createProperty("name", PropertyType.STRING);
    int rank = 0;
    for (String name : List.of("", "alice", "bob")) {
      var source = graph.addVertex(T.label, "PrefixHopSource", "rank", rank++);
      source.addEdge("prefixHopEdge", graph.addVertex(T.label, "PrefixHopTarget", "name", name));
    }
    graph.tx().commit();
    for (boolean sourceSlice : List.of(false, true)) {
      for (boolean typed : List.of(false, true)) {
        for (boolean emptyFirst : List.of(false, true)) {
          GremlinPlanCache.instance(graphSession()).invalidate();
          var walks = new AtomicInteger();
          var strategy = countingStrategy(walks);
          var prefixes = emptyFirst ? List.of("", "al", "", "bo", "al")
              : List.of("al", "", "bo", "al", "");
          for (String prefix : prefixes) {
            Supplier<
                org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?,
                    ?>> shape = () -> prefixHop(prefix, sourceSlice, typed);
            var expected = prefix.isEmpty() ? List.of("", "alice", "bob")
                : prefix.equals("al") ? List.of("alice") : List.of("bob");
            assertThat(runNativeHop(shape)).containsExactlyElementsOf(expected);
            assertThat(runCountedHop(shape, strategy)).containsExactlyElementsOf(expected);
          }
          assertThat(walks.get()).as("one walk per empty and finite slot layout")
              .isEqualTo(2);
        }
      }
    }
  }

  /** Child-local hasLabel re-typing narrows the gate for the next property HasStep in extract and walk. */
  @Test
  public void capturedChildRetype_keepsExtractedAndWalkedLayoutsInSync() {
    var parent = graphSession().createVertexClass("ProbePerson");
    graphSession().getSchema().createClass("ProbeSub", parent)
        .createProperty("name", PropertyType.STRING);
    for (String name : List.of("alice", "bob")) {
      graph.addVertex(T.label, "ProbeSub", "name", name);
    }
    graph.tx().commit();
    for (String kind : List.of("where", "and")) {
      GremlinPlanCache.instance(graphSession()).invalidate();
      var walks = new AtomicInteger();
      var strategy = countingStrategy(walks);
      for (String prefix : List.of("al", "bo", "al", "bo")) {
        Supplier<
            org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape =
                () -> capturedRetypeShape(kind, prefix);
        var admin = shape.get().asAdmin();
        var extracted = GremlinStepWalker.extractShape(admin, graphSession());
        var walked = GremlinStepWalker.production().walk(admin);
        assertThat(walked).as(kind).isNotNull();
        assertThat(extracted.hasContributions()).as(kind + " contribution positions and roles")
            .isEqualTo(walked.hasContributions());
        var expected = prefix.equals("al") ? List.of("alice") : List.of("bob");
        assertThat(runNativeHop(shape)).containsExactlyElementsOf(expected);
        assertThat(runCountedHop(shape, strategy)).containsExactlyElementsOf(expected);
      }
      assertThat(walks.get()).as(kind + " should splice all three warm child templates")
          .isEqualTo(1);
    }
  }

  /** After a hop the gate resets to V, then a target hasLabel narrows it for later property filters. */
  @Test
  public void capturedHopPrefixes_matchWalkAndReuseOneFiniteOrStrictLayout() {
    var parent = graphSession().createVertexClass("ProbePerson");
    graphSession().getSchema().createClass("ProbeSub", parent)
        .createProperty("name", PropertyType.STRING);
    graphSession().createEdgeClass("probeEdge").createProperty("flag", PropertyType.STRING);
    String max = "\uDBFF\uDFFF";
    for (String name : List.of("alice", "bob", max, max + "a")) {
      var source = graph.addVertex(T.label, "ProbePerson", "name", "source-" + name);
      source.addEdge("probeEdge", graph.addVertex(T.label, "ProbeSub", "name", name),
          "flag", name);
    }
    graph.addVertex(T.label, "ProbePerson", "name", "unlinked");
    graph.tx().commit();

    for (String kind : List.of("vertex", "edge", "where", "and", "or", "not",
        "sameStep", "edgeFlag", "noHop")) {
      for (List<String> prefixes : List.of(List.of("al", "bo", "al", "bo"),
          List.of("", max, "", max))) {
        GremlinPlanCache.instance(graphSession()).invalidate();
        var walks = new AtomicInteger();
        var strategy = countingStrategy(walks);
        String firstSql = null;
        for (String prefix : prefixes) {
          Supplier<
              org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape =
                  () -> capturedHopPrefix(kind, prefix);
          var admin = shape.get().asAdmin();
          var extracted = GremlinStepWalker.extractShape(admin, graphSession());
          var walked = GremlinStepWalker.production().walk(admin);
          assertThat(walked).as(kind + " remains translated").isNotNull();
          assertThat(extracted.hasContributions()).as(kind + " slot positions and roles")
              .isEqualTo(walked.hasContributions());
          assertThat(extracted.hasContributions()).as(kind + " has a bound prefix")
              .anySatisfy(c -> assertThat(c.slots())
                  .extracting(HasBindingContext.Slot::role)
                  .contains(GremlinPredicateAdapter.SlotRole.PREFIX));
          // A fingerprint includes every emitted MATCH filter and path item, not extraction slots.
          var sql = GremlinPlanFingerprint.fingerprint(walked.inputs(), walked.shaping());
          assertThat(sqlDigest(sql)).as(kind + " SQL fingerprint after hop-gate reset")
              .isEqualTo(baselineSqlDigest(kind, prefix));
          if (firstSql == null) {
            firstSql = sql;
          } else {
            assertThat(sql).as(kind + " unchanged MATCH SQL within a slot layout")
                .isEqualTo(firstSql);
          }
          var nativeRows = runNativeHop(shape).stream().sorted().toList();
          assertThat(runCountedHop(shape, strategy).stream().sorted().toList())
              .as(kind + " native rows for prefix " + prefix)
              .containsExactlyElementsOf(nativeRows);
        }
        assertThat(walks.get()).as(kind + " one plan per finite or strict layout")
            .isEqualTo(1);
      }
    }
  }

  private org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>
      capturedHopPrefix(String kind, String prefix) {
    var source = graph.traversal().V().hasLabel("ProbePerson");
    return switch (kind) {
      case "vertex" -> source.not(__.out("probeEdge").hasLabel("ProbeSub")
          .barrier(2).has("name", TextP.startingWith(prefix))).values("name");
      case "edge" -> source.not(__.outE("probeEdge").inV().hasLabel("ProbeSub")
          .barrier(2).has("name", TextP.startingWith(prefix))).values("name");
      case "where" -> source.not(__.out("probeEdge")
          .where(__.has("name", TextP.startingWith(prefix)))).values("name");
      case "and" -> source.not(__.out("probeEdge")
          .and(__.has("name", TextP.startingWith(prefix)), __.hasLabel("ProbeSub")))
          .values("name");
      case "or" -> source.not(__.out("probeEdge")
          .or(__.has("name", TextP.startingWith(prefix)),
              __.has("name", TextP.startingWith(prefix))))
          .values("name");
      case "not" -> source.not(__.out("probeEdge")
          .not(__.has("name", TextP.startingWith(prefix)))).values("name");
      case "sameStep" -> source.not(__.out("probeEdge")
          .hasLabel("ProbeSub").has("name", TextP.startingWith(prefix))).values("name");
      case "edgeFlag" -> source.not(__.outE("probeEdge")
          .has("flag", TextP.startingWith(prefix)).inV()
          .has("name", TextP.startingWith(prefix))).values("name");
      case "noHop" -> source.not(__.has("name", TextP.startingWith(prefix)))
          .values("name");
      default -> throw new IllegalArgumentException(kind);
    };
  }

  /**
   * SHA-256 of the MATCH fingerprint after hop-target gates reset to V (then optional hasLabel
   * narrow). Finite vs strict prefix layouts differ when the target class declares STRING.
   */
  private static String baselineSqlDigest(String kind, String prefix) {
    boolean strict = prefix.isEmpty() || prefix.equals("\uDBFF\uDFFF");
    return switch (kind) {
      case "and" -> "b34ef6b6a61107f4562d1ce8f433dceb26cef1f1f58c5c9a2d7111627904aee3";
      case "vertex", "sameStep" -> strict
          ? "b34ef6b6a61107f4562d1ce8f433dceb26cef1f1f58c5c9a2d7111627904aee3"
          : "c599b9fdb84cdeca0e0c914841d7dfa1be1a7f6da7447b9195a6c0b039768873";
      case "edge" -> strict
          ? "bd0241a42ce98f172aac38e67963c47b44a9d6fbf4841b73f1508d6baee9b978"
          : "8c4b208ab759b73ffcc67c7e26a7addb510ac3b631058c582cb421d63b51a661";
      case "where" -> "483af2a5eb5ccecd8ea9038e81c0a4150e02d433635e06ccba0eba9fef368374";
      case "or" -> "7c6a3090276ddcac62036c460ec512cd6280ead216a1e808e59132881696ca47";
      case "not" -> "352e4e7b2392573fa81bc305c1b7eb718829af056467c65e35560bff72988ec0";
      case "edgeFlag" -> strict
          ? "1ad622a95455b3974a59fe8482785c4b8e9faf76d0de95ddc19f3b4fe3823055"
          : "874b372ac1ddf4ce974e3bae55df586fb709aeb7cedfaf2885f37365b78765b1";
      case "noHop" -> "7f7b7262a2fe009a63c758a34eded6a61a0487aa4e2ab7150cb0fe7419628aa3";
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private static String sqlDigest(String fingerprint) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of().formatHex(digest.digest(
          fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  /** Union arms use independent plan contexts even when each child narrows its own label. */
  @Test
  public void unionChildRetype_keepsNativeRowsAcrossDifferentPrefixes() {
    var parent = graphSession().createVertexClass("ProbePerson");
    graphSession().getSchema().createClass("ProbeSub", parent)
        .createProperty("name", PropertyType.STRING);
    for (String name : List.of("alice", "bob")) {
      graph.addVertex(T.label, "ProbeSub", "name", name);
    }
    graph.tx().commit();
    var walks = new AtomicInteger();
    var strategy = countingStrategy(walks);
    for (String prefix : List.of("al", "bo", "al", "bo")) {
      Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> union =
              () -> graph.traversal().V().hasLabel("ProbePerson")
                  .union(__.hasLabel("ProbeSub").barrier(2)
                      .has("name", TextP.startingWith(prefix)),
                      __.hasLabel("ProbeSub").barrier(2)
                          .has("name", TextP.startingWith(prefix)));
      assertThat(runCountedHop(union, strategy)).hasSize(2);
      support.assertEquivalent("union child retype " + prefix,
          Recognition.RECOGNIZED_MULTI_PLAN, Cardinality.NON_EMPTY,
          TranslatorEquivalenceSupport::sortedIds,
          () -> graph.traversal().V().hasLabel("ProbePerson")
              .union(__.hasLabel("ProbeSub").barrier(2)
                  .has("name", TextP.startingWith(prefix)),
                  __.hasLabel("ProbeSub").barrier(2)
                      .has("name", TextP.startingWith(prefix))));
    }
    assertThat(walks.get()).as("union forks keep independent walks without outer templates")
        .isEqualTo(4);
  }

  /** Union forks keep the generic gate after their own hops and build independently per call. */
  @Test
  public void unionHopBranches_keepTheirIndependentPostHopGate() {
    var parent = graphSession().createVertexClass("ProbePerson");
    graphSession().getSchema().createClass("ProbeSub", parent)
        .createProperty("name", PropertyType.STRING);
    for (String name : List.of("alice", "bob")) {
      var source = graph.addVertex(T.label, "ProbePerson", "name", "source-" + name);
      source.addEdge("probeEdge", graph.addVertex(T.label, "ProbeSub", "name", name));
    }
    graph.tx().commit();
    var walks = new AtomicInteger();
    var strategy = countingStrategy(walks);
    for (String prefix : List.of("al", "bo", "al", "bo")) {
      Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape =
              () -> graph.traversal().V().hasLabel("ProbePerson")
                  .union(__.out("probeEdge").has("name", TextP.startingWith(prefix)),
                      __.out("probeEdge").has("name", TextP.startingWith(prefix)));
      var admin = shape.get().asAdmin();
      var extracted = GremlinStepWalker.extractShape(admin, graphSession());
      assertThat(extracted.hasContributions().stream()
          .filter(c -> c.slots().stream().anyMatch(
              s -> s.role() == GremlinPredicateAdapter.SlotRole.PREFIX)))
          .as("each union arm resets its own post-hop boundary")
          .allSatisfy(c -> assertThat(c.context().gateClasses()).isEmpty());
      assertThat(runCountedHop(shape, strategy).stream().sorted().toList())
          .containsExactlyElementsOf(runNativeHop(shape).stream().sorted().toList());
    }
    assertThat(walks.get()).as("the union carrier has no outer translation template")
        .isEqualTo(4);
  }

  /** Edge filters use the edge-label gate for both extraction and walk slot roles. */
  @Test
  public void edgePrefixGate_keepsExtractedAndWalkedLayoutsInSync() {
    graphSession().createEdgeClass("probeEdge").createProperty("flag", PropertyType.STRING);
    var source = graph.addVertex(T.label, "ProbePerson", "name", "source");
    source.addEdge("probeEdge", graph.addVertex(T.label, "ProbePerson", "name", "alice"),
        "flag", "yes");
    graph.tx().commit();
    Supplier<org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape =
        () -> graph.traversal().V().hasLabel("ProbePerson").outE("probeEdge")
            .has("flag", TextP.startingWith("y")).inV().values("name");
    var admin = shape.get().asAdmin();
    var extraction = GremlinStepWalker.extractShape(admin, graphSession());
    var walk = GremlinStepWalker.production().walk(admin);
    assertThat(walk).isNotNull();
    assertThat(extraction.hasContributions()).isEqualTo(walk.hasContributions());
    assertThat(runNativeHop(shape)).containsExactly("alice");
    assertThat(runCountedHop(shape, countingStrategy(new AtomicInteger())))
        .containsExactly("alice");
  }

  private org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>
      capturedRetypeShape(String kind, String prefix) {
    var source = graph.traversal().V().hasLabel("ProbePerson");
    if (kind.equals("and")) {
      return source.and(__.hasLabel("ProbeSub").barrier(2)
          .has("name", TextP.startingWith(prefix)), __.hasLabel("ProbeSub"))
          .values("name");
    }
    return source.where(__.hasLabel("ProbeSub").barrier(2)
        .has("name", TextP.startingWith(prefix))).values("name");
  }

  /** An overridden regex operator keeps its native instance on both cold and warm walks. */
  @Test
  public void customRegexPredicate_doesNotRebuildOrCacheAnOverriddenTest() {
    var source = graph.addVertex(T.label, "UnsafeHopSource", "rank", 1);
    source.addEdge("unsafeHopEdge", graph.addVertex(T.label, "UnsafeHopTarget", "name", "hit"));
    graph.tx().commit();
    for (boolean sourceSlice : List.of(false, true)) {
      GremlinPlanCache.instance(graphSession()).invalidate();
      var walks = new AtomicInteger();
      var strategy = countingStrategy(walks);
      var cache = GremlinPlanCache.instance(graphSession());
      long hits = cache.getTranslationHits();
      for (int run = 0; run < 4; run++) {
        // A plain regex with this pattern rejects "hit". The override must accept it.
        Text.RegexPredicate custom = new Text.RegexPredicate("^absent$", false) {
          @Override
          public boolean test(String input, String pattern) {
            return true;
          }
        };
        var predicate = new TextP(custom, "^absent$");
        assertThat(NativeHasOperands.cacheable(predicate)).isFalse();
        assertThat(NativeHasOperands.regexOnly(predicate)).isFalse();
        Supplier<
            org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape =
                () -> unsafeHop("name", predicate, sourceSlice);
        assertThat(runNativeHop(shape)).containsExactly("hit");
        assertThat(runCountedHop(shape, strategy)).containsExactly("hit");
      }
      assertThat(walks.get()).isEqualTo(4);
      assertThat(cache.getTranslationHits()).isEqualTo(hits);
    }
  }

  /** Subclass test() and non-List Collection equality survive cold and warm attempts. */
  @Test
  public void unsafeDeferredPredicates_preserveNativeTestAndCollectionType() {
    var source = graph.addVertex(T.label, "UnsafeHopSource", "rank", 1);
    source.addEdge("unsafeHopEdge", graph.addVertex(T.label, "UnsafeHopTarget", "name", "hit",
        "tags", List.of("red", "blue")));
    graph.tx().commit();
    for (boolean sourceSlice : List.of(false, true)) {
      for (boolean unsafeFirst : List.of(false, true)) {
        GremlinPlanCache.instance(graphSession()).invalidate();
        for (boolean unsafe : List.of(unsafeFirst, !unsafeFirst, unsafeFirst)) {
          Supplier<org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?,
              ?>> custom = () -> unsafeHop("name", new P<Object>(Compare.eq, "absent") {
                @Override
                public boolean test(Object value) {
                  return unsafe;
                }
              }, sourceSlice);
          var nativeCustom = runNativeHop(custom);
          assertThat(nativeCustom).containsExactlyElementsOf(unsafe ? List.of("hit") : List.of());
          assertThat(runCountedHop(custom, countingStrategy(new AtomicInteger())))
              .isEqualTo(nativeCustom);
          var operand = unsafe ? new ArrayDeque<>(List.of("red", "blue"))
              : new ArrayList<>(List.of("red", "blue"));
          Supplier<org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?,
              ?>> collection = () -> unsafeHop("tags", P.eq(operand), sourceSlice);
          var nativeCollection = runNativeHop(collection);
          assertThat(runCountedHop(collection, countingStrategy(new AtomicInteger())))
              .isEqualTo(nativeCollection);
        }
      }
    }
  }

  /** Two bound steps built before either runs cannot share the first step's filter instance. */
  @Test
  public void orderedHopFilters_twoBoundTraversalsKeepIndependentOperands() {
    seedColourHop();
    var first = colourHop("red", false).asAdmin();
    var second = colourHop("blue", false).asAdmin();
    GremlinToMatchStrategy.instance().apply(first);
    GremlinToMatchStrategy.instance().apply(second);
    assertThat(TranslatorEquivalenceSupport.countBoundarySteps(first)).isEqualTo(1);
    assertThat(TranslatorEquivalenceSupport.countBoundarySteps(second)).isEqualTo(1);
    assertThat(second.toList().stream().map(String::valueOf).toList())
        .containsExactly("B1", "B2");
    assertThat(first.toList().stream().map(String::valueOf).toList())
        .containsExactly("A1", "A2");
    first.reset();
    assertThat(first.toList().stream().map(String::valueOf).toList())
        .containsExactly("A1", "A2");
  }

  /** A bound ordered-hop filter keeps its own literal after closing and rearming its boundary. */
  @Test
  public void orderedHopBoundFilter_closedBoundaryReopensWithOwnOperand() throws Exception {
    seedColourHop();
    var red = colourHop("red", false).asAdmin();
    GremlinToMatchStrategy.instance().apply(red);
    assertThat(red.toList().stream().map(String::valueOf).toList())
        .containsExactly("A1", "A2");
    red.close();
    red.reset();
    assertThat(red.toList().stream().map(String::valueOf).toList())
        .containsExactly("A1", "A2");
  }

  /** Equal counts with exchanged slot roles cannot splice the stored template. */
  @Test
  public void equalCountWithDifferentSlotRoles_isNotTheSameCacheLayout() {
    var context = HasBindingContext.forVertex(List.of(), "V", false,
        HasBindingContext.Destination.ORDERED_FILTER);
    var first = List.of(new HasBindingContext.Contribution(context, List.of(
        new HasBindingContext.Slot(0, GremlinPredicateAdapter.SlotRole.PREFIX),
        new HasBindingContext.Slot(1, GremlinPredicateAdapter.SlotRole.OPERAND))));
    var swapped = List.of(new HasBindingContext.Contribution(context, List.of(
        new HasBindingContext.Slot(0, GremlinPredicateAdapter.SlotRole.OPERAND),
        new HasBindingContext.Slot(1, GremlinPredicateAdapter.SlotRole.PREFIX))));
    assertThat(first.getFirst().slots()).hasSameSizeAs(swapped.getFirst().slots());
    assertThat(GremlinToMatchStrategy.matchingLayout(first, swapped)).isFalse();
    assertThat(GremlinToMatchStrategy.matchingLayout(first, first)).isTrue();
    assertThat(GremlinToMatchStrategy.matchingLayout(first,
        List.of(new HasBindingContext.Contribution(
            HasBindingContext.forVertex(List.of("Other"), "V", false,
                HasBindingContext.Destination.ORDERED_FILTER),
            first.getFirst().slots()))))
        .isFalse();
  }

  /** A same-size wrong-role stored template is a miss, followed by a translated fresh walk. */
  @Test
  public void mismatchedStoredSlotRoles_fallBackToFreshWalkWithoutDecline() {
    graphSession().createVertexClass("MismatchPerson")
        .createProperty("name", PropertyType.STRING);
    graph.addVertex(T.label, "MismatchPerson", "name", "alice");
    graph.addVertex(T.label, "MismatchPerson", "name", "bob");
    graph.tx().commit();
    Supplier<org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> first =
        () -> graph.traversal().V().hasLabel("MismatchPerson")
            .has("name", TextP.startingWith("al")).values("name");
    assertThat(runCountedHop(first, countingStrategy(new AtomicInteger())))
        .containsExactly("alice");
    var key = GremlinStepWalker.extractShape(first.get().asAdmin(), graphSession()).key();
    var stored = (GremlinTranslationTemplate.Translate) GremlinPlanCache.getTranslation(
        key, graphSession());
    assertThat(stored).isNotNull();
    var original = stored.hasContributions();
    var contribution = original.getLast();
    assertThat(contribution.slots()).hasSize(2);
    var slots = new ArrayList<>(contribution.slots());
    slots.set(0, new HasBindingContext.Slot(slots.getFirst().containerIndex(),
        GremlinPredicateAdapter.SlotRole.OPERAND));
    var wrong = new ArrayList<>(original);
    wrong.set(wrong.size() - 1, new HasBindingContext.Contribution(contribution.context(), slots));
    GremlinPlanCache.putTranslation(key, new GremlinTranslationTemplate.Translate(
        stored.planTemplate(), stored.boundaryAlias(), stored.outputType(),
        stored.returnClass(), stored.shaping(), stored.bindingCount(), wrong), graphSession());
    var walks = new AtomicInteger();
    Supplier<org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> next =
        () -> graph.traversal().V().hasLabel("MismatchPerson")
            .has("name", TextP.startingWith("bo")).values("name");
    var failures = new ArrayList<String>();
    var nativeRows = runNativeHop(next);
    assertThat(runCountedHop(next, countingStrategy(walks, failures::add)))
        .containsExactlyElementsOf(nativeRows);
    assertThat(walks.get()).as("equal count but wrong slot role must walk again").isEqualTo(1);
    assertThat(failures).containsExactly("Translation cache binding agreement failed");
  }

  /** A compiled regex must be reconstructed, not have only its P value replaced. */
  @Test
  public void orderedHopRegexAlternation_rebuildsCompiledPatternsOnWarmHit() {
    seedColourHop();
    assertThat(shapeKey(() -> graph.traversal().V().hasLabel("ColourSource")
        .order().by("rank").out("colourEdge")
        .has("name", TextP.regex("^A")).limit(2).values("name")))
        .isEqualTo(shapeKey(() -> graph.traversal().V().hasLabel("ColourSource")
            .order().by("rank").out("colourEdge")
            .has("name", TextP.regex("^B")).limit(2).values("name")));
    var cache = GremlinPlanCache.instance(graphSession());
    for (boolean blueFirst : List.of(false, true)) {
      cache.invalidate();
      String first = blueFirst ? "^B" : "^A";
      String second = blueFirst ? "^A" : "^B";
      assertThat(regexHop(first)).containsExactlyElementsOf(regexExpected(first));
      long hits = cache.getTranslationHits();
      assertThat(regexHop(second)).containsExactlyElementsOf(regexExpected(second));
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
      assertThat(regexHop(first)).containsExactlyElementsOf(regexExpected(first));
    }
  }

  private List<String> regexHop(String pattern) {
    var admin = graph.traversal().V().hasLabel("ColourSource").order().by("rank")
        .out("colourEdge").has("name", TextP.regex(pattern)).limit(2).values("name")
        .asAdmin();
    return applyAdmin(admin).stream().map(String::valueOf).toList();
  }

  private static List<String> regexExpected(String pattern) {
    return pattern.equals("^A") ? List.of("A1", "A2") : List.of("B1", "B2");
  }

  /** A native ci property still reads this invocation's value after an op-template hit. */
  @Test
  public void orderedHopCollatedProperty_rebindsOnWarmHit() {
    graphSession().createVertexClass("CollatedTarget").createProperty("nickname",
        PropertyType.STRING).setCollate("ci");
    var first = graph.addVertex(T.label, "CollatedSource", "rank", 1);
    var second = graph.addVertex(T.label, "CollatedSource", "rank", 2);
    first.addEdge("collatedEdge", graph.addVertex(T.label, "CollatedTarget", "name", "A",
        "nickname", "Alice"));
    second.addEdge("collatedEdge", graph.addVertex(T.label, "CollatedTarget", "name", "B",
        "nickname", "Bob"));
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(graphSession());
    for (boolean bobFirst : List.of(false, true)) {
      cache.invalidate();
      String firstValue = bobFirst ? "BOB" : "ALICE";
      String secondValue = bobFirst ? "alice" : "bob";
      assertThat(collatedHop(firstValue)).containsExactly(bobFirst ? "B" : "A");
      long hits = cache.getTranslationHits();
      assertThat(collatedHop(secondValue)).containsExactly(bobFirst ? "A" : "B");
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    }
  }

  private List<String> collatedHop(String value) {
    return apply(() -> graph.traversal().V().hasLabel("CollatedSource").order().by("rank")
        .out("collatedEdge").has("nickname", value).limit(1).values("name"))
        .stream().map(String::valueOf).toList();
  }

  /** MATCH slots before and after an unsliced deferred flush retain their exact positions. */
  @Test
  public void unslicedFlushAcrossBarrier_rebindsPreAndPostHopSlotsOnWarmHit() {
    seedColourHop();
    for (var target : graph.traversal().V().hasLabel("ColourTarget").toList()) {
      target.addEdge("afterColour", graph.addVertex(T.label, "ColourResult", "name",
          target.value("name") + "-result"));
    }
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(graphSession());
    for (boolean blueFirst : List.of(false, true)) {
      cache.invalidate();
      String first = blueFirst ? "blue" : "red";
      String second = blueFirst ? "red" : "blue";
      var extracted = GremlinStepWalker.extractShape(unslicedFlush(first).asAdmin(),
          graphSession());
      assertThat(extracted.bindings().get(0)).isEqualTo(first);
      assertThat(extracted.bindings().get(1)).isEqualTo(first);
      assertThat(extracted.bindings().get(2)).isEqualTo("none");
      assertThat(extracted.bindings().get(3)).isEqualTo(first.equals("red")
          ? "A1-result" : "B1-result");
      assertThat(runUnslicedFlush(first)).containsExactly(first.equals("red")
          ? "A1-result" : "B1-result");
      long hits = cache.getTranslationHits();
      assertThat(runUnslicedFlush(second)).containsExactly(second.equals("red")
          ? "A1-result" : "B1-result");
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    }
  }

  private org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>
      unslicedFlush(String colour) {
    return graph.traversal().V().hasLabel("ColourSource").has("group", colour)
        .order().by("rank").out("colourEdge").has("colour", colour).barrier(3)
        .has("name", P.neq("none")).out("afterColour")
        .has("name", colour.equals("red") ? "A1-result" : "B1-result")
        .values("name");
  }

  private List<String> runUnslicedFlush(String colour) {
    return applyAdmin(unslicedFlush(colour).asAdmin()).stream().map(String::valueOf).toList();
  }

  /** Has predicates inside both union arms remain independent over repeated plan builds. */
  @Test
  public void unionHasFilters_alternateWarmValuesWithoutSharingWrongChildParameters() {
    seedColourHop();
    for (String colour : List.of("red", "blue", "red", "blue")) {
      support.assertEquivalent("union " + colour, Recognition.RECOGNIZED_MULTI_PLAN,
          Cardinality.NON_EMPTY, TranslatorEquivalenceSupport::sortedIds,
          () -> graph.traversal().V().hasLabel("ColourTarget")
              .union(__.has("colour", colour), __.has("colour", colour)));
    }
  }

  private org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>
      prefixHop(String prefix, boolean sourceSlice, boolean typed) {
    var ordered = graph.traversal().V().hasLabel("PrefixHopSource").order().by("rank");
    var hop = sourceSlice ? ordered.limit(3).out("prefixHopEdge")
        : ordered.out("prefixHopEdge");
    if (typed) {
      hop = hop.hasLabel("PrefixHopTarget");
    }
    var filtered = hop.has("name", TextP.startingWith(prefix));
    return sourceSlice ? filtered.values("name") : filtered.limit(3).values("name");
  }

  private org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>
      unsafeHop(String key, P<?> predicate, boolean sourceSlice) {
    var ordered = graph.traversal().V().hasLabel("UnsafeHopSource").order().by("rank");
    if (sourceSlice) {
      return ordered.limit(1).out("unsafeHopEdge").has(key, predicate).values("name");
    }
    return ordered.out("unsafeHopEdge").has(key, predicate).limit(1).values("name");
  }

  private GremlinToMatchStrategy countingStrategy(AtomicInteger walks) {
    return countingStrategy(walks, GremlinToMatchStrategy::assertAgreement);
  }

  private GremlinToMatchStrategy countingStrategy(AtomicInteger walks,
      java.util.function.Consumer<String> agreementFailure) {
    return new GremlinToMatchStrategy(new GremlinToMatchStrategy.TraversalTranslator() {
      @Override
      public GremlinToMatchTranslator.TranslationResult translate(Traversal.Admin<?, ?> traversal) {
        walks.incrementAndGet();
        return GremlinToMatchTranslator.translate(traversal);
      }

      @Override
      public GremlinToMatchTranslator.TranslationResult translate(Traversal.Admin<?, ?> traversal,
          Boolean includesMissing,
          com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement placements,
          Boolean polymorphic) {
        walks.incrementAndGet();
        return GremlinToMatchTranslator.translate(traversal, includesMissing, placements,
            polymorphic);
      }
    }, GremlinToMatchStrategy::buildPlan, true, agreementFailure);
  }

  private List<String> runNativeHop(Supplier<
      org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape) {
    var previous = support.translatorEnabled();
    support.setTranslatorEnabled(false);
    try {
      var admin = shape.get().asAdmin();
      admin.applyStrategies();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
      return admin.toList().stream().map(String::valueOf).toList();
    } finally {
      support.setTranslatorEnabled(previous);
    }
  }

  private List<String> runCountedHop(Supplier<
      org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape,
      GremlinToMatchStrategy strategy) {
    var previous = support.translatorEnabled();
    support.setTranslatorEnabled(true);
    try {
      var admin = shape.get().asAdmin();
      strategy.apply(admin);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isEqualTo(1);
      return admin.toList().stream().map(String::valueOf).toList();
    } finally {
      support.setTranslatorEnabled(previous);
    }
  }

  private void seedColourHop() {
    for (int i = 1; i <= 4; i++) {
      var red = i <= 2;
      var source = graph.addVertex(T.label, "ColourSource", "rank", i,
          "group", red ? "red" : "blue");
      var target = graph.addVertex(T.label, "ColourTarget", "name",
          (red ? "A" : "B") + (red ? i : i - 2), "colour", red ? "red" : "blue");
      source.addEdge("colourEdge", target);
    }
    graph.tx().commit();
  }

  private static List<String> expectedColour(String colour) {
    return colour.equals("red") ? List.of("A1", "A2") : List.of("B1", "B2");
  }

  private org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?> colourHop(
      String colour, boolean sourceSlice) {
    var ordered = graph.traversal().V().hasLabel("ColourSource").order().by("rank");
    if (sourceSlice) {
      return ordered.limit(4).out("colourEdge").has("colour", colour).values("name");
    }
    return ordered.out("colourEdge").has("colour", colour).limit(2).values("name");
  }

  private List<String> runColourHop(String colour, boolean sourceSlice,
      boolean translated, boolean direct) {
    var previous = support.translatorEnabled();
    support.setTranslatorEnabled(translated);
    try {
      var traversal = colourHop(colour, sourceSlice).asAdmin();
      if (direct && translated) {
        GremlinToMatchStrategy.instance().apply(traversal);
      } else {
        traversal.applyStrategies();
      }
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal))
          .isEqualTo(translated ? 1 : 0);
      return traversal.toList().stream().map(String::valueOf).toList();
    } finally {
      support.setTranslatorEnabled(previous);
    }
  }

  private org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<Vertex, Vertex>
      prefixShape(String kind, String first, String second) {
    var traversal = graph.traversal().V().hasLabel("PrefixPerson");
    if (kind.equals("and")) {
      return traversal.has("name", TextP.startingWith(first).and(TextP.startingWith(second)));
    }
    if (kind.equals("twoKeys")) {
      return traversal.has("name", TextP.startingWith(first))
          .has("other", TextP.startingWith(second));
    }
    return traversal.has("name", TextP.startingWith(first));
  }

  private List<String> runPrefixShape(
      String kind, String first, String second, boolean translated, boolean direct) {
    var previous = support.translatorEnabled();
    support.setTranslatorEnabled(translated);
    try {
      var traversal = prefixShape(kind, first, second).asAdmin();
      if (direct && translated) {
        GremlinToMatchStrategy.instance().apply(traversal);
      } else {
        traversal.applyStrategies();
      }
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal))
          .isEqualTo(translated ? 1 : 0);
      return sortedNames(traversal.toList());
    } finally {
      support.setTranslatorEnabled(previous);
    }
  }

  /** Stage order and the explicit barrier window size each distinguish translation keys. */
  @Test
  public void orderedHopBarrierPositionsAndSizes_haveDistinctWarmCacheEntries() {
    var first = graph.addVertex(T.label, "BarrierSource", "name", "1");
    var second = graph.addVertex(T.label, "BarrierSource", "name", "2");
    var third = graph.addVertex(T.label, "BarrierSource", "name", "3");
    var a = graph.addVertex(T.label, "BarrierChild", "name", "A");
    var b = graph.addVertex(T.label, "BarrierChild", "name", "B");
    first.addEdge("barrierEdge", a);
    second.addEdge("barrierEdge", b);
    third.addEdge("barrierEdge", a);
    graph.tx().commit();

    var after = shapeKey(() -> graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").out("barrierEdge").limit(2).barrier(3));
    var beforeTwo = shapeKey(() -> graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").out("barrierEdge").barrier(2).limit(2));
    var beforeThree = shapeKey(() -> graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").out("barrierEdge").barrier(3).limit(2));
    assertThat(after).isNotEqualTo(beforeThree);
    assertThat(beforeTwo).isNotEqualTo(beforeThree);
    var cache = GremlinPlanCache.instance(graphSession());
    for (int i = 0; i < 2; i++) {
      // In the second pass, run the barrier sizes in reverse order against warm entries.
      if (i == 0) {
        assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
            .order().by("name").out("barrierEdge").barrier(2).limit(2))))
            .containsExactly("A", "B");
      }
      assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
          .order().by("name").out("barrierEdge").barrier(3).limit(2))))
          .containsExactly("A", "A");
      assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
          .order().by("name").out("barrierEdge").limit(2).barrier(3))))
          .containsExactly("A", "B");
      if (i == 1) {
        assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
            .order().by("name").out("barrierEdge").barrier(2).limit(2))))
            .containsExactly("A", "B");
      }
    }
    // Another test can cap the global cache at two entries. Warm each shape immediately before
    // checking its own hit, while keeping the cross-shape alternating result checks above.
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").out("barrierEdge").limit(2).barrier(3))))
        .containsExactly("A", "B");
    var hits = cache.getTranslationHits();
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").out("barrierEdge").limit(2).barrier(3))))
        .containsExactly("A", "B");
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").out("barrierEdge").barrier(3).limit(2))))
        .containsExactly("A", "A");
    hits = cache.getTranslationHits();
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").out("barrierEdge").barrier(3).limit(2))))
        .containsExactly("A", "A");
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").out("barrierEdge").barrier(2).limit(2))))
        .containsExactly("A", "B");
    hits = cache.getTranslationHits();
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").out("barrierEdge").barrier(2).limit(2))))
        .containsExactly("A", "B");
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
  }

  /** Live path labels discriminate otherwise identical ordered-hop source slices. */
  @Test
  public void orderedHopLabelledPaths_haveDistinctWarmCacheEntries() {
    var source = graph.addVertex(T.label, "PathSource", "name", "Source");
    var hub = graph.addVertex(T.label, "PathHub", "name", "Hub");
    var child = graph.addVertex(T.label, "PathChild", "name", "Child");
    source.addEdge("pathEdge", hub);
    hub.addEdge("pathNext", child);
    graph.tx().commit();

    var plain = shapeKey(() -> graph.traversal().V().hasLabel("PathSource")
        .out("pathEdge").order().by("name").limit(1).out("pathNext"));
    var labelled = shapeKey(() -> graph.traversal().V().hasLabel("PathSource").as("a")
        .out("pathEdge").order().by("name").limit(1).out("pathNext"));
    assertThat(plain).isNotEqualTo(labelled);
    for (int round = 0; round < 2; round++) {
      support.assertEquivalent("plain path " + round, Recognition.RECOGNIZED,
          Cardinality.NON_EMPTY, TranslatorEquivalenceSupport::sortedIds,
          () -> graph.traversal().V().hasLabel("PathSource")
              .out("pathEdge").order().by("name").limit(1).out("pathNext"));
      support.assertEquivalent("labelled path " + round, Recognition.RECOGNIZED,
          Cardinality.NON_EMPTY, TranslatorEquivalenceSupport::sortedIds,
          () -> graph.traversal().V().hasLabel("PathSource").as("a")
              .out("pathEdge").order().by("name").limit(1).out("pathNext"));
    }
  }

  /** Two suppliers with the same gated token share a plan but read their own runtime values. */
  @Test
  public void orderedHopGatedSackSuppliers_shareWarmPlanWithoutSharingValues() {
    var first = graph.addVertex(T.label, "BulkParent", "name", "One");
    var second = graph.addVertex(T.label, "BulkParent", "name", "Two");
    var hub = graph.addVertex(T.label, "BulkHub", "name", "Hub");
    var a = graph.addVertex(T.label, "BulkChild", "name", "A");
    var b = graph.addVertex(T.label, "BulkChild", "name", "B");
    first.addEdge("toHub", hub);
    second.addEdge("toHub", hub);
    hub.addEdge("toChild", a);
    hub.addEdge("toChild", b);
    graph.tx().commit();

    var nullKey = shapeKey(() -> graph.traversal().withSack(() -> (Integer) null)
        .V().hasLabel("BulkParent").out("toHub").order().by("name")
        .out("toChild").limit(3));
    var liveKey = shapeKey(() -> graph.traversal().withSack(() -> 1)
        .V().hasLabel("BulkParent").out("toHub").order().by("name")
        .out("toChild").limit(3));
    var splitKey = shapeKey(
        () -> graph.traversal().withSack((java.util.function.Supplier<Integer>) () -> 1,
            (java.util.function.UnaryOperator<Integer>) x -> x)
            .V().hasLabel("BulkParent").out("toHub").order().by("name")
            .out("toChild").limit(3));
    var mergedKey = shapeKey(() -> graph.traversal().withSack(1, Integer::sum)
        .V().hasLabel("BulkParent").out("toHub").order().by("name")
        .out("toChild").limit(3));
    assertThat(nullKey).isEqualTo(liveKey).isNotEqualTo(splitKey).isNotEqualTo(mergedKey);
    var cache = GremlinPlanCache.instance(graphSession());
    long hits = cache.getTranslationHits();
    var nullRows = orderedNames(apply(() -> graph.traversal().withSack(() -> (Integer) null)
        .V().hasLabel("BulkParent").out("toHub").order().by("name")
        .out("toChild").limit(3)));
    assertThat(nullRows).hasSize(3);
    assertThat(nullRows.get(0)).isEqualTo(nullRows.get(1)).isNotEqualTo(nullRows.get(2));
    // The second supplier hits the first supplier's template, but must not reuse its value.
    var liveRows = orderedNames(apply(() -> graph.traversal().withSack(() -> 1)
        .V().hasLabel("BulkParent").out("toHub").order().by("name")
        .out("toChild").limit(3)));
    assertThat(liveRows).hasSize(3);
    assertThat(liveRows.get(0)).isEqualTo(liveRows.get(2)).isNotEqualTo(liveRows.get(1));
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
  }

  /** Sack eligibility changes both cache identity and the ordered hop's bulk grouping. */
  @Test
  public void orderedHopSackConfigurations_useDistinctWarmCacheEntries() {
    var first = graph.addVertex(T.label, "BulkParent", "name", "One");
    var second = graph.addVertex(T.label, "BulkParent", "name", "Two");
    var hub = graph.addVertex(T.label, "BulkHub", "name", "Hub");
    var a = graph.addVertex(T.label, "BulkChild", "name", "A");
    var b = graph.addVertex(T.label, "BulkChild", "name", "B");
    first.addEdge("toHub", hub);
    second.addEdge("toHub", hub);
    hub.addEdge("toChild", a);
    hub.addEdge("toChild", b);
    graph.tx().commit();

    var plain = shapeKey(() -> graph.traversal().V().hasLabel("BulkParent")
        .out("toHub").order().by("name").out("toChild").limit(3));
    var noMerger = shapeKey(() -> graph.traversal().withSack(1).V()
        .hasLabel("BulkParent").out("toHub").order().by("name")
        .out("toChild").limit(3));
    var merger = shapeKey(() -> graph.traversal().withSack(1, Integer::sum).V()
        .hasLabel("BulkParent").out("toHub").order().by("name")
        .out("toChild").limit(3));
    assertThat(plain).isNotEqualTo(noMerger).isNotEqualTo(merger);
    assertThat(merger).isNotEqualTo(noMerger);
    for (int round = 0; round < 2; round++) {
      support.assertEquivalent("plain, round " + round, Recognition.RECOGNIZED,
          Cardinality.NON_EMPTY, GremlinTranslationCacheTest::orderedNames,
          () -> graph.traversal().V().hasLabel("BulkParent").out("toHub")
              .order().by("name").out("toChild").limit(3));
      support.assertEquivalent("unmergeable, round " + round, Recognition.RECOGNIZED,
          Cardinality.NON_EMPTY, GremlinTranslationCacheTest::orderedNames,
          () -> graph.traversal().withSack(1).V().hasLabel("BulkParent")
              .out("toHub").order().by("name").out("toChild").limit(3));
      support.assertEquivalent("mergeable, round " + round, Recognition.RECOGNIZED,
          Cardinality.NON_EMPTY, GremlinTranslationCacheTest::orderedNames,
          () -> graph.traversal().withSack(1, Integer::sum).V()
              .hasLabel("BulkParent").out("toHub").order().by("name")
              .out("toChild").limit(3));
    }
  }

  /** Deferred hasId cannot serve the first RID's neighbour predicate to a second RID. */
  @Test
  public void orderedHopHasId_twoDifferentIds_keepTheirOwnResults() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    hub.addEdge("knows", alice);
    hub.addEdge("knows", bob);
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var hits = cache.getTranslationHits();
    assertThat(sortedNames(apply(() -> graph.traversal().V().order().by("name")
        .out("knows").hasId(alice.id()).limit(1)))).containsExactly("Alice");
    assertThat(sortedNames(apply(() -> graph.traversal().V().order().by("name")
        .out("knows").hasId(bob.id()).limit(1)))).containsExactly("Bob");
    assertThat(cache.getTranslationHits()).isEqualTo(hits);
    support.assertEquivalent("first deferred RID", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().order().by("name").out("knows")
            .hasId(alice.id()).limit(1));
    support.assertEquivalent("second deferred RID", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().order().by("name").out("knows")
            .hasId(bob.id()).limit(1));
  }

  /** Warm cache entries for bare selects and distinct by-modulators never replace each other. */
  @Test
  public void orderedHopSelectPayloads_alternateOverWarmCacheWithoutSharingTemplates() {
    var source = graph.addVertex(T.label, "Person", "name", "Source", "age", 24);
    var target = graph.addVertex(T.label, "Person", "name", "Target");
    source.addEdge("knows", target);
    graph.tx().commit();

    var bareKey = shapeKey(() -> graph.traversal().V().has("name", "Source")
        .as("s").order().by("name").select("s").out("knows").limit(1));
    var nameKey = shapeKey(() -> graph.traversal().V().has("name", "Source")
        .as("s").order().by("name").select("s").by("name").out("knows").limit(1));
    var ageKey = shapeKey(() -> graph.traversal().V().has("name", "Source")
        .as("s").order().by("name").select("s").by("age").out("knows").limit(1));
    assertThat(bareKey).isNotEqualTo(nameKey).isNotEqualTo(ageKey);
    assertThat(nameKey).isNotEqualTo(ageKey);

    var cache = GremlinPlanCache.instance(graphSession());
    var hits = cache.getTranslationHits();
    var misses = cache.getTranslationMisses();
    // Run two shapes at a time: another core test sets the global cache capacity to two.
    // A third simultaneous entry would evict the first and make the hit pin depend on test order.
    for (String modulator : List.of("name", "age")) {
      for (int round = 0; round < 2; round++) {
        assertCachedSelectCast(modulator + " payload, round " + round,
            () -> graph.traversal().V().has("name", "Source")
                .as("s").order().by("name").select("s").by(modulator)
                .out("knows").limit(1));
        support.assertEquivalent("bare select after " + modulator + ", round " + round,
            Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
            TranslatorEquivalenceSupport::sortedIds,
            () -> graph.traversal().V().has("name", "Source")
                .as("s").order().by("name").select("s").out("knows").limit(1));
      }
    }
    // Each distinct shape misses once, then hits its own entry on the next paired pass. The
    // raw shape keys above differ from stored keys after the RID tie-break strategy runs.
    assertThat(cache.getTranslationMisses()).isEqualTo(misses + 3);
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 5);
  }

  /** A cached select-payload traversal must translate and throw like its native counterpart. */
  private void assertCachedSelectCast(
      String scenario,
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> shape) {
    support.withTranslator(false, () -> {
      var nativeTraversal = shape.get().asAdmin();
      nativeTraversal.applyStrategies();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(nativeTraversal)).isZero();
      assertThatThrownBy(nativeTraversal::toList).as(scenario + " (native)")
          .isInstanceOf(ClassCastException.class);
    });
    support.withTranslator(true, () -> {
      var translated = shape.get().asAdmin();
      translated.applyStrategies();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(translated)).isEqualTo(1);
      assertThatThrownBy(translated::toList).as(scenario + " (translated)")
          .isInstanceOf(ClassCastException.class);
    });
  }

  /** The same ordered hop cannot reuse a neighbour-label filter under another poly setting. */
  @Test
  public void orderedHopLabelPolymorphism_keepsCachedTemplatesDistinct() {
    var parent = graphSession().createVertexClass("CacheParent");
    graphSession().getSchema().createClass("CacheChild", parent);
    var hub = graph.addVertex(T.label, "CacheParent", "name", "Hub");
    var child = graph.addVertex(T.label, "CacheChild", "name", "Child");
    hub.addEdge("knows", child);
    graph.tx().commit();
    var poly = shapeKey(() -> graph.traversal()
        .with(YTDBQueryConfigParam.polymorphicQuery, true).V().has("name", "Hub")
        .order().by("name").out("knows").hasLabel("CacheParent").limit(1));
    var exact = shapeKey(() -> graph.traversal()
        .with(YTDBQueryConfigParam.polymorphicQuery, false).V().has("name", "Hub")
        .order().by("name").out("knows").hasLabel("CacheParent").limit(1));
    assertThat(poly).isNotEqualTo(exact);
    var cache = GremlinPlanCache.instance(graphSession());
    var hits = cache.getTranslationHits();
    var misses = cache.getTranslationMisses();
    assertThat(sortedNames(apply(() -> graph.traversal()
        .with(YTDBQueryConfigParam.polymorphicQuery, true).V().has("name", "Hub")
        .order().by("name").out("knows").hasLabel("CacheParent").limit(1))))
        .containsExactly("Child");
    assertThat(apply(() -> graph.traversal()
        .with(YTDBQueryConfigParam.polymorphicQuery, false).V().has("name", "Hub")
        .order().by("name").out("knows").hasLabel("CacheParent").limit(1))).isEmpty();
    assertThat(sortedNames(apply(() -> graph.traversal()
        .with(YTDBQueryConfigParam.polymorphicQuery, true).V().has("name", "Hub")
        .order().by("name").out("knows").hasLabel("CacheParent").limit(1))))
        .containsExactly("Child");
    assertThat(cache.getTranslationMisses()).isEqualTo(misses + 2);
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
  }

  /** A session-default flip after flag resolution does not change the key or the walk's op. */
  @Test
  public void orderedHopPolymorphism_resolvedOnceAcrossShapeAndWalk() {
    var configuration = graphSession().getConfiguration();
    var previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    try {
      var admin = graph.traversal().V().order().by("name").out("knows")
          .hasLabel("Person").limit(1).asAdmin();
      var missingKey = YTDBStrategyUtil.orderIncludesMissingKey(admin);
      var placements = YTDBStrategyUtil.orderByNullsPlacements(admin);
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, false);
      var exactKey = GremlinStepWalker.extractShape(
          admin, graphSession(), missingKey, placements, false).key();
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, true);
      var exact = GremlinStepWalker.production().walk(
          admin, GremlinStepWalker.NO_CHILD_SCOPE, missingKey, placements, false);
      assertThat(exact).isNotNull();
      assertThat(((OrderedExpandSliceListShapingOp) exact.shaping().listShapingOps().getFirst())
          .polymorphic()).isFalse();
      var polyKey = GremlinStepWalker.extractShape(
          admin, graphSession(), missingKey, placements, true).key();
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, false);
      var poly = GremlinStepWalker.production().walk(
          admin, GremlinStepWalker.NO_CHILD_SCOPE, missingKey, placements, true);
      assertThat(poly).isNotNull();
      assertThat(((OrderedExpandSliceListShapingOp) poly.shaping().listShapingOps().getFirst())
          .polymorphic()).isTrue();
      assertThat(exactKey).isNotEqualTo(polyKey);
    } finally {
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

  /** An op with an unbound custom predicate literal cannot use an earlier op's literal. */
  @Test
  public void orderedHopCustomPredicate_twoDifferentLiterals_areNotCached() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    hub.addEdge("knows", alice);
    hub.addEdge("knows", bob);
    graph.tx().commit();

    PBiPredicate<Object, Object> same = Object::equals;
    var cache = GremlinPlanCache.instance(graphSession());
    var hits = cache.getTranslationHits();
    var misses = cache.getTranslationMisses();
    var firstShape = GremlinStepWalker.extractShape(
        graph.traversal().V().order().by("name").out("knows")
            .has("name", new P<>(same, "Alice")).limit(1).asAdmin(),
        graphSession());
    var secondShape = GremlinStepWalker.extractShape(
        graph.traversal().V().order().by("name").out("knows")
            .has("name", new P<>(same, "Bob")).limit(1).asAdmin(),
        graphSession());
    assertThat(firstShape.key()).isEqualTo(secondShape.key());
    assertThat(cache.containsTranslation(firstShape.key())).isFalse();
    assertThat(sortedNames(apply(() -> graph.traversal().V().order().by("name")
        .out("knows").has("name", new P<>(same, "Alice")).limit(1))))
        .containsExactly("Alice");
    assertThat(sortedNames(apply(() -> graph.traversal().V().order().by("name")
        .out("knows").has("name", new P<>(same, "Bob")).limit(1))))
        .containsExactly("Bob");
    assertThat(cache.getTranslationHits()).isEqualTo(hits);
    assertThat(cache.getTranslationMisses())
        .isEqualTo(misses + (firstShape.complete() ? 2 : 0));
    assertThat(cache.containsTranslation(firstShape.key()))
        .as("the walk must not store a reusable op containing Alice's literal")
        .isFalse();
    support.assertEquivalent("first custom literal", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().order().by("name").out("knows")
            .has("name", new P<>(same, "Alice")).limit(1));
    support.assertEquivalent("second custom literal", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().order().by("name").out("knows")
            .has("name", new P<>(same, "Bob")).limit(1));
  }

  /** A bindable filter before a sorted hop shares its template across different source values. */
  @Test
  public void orderedHopPreHopHas_twoValues_shareCachedTranslation() {
    var abe = graph.addVertex(T.label, "Person", "name", "Abe");
    var zed = graph.addVertex(T.label, "Person", "name", "Zed");
    var firstTarget = graph.addVertex(T.label, "Person", "name", "FirstTarget");
    var secondTarget = graph.addVertex(T.label, "Person", "name", "SecondTarget");
    abe.addEdge("knows", firstTarget);
    zed.addEdge("knows", secondTarget);
    graph.tx().commit();

    var firstShape = GremlinStepWalker.extractShape(
        graph.traversal().V().has("name", "Abe").order().by("name")
            .limit(1).out("knows").asAdmin(),
        graphSession());
    var secondShape = GremlinStepWalker.extractShape(
        graph.traversal().V().has("name", "Zed").order().by("name")
            .limit(1).out("knows").asAdmin(),
        graphSession());
    assertThat(firstShape.complete()).isTrue();
    assertThat(firstShape.key()).isEqualTo(secondShape.key());
    var cache = GremlinPlanCache.instance(graphSession());
    var hits = cache.getTranslationHits();
    var misses = cache.getTranslationMisses();

    assertThat(sortedNames(apply(() -> graph.traversal().V().has("name", "Abe")
        .order().by("name").limit(1).out("knows")))).containsExactly("FirstTarget");
    assertThat(cache.containsTranslation(firstShape.key())).isTrue();
    assertThat(cache.getTranslationMisses()).isEqualTo(misses + 1);
    assertThat(sortedNames(apply(() -> graph.traversal().V().has("name", "Zed")
        .order().by("name").limit(1).out("knows")))).containsExactly("SecondTarget");
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
  }

  /**
   * Reordering the outer predicate and detached NOT child swaps parameter slots. The second
   * traversal must not reuse a plan with the first traversal's outer and child slots reversed.
   */
  @Test
  public void reorderedOuterAndNotChildValues_keepTheirOwnBindings() {
    var a = graph.addVertex(T.label, "Person", "name", "A", "v", 1);
    var b = graph.addVertex(T.label, "Person", "name", "B", "v", 2);
    var c = graph.addVertex(T.label, "Person", "name", "C", "v", 1);
    var d = graph.addVertex(T.label, "Person", "name", "D", "v", 2);
    a.addEdge("link", graph.addVertex(T.label, "Target", "v", 2));
    b.addEdge("link", graph.addVertex(T.label, "Target", "v", 1));
    c.addEdge("link", graph.addVertex(T.label, "Target", "v", 1));
    d.addEdge("link", graph.addVertex(T.label, "Target", "v", 2));
    graph.tx().commit();

    var first = GremlinStepWalker.production().walk(graph.traversal().V().hasLabel("Person")
        .has("v", 1).barrier().not(__.out("link").has("v", 2)).asAdmin());
    var second = GremlinStepWalker.production().walk(graph.traversal().V().hasLabel("Person")
        .not(__.out("link").has("v", 1)).barrier().has("v", 2).asAdmin());
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(GremlinPlanFingerprint.fingerprint(first.inputs(), first.shaping()))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(second.inputs(), second.shaping()));

    var cache = GremlinPlanCache.instance(graphSession());
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .has("v", 1).barrier().not(__.out("link").has("v", 2)))))
        .containsExactly("C");
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .not(__.out("link").has("v", 1)).barrier().has("v", 2))))
        .containsExactly("D");
    var hitsBefore = cache.getTranslationHits();
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .has("v", 2).barrier().not(__.out("link").has("v", 1)))))
        .containsExactly("D");
    assertThat(cache.getTranslationHits()).isGreaterThan(hitsBefore);
    support.withTranslator(false, () -> {
      assertThat(sortedNames(graph.traversal().V().hasLabel("Person")
          .has("v", 1).barrier().not(__.out("link").has("v", 2)).toList()))
          .containsExactly("C");
      assertThat(sortedNames(graph.traversal().V().hasLabel("Person")
          .not(__.out("link").has("v", 1)).barrier().has("v", 2).toList()))
          .containsExactly("D");
    });
  }

  /** A cached exists template must rebind the child predicate and keep the second vertex. */
  @Test
  public void existsChild_freshValue_replaysCachedTemplate() {
    var a = graph.addVertex(T.label, "Person", "name", "A");
    var b = graph.addVertex(T.label, "Person", "name", "B");
    a.addEdge("link", graph.addVertex(T.label, "Target", "tag", "one"));
    b.addEdge("link", graph.addVertex(T.label, "Target", "tag", "two"));
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var hitsBefore = cache.getTranslationHits();
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .where(__.out("link").has("tag", "one"))))).containsExactly("A");
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .where(__.out("link").has("tag", "two"))))).containsExactly("B");
    assertThat(cache.getTranslationHits()).isGreaterThan(hitsBefore);
  }

  /** Different child predicates and opposite polarities must not share a cached plan. */
  @Test
  public void existsDifferentChildrenAndNot_doNotShareCacheEntries() {
    var a = graph.addVertex(T.label, "Person", "name", "A");
    var b = graph.addVertex(T.label, "Person", "name", "B");
    a.addEdge("link", graph.addVertex(T.label, "Target", "tag", "one"));
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var misses = cache.getTranslationMisses();
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .where(__.out("link").has("tag", "one"))))).containsExactly("A");
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .where(__.out("link").has("name", "Other"))))).isEmpty();
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .not(__.out("link").has("tag", "one"))))).containsExactly("B");
    assertThat(cache.getTranslationMisses()).isGreaterThanOrEqualTo(misses + 3);
  }

  /** An exists expression must tie its parameter slot to the child predicate in the plan key. */
  @Test
  public void reorderedOuterAndExistsChild_predicatesKeepTheirSlots() {
    var a = graph.addVertex(T.label, "Person", "name", "A", "v", 1);
    var b = graph.addVertex(T.label, "Person", "name", "B", "v", 2);
    a.addEdge("link", graph.addVertex(T.label, "Target", "v", 2));
    b.addEdge("link", graph.addVertex(T.label, "Target", "v", 1));
    graph.tx().commit();

    var first = GremlinStepWalker.production().walk(graph.traversal().V().hasLabel("Person")
        .has("v", 1).where(__.out("link").has("v", 2)).asAdmin());
    var second = GremlinStepWalker.production().walk(graph.traversal().V().hasLabel("Person")
        .where(__.out("link").has("v", 1)).has("v", 2).asAdmin());
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(GremlinPlanFingerprint.fingerprint(first.inputs(), first.shaping()))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(second.inputs(), second.shaping()));
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .has("v", 1).where(__.out("link").has("v", 2)))))
        .containsExactly("A");
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Person")
        .where(__.out("link").has("v", 1)).has("v", 2))))
        .containsExactly("B");
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
    var fp = GremlinPlanFingerprint.fingerprint(walked.inputs(), walked.shaping());
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
    assertThat(shapeKey(() -> graph.traversal().V().has("name", TextP.regex("^mar"))))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().has("name", TextP.notRegex("^mar"))));
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
   * {@code Text.regex} and {@code Text.notRegex} share {@code RegexPredicate}; only {@code
   * isNegate()} distinguishes them. After {@code regex("^mar")} is cached, {@code notRegex("^mar")}
   * must still return the names that do not match, not splice the positive MATCHES plan.
   */
  @Test
  public void regexAndNotRegex_doNotShareCachedPlan() {
    graph.addVertex(T.label, "Person", "name", "marko");
    graph.addVertex(T.label, "Person", "name", "vadas");
    graph.tx().commit();

    var regex = apply(() -> graph.traversal().V().has("name", TextP.regex("^mar")));
    assertThat(sortedNames(regex)).containsExactly("marko");

    var notRegex = apply(() -> graph.traversal().V().has("name", TextP.notRegex("^mar")));
    assertThat(sortedNames(notRegex)).containsExactly("vadas");
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

  /** Every global placement combination is written explicitly into converted sort items. */
  @Test
  public void allFourGlobalNullPlacementCombinations_reachConvertedItems() {
    for (var ascending : OrderByNullsPlacement.values()) {
      for (var descending : OrderByNullsPlacement.values()) {
        setGlobalNullPlacements(ascending, descending);
        var orderBy =
            translatedOrderBy(
                () -> graph.traversal().V().order().by("age").by("name", Order.desc));

        assertThat(orderBy.getItems())
            .extracting(SQLOrderByItem::getNullOrdering)
            .containsExactly(nullOrdering(ascending), nullOrdering(descending));
      }
    }
  }

  /** The ascending per-query option overrides only the converted ascending item. */
  @Test
  public void ascendingPerQueryNullPlacement_reachesConvertedItem() {
    setGlobalNullPlacements(OrderByNullsPlacement.FIRST, OrderByNullsPlacement.LAST);

    var orderBy =
        translatedOrderBy(
            () -> graph
                .traversal()
                .with(
                    YTDBQueryConfigParam.orderByNullsPlacementAsc,
                    OrderByNullsPlacement.LAST)
                .V()
                .order()
                .by("age")
                .by("name", Order.desc));

    assertThat(orderBy.getItems())
        .extracting(SQLOrderByItem::getNullOrdering)
        .containsExactly(SQLOrderByItem.NULLS_LAST, SQLOrderByItem.NULLS_LAST);
  }

  /** The descending per-query option overrides only the converted descending item. */
  @Test
  public void descendingPerQueryNullPlacement_reachesConvertedItem() {
    setGlobalNullPlacements(OrderByNullsPlacement.FIRST, OrderByNullsPlacement.LAST);

    var orderBy =
        translatedOrderBy(
            () -> graph
                .traversal()
                .with(
                    YTDBQueryConfigParam.orderByNullsPlacementDesc,
                    OrderByNullsPlacement.FIRST)
                .V()
                .order()
                .by("age")
                .by("name", Order.desc));

    assertThat(orderBy.getItems())
        .extracting(SQLOrderByItem::getNullOrdering)
        .containsExactly(SQLOrderByItem.NULLS_FIRST, SQLOrderByItem.NULLS_FIRST);
  }

  /** Shape-identical traversals with different placements use separate translation entries. */
  @Test
  public void nullPlacement_missesTranslationCacheWithinOneLifetime() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.tx().commit();

    var cache = GremlinPlanCache.instance(graphSession());
    var missesBefore = cache.getTranslationMisses();
    var hitsBefore = cache.getTranslationHits();

    var first =
        apply(
            () -> graph
                .traversal()
                .with(
                    YTDBQueryConfigParam.orderByNullsPlacementAsc,
                    OrderByNullsPlacement.FIRST)
                .V()
                .order()
                .by("age")
                .values("name"));
    assertThat(first).isEqualTo(List.of("Nobody", "Alice"));
    assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 1);

    apply(
        () -> graph
            .traversal()
            .with(
                YTDBQueryConfigParam.orderByNullsPlacementAsc,
                OrderByNullsPlacement.FIRST)
            .V()
            .order()
            .by("age")
            .values("name"));
    assertThat(cache.getTranslationHits()).isEqualTo(hitsBefore + 1);

    var last =
        apply(
            () -> graph
                .traversal()
                .with(
                    YTDBQueryConfigParam.orderByNullsPlacementAsc,
                    OrderByNullsPlacement.LAST)
                .V()
                .order()
                .by("age")
                .values("name"));
    assertThat(last).isEqualTo(List.of("Alice", "Nobody"));
    assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 2);
  }

  /** Placement changes do not partition a shape without any global order step. */
  @Test
  public void nullPlacement_doesNotPartitionShapeWithoutOrder() {
    setGlobalNullPlacements(OrderByNullsPlacement.FIRST, OrderByNullsPlacement.LAST);
    var shipped = shapeKey(() -> graph.traversal().V().hasLabel("Person"));
    setGlobalNullPlacements(OrderByNullsPlacement.LAST, OrderByNullsPlacement.FIRST);
    var reversed = shapeKey(() -> graph.traversal().V().hasLabel("Person"));

    assertThat(shipped).isEqualTo(reversed);
  }

  /** A nested union-arm order still partitions the enclosing translation shape. */
  @Test
  public void nullPlacement_partitionsShapeForNestedUnionOrder() {
    setGlobalNullPlacements(OrderByNullsPlacement.FIRST, OrderByNullsPlacement.LAST);
    var shipped =
        rawShapeKey(
            () -> graph
                .traversal()
                .V()
                .union(__.order().by("age"), __.identity()));
    setGlobalNullPlacements(OrderByNullsPlacement.LAST, OrderByNullsPlacement.FIRST);
    var reversed =
        rawShapeKey(
            () -> graph
                .traversal()
                .V()
                .union(__.order().by("age"), __.identity()));

    assertThat(shipped).isNotEqualTo(reversed);
  }

  /** The per-session polymorphism flag is part of the shape key; toggling it must split entries. */
  @Test
  public void polymorphismFlag_discriminatesShapeKeys() {
    final var polyOn = captureShapeKeyWithPolymorphic(true);
    final var polyOff = captureShapeKeyWithPolymorphic(false);
    assertThat(polyOn).isNotEqualTo(polyOff);
  }

  /**
   * The resolved order mode is part of the shape key. The two values produce
   * different patterns for the same step list — one carries the order-key {@code IS DEFINED}
   * conjunct and the other does not — so they must not share an entry.
   */
  @Test
  public void productiveOrderSetting_discriminatesShapeKeys() {
    final var includingKey = captureOrderShapeKeyWith(true);
    final var standardOrderKey = captureOrderShapeKeyWith(false);
    assertThat(includingKey).isNotEqualTo(standardOrderKey);
  }

  /** The per-traversal option partitions the effective order mode in the shape key. */
  @Test
  public void perTraversalOrderOption_discriminatesShapeKeys() {
    var retaining = shapeKey(() -> graph.traversal()
        .with(YTDBQueryConfigParam.orderIncludesMissingKey, true).V().order().by("age"));
    var removing = shapeKey(() -> graph.traversal()
        .with(YTDBQueryConfigParam.orderIncludesMissingKey, false).V().order().by("age"));

    assertThat(retaining).isNotEqualTo(removing);
  }

  /** A user strategy partitions the effective order mode in the existing shape token. */
  @Test
  public void standardOrderStrategy_discriminatesShapeKeys() {
    var retaining = shapeKey(() -> graph.traversal().V().order().by("age"));
    var removing = shapeKey(() -> graph.traversal()
        .withStrategies(StandardOrderSemanticsStrategy.instance()).V().order().by("age"));

    assertThat(retaining).isNotEqualTo(removing);
  }

  /**
   * The detecting test for the storage-wide cache: translate a shape under one setting, FLIP the
   * setting INSIDE ONE CACHE LIFETIME with no invalidation in between, and translate again.
   *
   * <p>The second translation must MISS. Without the shape-key token it would hit, and the plan
   * built under the first setting would be spliced verbatim into a traversal running under the
   * second — across sessions, because the cache is storage-wide. The rows prove which semantics
   * each run actually got: three rows under the including default, two under the standard order semantics mode.
   */
  @Test
  public void flippingProductiveOrderSetting_missesTranslationCacheWithinOneLifetime() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.tx().commit();

    var config = graphSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    var cache = GremlinPlanCache.instance(graphSession());
    try {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, true);
      var missesBefore = cache.getTranslationMisses();
      var hitsBefore = cache.getTranslationHits();

      var including = apply(() -> graph.traversal().V().order().by("age").values("name"));
      assertThat(including)
          .as("the including default keeps the record that carries no age")
          .hasSize(3);
      assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 1);

      // Same shape, same cache, no invalidation: a warm hit, which proves the entry is live and
      // that the miss below is caused by the flip rather than by an empty cache.
      var warm = apply(() -> graph.traversal().V().order().by("age").values("name"));
      assertThat(warm).hasSize(3);
      assertThat(cache.getTranslationHits()).isEqualTo(hitsBefore + 1);
      assertThat(cache.getTranslationMisses()).isEqualTo(missesBefore + 1);

      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, false);

      var standardOrderResult = apply(() -> graph.traversal().V().order().by("age").values("name"));
      assertThat(cache.getTranslationMisses())
          .as("the flipped setting must key a different entry, so this translation misses")
          .isEqualTo(missesBefore + 2);
      assertThat(cache.getTranslationHits())
          .as("and it must not be served the plan built under the other setting")
          .isEqualTo(hitsBefore + 1);
      assertThat(standardOrderResult)
          .as("the standard order semantics mode drops the record that carries no age")
          .hasSize(2);
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, previous);
    }
  }

  /**
   * Translation-cache shape keys are value-independent only within one runtime class. Different
   * String values share a key, but an Integer and a Long do not.
   */
  @Test
  public void runtimeValueClass_partitionsShapeKey() {
    var intThirty = shapeKey(() -> graph.traversal().V().has("age", P.eq(30)));
    var intNinetyNine = shapeKey(() -> graph.traversal().V().has("age", P.eq(99)));
    var longThirty = shapeKey(() -> graph.traversal().V().has("age", P.eq(30L)));

    assertThat(intThirty).isEqualTo(intNinetyNine);
    assertThat(intThirty).isNotEqualTo(longThirty);
  }

  /**
   * Row-level guard on limit literals: after {@code limit(1)} is cached, {@code limit(2)} must still
   * return two rows. A fingerprint that collapsed both limits to {@code ?} would serve the first
   * plan and silently truncate.
   */
  @Test
  public void differentLimits_afterCacheWarm_returnCorrectCardinality() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 1);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 2);
    graph.addVertex(T.label, "Person", "name", "Carol", "age", 3);
    graph.tx().commit();

    assertThat(apply(() -> graph.traversal().V().limit(1))).hasSize(1);
    assertThat(apply(() -> graph.traversal().V().limit(2)))
        .as("limit(2) must not reuse a cached limit(1) plan")
        .hasSize(2);
  }

  /**
   * Row-level guard on projection keys: after {@code values("name")} is cached, {@code values("age")}
   * must emit ages, not names.
   */
  @Test
  public void differentValuesKeys_afterCacheWarm_doNotCrossContaminate() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    var names = apply(() -> graph.traversal().V().order().by("name").values("name"));
    assertThat(names).isEqualTo(List.of("Alice", "Bob"));

    var ages = apply(() -> graph.traversal().V().order().by("name").values("age"));
    assertThat(ages)
        .as("values(age) must not reuse a cached values(name) plan")
        .isEqualTo(List.of(30, 40));
  }

  /**
   * Integer vs Long {@code has(age)} partitions the shape key; after warming the Integer entry, the
   * Long walk must still answer correctly for its own binding (and must not be served the Integer
   * plan).
   */
  @Test
  public void integerThenLongAge_afterCacheWarm_bothReturnCorrectRows() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    assertThat(sortedNames(apply(() -> graph.traversal().V().has("age", 30))))
        .containsExactly("Alice");
    assertThat(sortedNames(apply(() -> graph.traversal().V().has("age", 30L))))
        .as("Long 30 must not reuse the Integer-30 cached plan incorrectly")
        .containsExactly("Alice");
    assertThat(sortedNames(apply(() -> graph.traversal().V().has("age", 40L))))
        .containsExactly("Bob");
  }

  /**
   * Predicate order can differ between Gremlin shapes that still compile to one PQD. Warm with
   * {@code has(age).has(name)}, then run {@code has(name).has(age)} with different bindings — both
   * must return their own row (walk + PQD hit + shape backfill must not cross-bind).
   */
  @Test
  public void swappedHasOrder_afterCacheWarm_returnsOwnRow() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    assertThat(
        sortedNames(
            apply(() -> graph.traversal().V().has("age", 30).has("name", "Alice"))))
        .containsExactly("Alice");
    assertThat(
        sortedNames(apply(() -> graph.traversal().V().has("name", "Bob").has("age", 40))))
        .as("swapped has() order must not return the previously cached person's row")
        .containsExactly("Bob");
  }

  private SQLOrderBy translatedOrderBy(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    var admin = supplier.get().asAdmin();
    var placements = YTDBStrategyUtil.orderByNullsPlacements(admin);
    assertThat(placements).isNotNull();
    var translation =
        GremlinToMatchTranslator.translate(
            admin, YTDBStrategyUtil.orderIncludesMissingKey(admin), placements);
    assertThat(translation).isNotNull();
    assertThat(translation.inputs()).isNotNull();
    assertThat(translation.inputs().orderBy()).isNotNull();
    return translation.inputs().orderBy();
  }

  private static void setGlobalNullPlacements(
      OrderByNullsPlacement ascending, OrderByNullsPlacement descending) {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC.setValue(ascending);
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC.setValue(descending);
  }

  private static String nullOrdering(OrderByNullsPlacement placement) {
    return placement == OrderByNullsPlacement.FIRST
        ? SQLOrderByItem.NULLS_FIRST
        : SQLOrderByItem.NULLS_LAST;
  }

  /**
   * A user {@code as(...)} label parked on a transparent barrier is part of the shape, and so is the
   * position of that barrier. {@code out().as(mid)[on barrier].out()} and {@code
   * out().out().as(mid)[on barrier]} leave every significant step unlabelled, so before the barrier
   * labels were encoded both spellings produced one key. The walker binds the label to the boundary
   * reached where the barrier sits, so the two spellings name different hops and must not share a
   * cache entry.
   */
  @Test
  public void barrierLabelPosition_discriminatesShapeKeys() {
    var afterFirstHop = shapeKey(barrierLabelAfterHop(1));
    var afterSecondHop = shapeKey(barrierLabelAfterHop(2));

    assertThat(afterFirstHop)
        .as("a barrier label after the first hop names a different alias than after the second")
        .isNotEqualTo(afterSecondHop);
  }

  /**
   * The row-level half of {@link #barrierLabelPosition_discriminatesShapeKeys}. Over the chain
   * Alice knows Bob knows Carol, {@code out().as(mid)[on barrier].out().select(mid)} must return
   * Bob and {@code out().out().as(mid)[on barrier].select(mid)} must return Carol. Warming the cache
   * with the first shape must not serve its plan to the second.
   */
  @Test
  public void barrierLabelPosition_doNotShareCachedPlan() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    bob.addEdge("knows", carol);
    graph.tx().commit();

    var afterFirstHop = applyAdmin(barrierLabelAfterHop(1));
    assertThat(sortedNames(afterFirstHop))
        .as("a label bound after the first hop selects the middle vertex")
        .containsExactly("Bob");

    var afterSecondHop = applyAdmin(barrierLabelAfterHop(2));
    assertThat(sortedNames(afterSecondHop))
        .as("the second shape must not be served the first shape's cached plan")
        .containsExactly("Carol");
  }

  /**
   * A barrier with no label on it is part of the shape too, because the walker reads a skipped
   * barrier through the fold latch. {@code g.V().has("name", gt(27))} is folded and compares with
   * SQL ordering, which ranks a String above an Integer, while {@code
   * g.V().barrier().has("name", gt(27))} is unfolded and carries the per-record type guard, which
   * keeps only the numeric row. Over one class holding both runtime types the two spellings return
   * different rows, so one cache entry cannot serve both.
   */
  @Test
  public void unlabelledBarrier_doesNotShareCachedPlanWithTheFoldedSpelling() {
    seedMixedRuntimeTypes();

    assertThat(shapeKey(() -> graph.traversal().V().has("name", P.gt(27))))
        .as("a barrier that closes the fold must discriminate the shape key")
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().barrier().has("name", P.gt(27))));

    var folded = apply(() -> graph.traversal().V().has("name", P.gt(27)));
    assertThat(sortedTags(folded))
        .as("the folded comparison keeps the SQL ordering answer, which ranks the String above 27")
        .containsExactly("loose_num", "loose_zulu");

    var unfolded = apply(() -> graph.traversal().V().barrier().has("name", P.gt(27)));
    assertThat(sortedTags(unfolded))
        .as("the unfolded comparison must not be served the folded plan, which drops its guard")
        .containsExactly("loose_num");
  }

  /**
   * Two vertices of one schema-less class holding both runtime types under {@code name}: the String
   * {@code zulu} and the Integer {@code 99}. A comparison that ignores runtime type answers over
   * both, one that respects it answers over the Integer alone.
   */
  private void seedMixedRuntimeTypes() {
    session.createVertexClass("Loose");
    graph.addVertex(T.label, "Loose", "tag", "loose_zulu", "name", "zulu");
    graph.addVertex(T.label, "Loose", "tag", "loose_num", "name", 99);
    graph.tx().commit();
  }

  /** Sorted {@code tag} values of the returned vertices — the mixed-type fixture's row identity. */
  private static List<String> sortedTags(List<?> vertices) {
    return vertices.stream()
        .map(v -> ((Vertex) v).value("tag"))
        .map(Object::toString)
        .sorted()
        .toList();
  }

  /**
   * Documentation-only / pre-existing encoding (TQ1500): a label {@code FilterRankingStrategy}
   * migrates onto the {@code dedup()} step is already part of the shape key through the per-step
   * label section that existed before Track 03. Production rarely reaches the hop-labelled
   * spelling, because the ranking strategy moves the label onto {@code dedup} first. Kept here to
   * record that encoding, not as Track 03 coverage.
   */
  @Test
  public void dedupStepLabel_isPartOfShapeKey() {
    var labelOnHop = shapeKey(dedupSelectShape(/* labelOnDedupStep= */ false));
    var labelOnDedup = shapeKey(dedupSelectShape(/* labelOnDedupStep= */ true));

    assertThat(labelOnHop)
        .as("the dedup step's own labels reach the key through the per-step label section")
        .isNotEqualTo(labelOnDedup);
  }

  /**
   * Documentation-only companion to {@link #dedupStepLabel_isPartOfShapeKey} (TQ1500): the
   * over-keying is harmless because {@code dedup} emits the traverser it received, so both label
   * positions name the same hop target and return the same row.
   */
  @Test
  public void dedupStepLabel_bothPositionsReturnTheSameRow() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    assertThat(sortedNames(applyAdmin(dedupSelectShape(false))))
        .as("the label on the hop selects the hop target")
        .containsExactly("Bob");
    assertThat(sortedNames(applyAdmin(dedupSelectShape(true))))
        .as("the migrated label names the same element the hop bound")
        .containsExactly("Bob");
  }

  /**
   * {@code g.V().out("knows").out("knows").select("mid")} with {@code as("mid")} parked on a
   * transparent {@link NoOpBarrierStep} that follows hop number {@code hop}. This is the placement
   * {@code LazyBarrierStrategy} produces for {@code out().as("mid").out()}: it inserts a barrier
   * after the hop and moves the hop's labels onto it through {@code TraversalHelper.copyLabels}. The
   * barrier is placed by hand so both shapes differ in that one position and nothing else.
   */
  private Traversal.Admin<?, ?> barrierLabelAfterHop(int hop) {
    var admin = graph.traversal().V().out("knows").out("knows").select("mid").asAdmin();
    var barrier = new NoOpBarrierStep<>(admin);
    barrier.addLabel("mid");
    // Step 0 is the GraphStep, so hop n sits at index n and its barrier goes at index n + 1.
    admin.addStep(hop + 1, barrier);
    return admin;
  }

  /**
   * {@code g.V().out("knows").as("t").dedup().select("t")} with {@code as("t")} either left on the
   * hop or moved onto the {@code dedup} step, which is what {@code FilterRankingStrategy} does to
   * this shape in production.
   */
  private Traversal.Admin<?, ?> dedupSelectShape(boolean labelOnDedupStep) {
    var admin = graph.traversal().V().out("knows").as("t").dedup().select("t").asAdmin();
    if (labelOnDedupStep) {
      // Index 1 is the hop, index 2 the DedupGlobalStep — the migration moves the label forward.
      // getSteps() is a raw Step list, and label mutation needs no type argument.
      admin.getSteps().get(1).removeLabel("t");
      admin.getSteps().get(2).addLabel("t");
    }
    return admin;
  }

  /** NL-7 keeps a non-String label value out of a warmed String-label translation entry. */
  @Test
  public void labelValueWithSameTextAsString_doesNotHitWarmedTranslation() {
    session.createVertexClass("Employee");
    graph.addVertex(T.label, "Employee", "name", "e");
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(graphSession());
    var fakeLabel = new Object() {
      @Override
      public String toString() {
        return "Employee";
      }
    };
    // The native start-step planner rejects this non-String value. A cached String-label plan
    // must not replace that rejection with Employee rows.
    support.withTranslator(false, () -> assertThatThrownBy(
        () -> graph.traversal().V().has(T.label, P.eq(fakeLabel)).toList())
        .isInstanceOf(ClassCastException.class));
    assertThat(sortedNames(apply(() -> graph.traversal().V().hasLabel("Employee"))))
        .containsExactly("e");
    var hits = cache.getTranslationHits();
    var misses = cache.getTranslationMisses();

    var invalid = graph.traversal().V().has(T.label, P.eq(fakeLabel)).asAdmin();
    GremlinToMatchStrategy.instance().apply(invalid);
    assertThat(invalid.getStartStep()).isNotInstanceOf(YTDBMatchPlanStep.class);
    assertThat(cache.getTranslationHits()).isEqualTo(hits);
    assertThat(cache.getTranslationMisses()).isEqualTo(misses + 1);
    // Draining applies provider strategies again and may hit the new cached decline, not the
    // warmed Employee translation. The native exception must still reach the caller.
    assertThatThrownBy(invalid::toList).isInstanceOf(ClassCastException.class);
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    assertThat(shapeKey(() -> graph.traversal().V().hasLabel("Employee")))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(T.label, P.eq(fakeLabel))));
  }

  /** A cached unsupported hasId operator must not suppress an accepted equality on the same id. */
  @Test
  public void hasIdOperators_doNotShareCachedDecline() {
    var employee = graph.addVertex(T.label, "Person", "name", "e");
    var other = graph.addVertex(T.label, "Person", "name", "u");
    graph.tx().commit();
    var id = employee.id();
    var cache = GremlinPlanCache.instance(graphSession());
    var unsupported = graph.traversal().V().hasId(P.neq(id)).asAdmin();
    GremlinToMatchStrategy.instance().apply(unsupported);
    assertThat(unsupported.getStartStep()).isNotInstanceOf(YTDBMatchPlanStep.class);
    assertThat(unsupported.toList()).containsExactly(other);
    var hits = cache.getTranslationHits();

    assertThat(sortedNames(apply(() -> graph.traversal().V().hasId(P.eq(id)))))
        .containsExactly("e");
    assertThat(cache.getTranslationHits()).isEqualTo(hits);
    assertThat(shapeKey(() -> graph.traversal().V().hasId(P.eq(id))))
        .isNotEqualTo(shapeKey(() -> graph.traversal().V().hasId(P.neq(id))));
    support.assertEquivalent("hasId equality after cached inequality",
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED,
        TranslatorEquivalenceSupport.Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().hasId(P.eq(id)));
  }

  /** Label and id keys include predicate trees, operators, typed values and collection structure. */
  @Test
  public void labelAndIdPredicates_encodeCompleteStructuralConditions() {
    for (var token : List.of(T.label, T.id)) {
      assertThat(shapeKey(() -> graph.traversal().V().has(token, P.eq("Employee"))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token, P.neq("Employee"))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token, P.within("Employee"))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token, P.eq("Contractor"))));
      assertThat(shapeKey(() -> graph.traversal().V().has(token,
          P.eq("Employee").and(P.neq("Manager")))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token,
              P.eq("Employee").or(P.neq("Manager")))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token,
              P.eq("Employee").and(P.eq("Manager")))));
      assertThat(shapeKey(() -> graph.traversal().V().has(token, TextP.regex("Emp.*"))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token,
              TextP.notRegex("Emp.*"))));
      assertThat(shapeKey(() -> graph.traversal().V().has(token, P.eq(1))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token, P.eq(1L))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token, P.eq("1"))));
      assertThat(shapeKey(() -> graph.traversal().V().has(token, P.eq(List.of(1)))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token, P.eq(List.of("1")))))
          .isNotEqualTo(shapeKey(() -> graph.traversal().V().has(token, P.eq("[1]"))));
    }
    var nullValue = new GremlinShapeEncoder(null);
    nullValue.appendPredicate(P.eq(null), true);
    var nullText = new GremlinShapeEncoder(null);
    nullText.appendPredicate(P.eq("null"), true);
    assertThat(nullValue.key()).isNotEqualTo(nullText.key());
    var nestedList = new GremlinShapeEncoder(null);
    nestedList.appendPredicate(P.eq(List.of(List.of("Employee"))), true);
    var listText = new GremlinShapeEncoder(null);
    listText.appendPredicate(P.eq(List.of("[Employee]")), true);
    assertThat(nestedList.key()).isNotEqualTo(listText.key());
    var negated = new GremlinShapeEncoder(null);
    negated.appendPredicate(new NotP<>(P.eq("Employee")), true);
    var equality = new GremlinShapeEncoder(null);
    equality.appendPredicate(P.eq("Employee"), true);
    assertThat(negated.key()).isNotEqualTo(equality.key());
  }

  /** A cyclic collection cannot be encoded completely and must bypass cache lookup and storage. */
  @Test
  public void cyclicStructuralValue_marksShapeIncompleteWithoutOverflow() {
    var cycle = new ArrayList<Object>();
    cycle.add(cycle);
    var encoder = new GremlinShapeEncoder(null);
    encoder.appendPredicate(P.eq(cycle), true);
    assertThat(encoder.complete()).isFalse();
  }

  private String shapeKey(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    return shapeKey(supplier.get().asAdmin());
  }

  private String shapeKey(Traversal.Admin<?, ?> admin) {
    var extraction = GremlinStepWalker.extractShape(admin, graphSession());
    assertThat(extraction.complete()).isTrue();
    return extraction.key();
  }

  private String rawShapeKey(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    return GremlinStepWalker.extractShape(supplier.get().asAdmin(), graphSession()).key();
  }

  private List<?> apply(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    return applyAdmin(supplier.get().asAdmin());
  }

  /**
   * Runs {@link GremlinToMatchStrategy} over a prepared step list and drains it, pinning that the
   * shape translated. Strategies are not applied first, so a hand-placed barrier stays where the
   * test put it.
   */
  private List<?> applyAdmin(Traversal.Admin<?, ?> admin) {
    GremlinToMatchStrategy.instance().apply(admin);
    assertThat(admin.getSteps()).hasSize(1);
    assertThat(admin.getSteps().getFirst()).isInstanceOf(YTDBMatchPlanStep.class);
    return admin.toList();
  }

  private static List<String> sortedNames(List<?> vertices) {
    return orderedNames(vertices).stream().sorted().toList();
  }

  /** Order-sensitive renderer for traversals whose order() makes bulk grouping observable. */
  private static List<String> orderedNames(List<?> vertices) {
    return vertices.stream()
        .map(v -> (Object) ((Vertex) v).value("name"))
        .map(String::valueOf).toList();
  }

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  /** Returns the order shape key for the supplied order mode. */
  private String captureOrderShapeKeyWith(boolean value) {
    var config = graphSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, value);
    try {
      return shapeKey(() -> graph.traversal().V().order().by("age").values("name"));
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, previous);
    }
  }

  private String captureShapeKeyWithPolymorphic(boolean value) {
    var config = graphSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, value);
    try {
      return shapeKey(() -> graph.traversal().V().hasLabel("Person"));
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

}
