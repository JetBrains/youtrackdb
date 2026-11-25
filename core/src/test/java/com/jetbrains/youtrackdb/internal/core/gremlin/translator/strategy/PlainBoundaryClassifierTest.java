package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AliasPropertyPresence;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.DedupPayloadListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.FoldListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedExpandSliceListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedHopStage;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PropertyGroupCount;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ReverseListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.TailListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.UnfoldListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ValuesFlatMapListShapingOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.Reason;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.junit.Test;

/** Real translator and planner fixtures assert decisions, never engine result equality. */
public class PlainBoundaryClassifierTest extends GraphBaseTest {

  /** Exact reverse maps around one fold are neutral, but nullable unfold is not certified. */
  @Test
  public void nativeReverseCompositionStillRequiresTheCompletePhysicalProof() {
    var base = translate(graph.traversal().V());
    var context = new BasicCommandContext(session);
    var plan = new MatchExecutionPlanner(base.inputs()).createExecutionPlan(context, false, false);
    try {
      for (var operations : List.of(
          List.<ListShapingOp>of(new ReverseListShapingOp()),
          List.<ListShapingOp>of(new ReverseListShapingOp(), new FoldListShapingOp()))) {
        var admitted = base.withShaping(base.shaping().withListShapingOps(operations));
        assertThat(PlainBoundaryClassifier.classify(plan, admitted, null, context, false).reason())
            .isEqualTo(Reason.PLAIN);
      }
      var reversed = base.withShaping(base.shaping().withListShapingOps(
          List.of(new FoldListShapingOp(), new ReverseListShapingOp())));
      assertThat(PlainBoundaryClassifier.classify(plan, reversed, null, context, false).reason())
          .isEqualTo(Reason.PLAIN);
      var twiceFolded = base.withShaping(base.shaping().withListShapingOps(
          List.of(new FoldListShapingOp(), new FoldListShapingOp())));
      assertThat(PlainBoundaryClassifier.classify(plan, twiceFolded).reason())
          .isEqualTo(Reason.BOUNDARY_OPERATION);
      var unknown = base.withShaping(base.shaping().withListShapingOps(
          List.of(new UnfoldListShapingOp())));
      assertThat(PlainBoundaryClassifier.classify(plan, unknown, null, context, false).reason())
          .isEqualTo(Reason.BOUNDARY_OPERATION);
    } finally {
      plan.close();
    }
  }

  @Test
  public void directElementPropertyAndMapBoundariesIncludingG6AndFoldArePlain() {
    graph.addVertex("name", "one");
    for (var traversal : List.of(graph.traversal().V(), graph.traversal().V().values("name"),
        graph.traversal().V().valueMap("name"), graph.traversal().V().elementMap("name"),
        graph.traversal().V().fold())) {
      var translation = GremlinToMatchTranslator.translate(traversal.asAdmin());
      assertThat(translation).isNotNull();
      assertThat(decision(translation)).isEqualTo(Reason.PLAIN);
      assertThat(decision(translation.withShaping(
          translation.shaping().withPropertyMapBulkingCapacity(100)))).isEqualTo(Reason.PLAIN);
    }
  }

  @Test
  public void eachUnsupportedBoundaryFlagAndUnknownListOperationKeepsMatch() {
    var base = translate(graph.traversal().V());
    List<UnaryOperator<ResultShaping>> changes = List.of(
        s -> s.withAccumulateMap(true), s -> s.withEmitGroupEntries(true),
        s -> s.withEmptyBarrier(true), s -> s.withRowDedupAlias("a"),
        s -> s.withOrderedSourceKeyColumns(List.of("a")),
        s -> s.withEdgeMapKeys(List.of("a")), s -> s.withPropertyMapBulkingCapacity(-1),
        s -> s.withAliasPropertyPresences(List.of(new AliasPropertyPresence("a", "name", "n"))),
        s -> s.withPropertyGroupCount(new PropertyGroupCount("a", "name", false)));
    for (var change : changes) {
      assertThat(decision(base.withShaping(change.apply(ResultShaping.NONE))))
          .isEqualTo(Reason.BOUNDARY_SHAPE);
    }
    ListShapingOp unknown = upstream -> upstream;
    assertThat(decision(base.withShaping(ResultShaping.NONE.withListShapingOps(List.of(unknown)))))
        .isEqualTo(Reason.BOUNDARY_OPERATION);
    assertThat(decision(base.withShaping(ResultShaping.NONE.withListShapingOps(
        List.of(new FoldListShapingOp(), new FoldListShapingOp())))))
        .isEqualTo(Reason.BOUNDARY_OPERATION);
  }

