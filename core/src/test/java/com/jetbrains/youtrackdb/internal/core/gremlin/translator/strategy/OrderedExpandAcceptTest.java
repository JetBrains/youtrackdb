package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedExpandSliceListShapingOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import java.util.List;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link OrderedExpandAccept}: source-slice-then-expand admission and the labelled /
 * traversal-bearing decline arms that end-to-end strategy application can relocate off the hop.
 */
public class OrderedExpandAcceptTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /**
   * With {@code order()} + statement {@code LIMIT} on the current boundary, a bare hop is admitted
   * as expand-after-source-slice.
   */
  @Test
  public void hasOrderedSourceSliceForExpand_trueWhenOrderAndLimitCaptured() {
    var ctx = orderedSlicedContext();
    assertThat(OrderedExpandAccept.hasOrderedSourceSliceForExpand(ctx)).isTrue();
  }

  /** A pending deferred hop blocks the source-slice expand carve-out. */
  @Test
  public void hasOrderedSourceSliceForExpand_falseWhenPendingHopPresent() {
    var ctx = orderedSlicedContext();
    ctx.setPendingOrderedHop(
        new PendingOrderedHop(
            org.apache.tinkerpop.gremlin.structure.Direction.OUT,
            new String[] {"knows"},
            BOUNDARY_ALIAS,
            "$g2m_anon_0",
            List.of()));
    assertThat(OrderedExpandAccept.hasOrderedSourceSliceForExpand(ctx)).isFalse();
  }

  /**
   * A labelled hop after source slice declines — neighbours are list-shaping payloads, not MATCH
   * aliases a later {@code select} could resolve.
   */
  @Test
  public void acceptExpandAfterSourceSlice_labelledHop_declines() {
    var admin = graph.traversal().V().out("knows").as("n").asAdmin();
    var hop = (VertexStepContract<?>) admin.getSteps().get(1);
    var ctx = orderedSlicedContext();
    var cursor = cursorAfterStart(admin);
    cursor.take(); // hop already extracted for the recogniser call shape

    assertThat(OrderedExpandAccept.acceptExpandAfterSourceSlice(cursor, hop, ctx))
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.listShapingOps()).isEmpty();
  }

  /**
   * A labelled neighbour {@code has} after the hop declines for the same reason — the label cannot
   * bind onto a MATCH alias behind expand-only shaping.
   */
  @Test
  public void acceptExpandAfterSourceSlice_labelledHas_declines() {
    var admin = graph.traversal().V().out("knows").has("name", "x").as("n").asAdmin();
    var hop = (VertexStepContract<?>) admin.getSteps().get(1);
    var ctx = orderedSlicedContext();
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take(); // GraphStep
    cursor.take(); // hop — recogniser already holds it

    assertThat(OrderedExpandAccept.acceptExpandAfterSourceSlice(cursor, hop, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /** Traversal-bearing neighbour {@code has} declines via {@code collectDeferredHasContainers}. */
  @Test
  public void acceptExpandAfterSourceSlice_traversalHas_declines() {
    var admin = graph.traversal().V().out("knows").has("name", __.out("knows")).asAdmin();
    var hop = (VertexStepContract<?>) admin.getSteps().get(1);
    var ctx = orderedSlicedContext();
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take();
    cursor.take();

    assertThat(OrderedExpandAccept.acceptExpandAfterSourceSlice(cursor, hop, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /** {@code groupBy} present declines expand-after-source-slice. */
  @Test
  public void acceptExpandAfterSourceSlice_withGroupBy_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var hop = (VertexStepContract<?>) admin.getSteps().get(1);
    var ctx = orderedSlicedContext();
    ctx.setGroupBy(new SQLGroupBy(-1));
    var cursor = cursorAfterStart(admin);
    cursor.take();

    assertThat(OrderedExpandAccept.acceptExpandAfterSourceSlice(cursor, hop, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /** Happy path: bare hop after source slice appends an unbounded ordered-expand stage. */
  @Test
  public void acceptExpandAfterSourceSlice_bareHop_appendsExpandOp() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var hop = (VertexStepContract<?>) admin.getSteps().get(1);
    var ctx = orderedSlicedContext();
    var cursor = cursorAfterStart(admin);
    cursor.take();

    assertThat(OrderedExpandAccept.acceptExpandAfterSourceSlice(cursor, hop, ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.listShapingOps()).hasSize(1);
    assertThat(ctx.listShapingOps().getFirst()).isInstanceOf(OrderedExpandSliceListShapingOp.class);
    var op = (OrderedExpandSliceListShapingOp) ctx.listShapingOps().getFirst();
    assertThat(op.limit()).isEqualTo(-1);
    assertThat(op.skip()).isEqualTo(0);
  }

  private static WalkerContext orderedSlicedContext() {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    ctx.setOrderBy(
        MatchProjectionBuilder.orderBy(
            List.of(
                ProjectionExpressionFactories.orderByProperty(BOUNDARY_ALIAS, "name", true))));
    ctx.recordOrderByCapture(BOUNDARY_ALIAS, false);
    ctx.setLimit(ProjectionExpressionFactories.limit(1));
    return ctx;
  }

  private static StepStreamCursor cursorAfterStart(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take();
    return cursor;
  }
}