  @Test
  public void auditedCurrentRowFlagsDoNotTurnPlainWorkIntoNonPlainWork() {
    var base = translate(graph.traversal().V());
    var shaping = ResultShaping.NONE.withDropNullRows(true).withDropOnAbsent(true)
        .withPresencePropertyKeys(List.of("name")).withMapEmitColumnOrder(List.of("name"))
        .withRecordIdMapKeys(List.of("id")).withWrapMapValuesInLists(true)
        .withUnwrapSingletonMap(true).withElementMapTokens(true);
    assertThat(decision(base.withShaping(shaping))).isEqualTo(Reason.PLAIN);
  }

  @Test
  public void unionAndScalarAggregatesKeepTheirDistinctBoundaryReasons() {
    var base = translate(graph.traversal().V());
    var scalar = GremlinToMatchTranslator.TranslationResult.singlePlan(base.inputs(),
        base.boundaryAlias(), BoundaryOutputType.SCALAR, base.returnClass(), base.inputParameters(),
        false);
    assertThat(decision(scalar)).isEqualTo(Reason.BOUNDARY_SHAPE);
    var child = new GremlinToMatchTranslator.TranslationResult.ChildPlan(base.inputs(),
        base.inputParameters(), false);
    var union = GremlinToMatchTranslator.TranslationResult.multiPlan(List.of(child, child),
        List.of(), base.boundaryAlias(), base.outputType(), base.returnClass(), ResultShaping.NONE);
    assertThat(PlainBoundaryClassifier.classify(null, union).reason()).isEqualTo(Reason.MULTI_PLAN);
    for (var operation : List.of(new PostConcatOp.Count(), new PostConcatOp.Range(0, 1),
        new PostConcatOp.Dedup(), new PostConcatOp.RequireDefined("a", "name"),
        new PostConcatOp.Order(List.of(new SQLOrderByItem())))) {
      var reduced = GremlinToMatchTranslator.TranslationResult.multiPlan(List.of(child, child),
          List.of(operation), base.boundaryAlias(), base.outputType(), base.returnClass(),
          ResultShaping.NONE);
      assertThat(PlainBoundaryClassifier.classify(null, reduced).reason())
          .isEqualTo(Reason.MULTI_PLAN);
    }
  }

  @Test
  public void resultShapingAdditionsRequireAnExplicitAudit() {
    assertThat(Arrays.stream(ResultShaping.class.getRecordComponents()).map(c -> c.getName()))
        .containsExactly("dropNullRows", "dropOnAbsent", "presencePropertyKeys",
            "aliasPropertyPresences", "mapEmitColumnOrder", "recordIdMapKeys", "edgeMapKeys",
            "wrapMapValuesInLists", "accumulateMap", "unwrapSingletonMap", "elementMapTokens",
            "emitGroupEntries", "emptyBarrier", "rowDedupAlias", "listShapingOps",
            "orderedSourceKeyColumns", "propertyGroupCount", "propertyMapBulkingCapacity");
  }

  @Test
  public void frozenNonPlainListFamiliesKeepTheBoundaryOperationReason() {
    var base = translate(graph.traversal().V());
    for (var type : List.of(DedupPayloadListShapingOp.class, OrderedExpandSliceListShapingOp.class,
        TailListShapingOp.class, UnfoldListShapingOp.class,
        ValuesFlatMapListShapingOp.class)) {
      assertThat(decision(base.withShaping(ResultShaping.NONE.withListShapingOps(
          List.of(mock(type)))))).isEqualTo(Reason.BOUNDARY_OPERATION);
    }
  }

  @Test
  public void allOrderedHopStageFamiliesStayInsideANonPlainBoundaryOwner() {
    var base = translate(graph.traversal().V());
    var merge = new OrderedHopStage.SourceMerge(OrderedHopStage.MergeKey.ELEMENT);
    var expand = new OrderedHopStage.Expand(Direction.OUT, null);
    for (var stage : List.of(merge, expand, new OrderedHopStage.Filter(List.of(), true),
        new OrderedHopStage.Slice(0, 1), new OrderedHopStage.Values("name"),
        new OrderedHopStage.Barrier(10, OrderedHopStage.MergeKey.ELEMENT),
        new OrderedHopStage.Project())) {
      var operation = new OrderedExpandSliceListShapingOp(List.of(merge, expand, stage));
      assertThat(decision(base.withShaping(ResultShaping.NONE.withListShapingOps(
          List.of(operation))))).isEqualTo(Reason.BOUNDARY_OPERATION);
    }
  }

  @Test
  public void realForwardHopsAndLocalDisjunctionHaveAuditedPlainShapes() {
    var modern = ModernGraphFixture.seed(graph, session);
    // Keep non-root V aliases above the prefetch threshold. A RID root also prevents rerooting.
    // The small modern graph alone selects other-alias prefetch, which is deliberately non-plain.
    for (int i = 0; i < 110; i++) {
      graph.addVertex("name", "padding" + i);
    }
    graph.tx().commit();
    assertThat(decision(translate(graph.traversal().V(modern.marko().id())
        .out("knows").out("created"))))
        .isEqualTo(Reason.PLAIN);
    assertThat(decision(translate(graph.traversal().V().has("name",
        org.apache.tinkerpop.gremlin.process.traversal.P.within("marko", "josh")))))
        .isEqualTo(Reason.PLAIN);
  }

  @Test
  public void smallGraphPrefetchOnANonStartAliasIsNonPlain() {
    var modern = ModernGraphFixture.seed(graph, session);
    assertThat(decision(translate(graph.traversal().V(modern.marko().id())
        .out("knows").out("created")))).isEqualTo(Reason.ROOT_CHANGED);
  }

  @Test
  public void translatedUnfilteredHopSliceIsPlainButPredicateFilterAppliesSliceAfterFilter() {
    var modern = ModernGraphFixture.seed(graph, session);
    // Keep the filtered target above the prefetch threshold as well as the unfiltered target.
    for (int i = 0; i < 1100; i++) {
      graph.addVertex("name", "padding" + i, "age", 2);
    }
    graph.tx().commit();
    assertThat(decision(translate(graph.traversal().V(modern.marko().id())
        .out("knows").out("created").limit(1)))).isEqualTo(Reason.PLAIN);
    assertThat(decision(translate(graph.traversal().V(modern.marko().id())
        .out("knows").has("age", 2)
        .limit(1)))).isEqualTo(Reason.SLICE_AFTER_FILTER);
  }

  private GremlinToMatchTranslator.TranslationResult translate(GraphTraversal<?, ?> traversal) {
    var result = GremlinToMatchTranslator.translate(traversal.asAdmin());
    assertThat(result).isNotNull();
    return result;
  }

  private Reason decision(GremlinToMatchTranslator.TranslationResult translation) {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    ctx.setInputParameters(translation.inputParameters());
    var plan =
        new MatchExecutionPlanner(translation.inputs()).createExecutionPlan(ctx, false, false);
    return PlainBoundaryClassifier.classify(plan, translation).reason();
  }
}
