package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link OrderGlobalStepRecogniser} and {@link RangeGlobalStepRecogniser}: ORDER BY
 * wiring, skip/limit translation, and decline paths.
 */
public class OrderRangeStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /** Bare {@code order()} sorts by element identity ({@code alias.@rid ASC}). */
  @Test
  public void bareOrder_sortsByRidAsc() {
    var admin = graph.traversal().V().order().asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, OrderGlobalStep.class);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy).isNotNull();
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("@rid");
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("ASC");
  }

  /** {@code order().by("name", Order.desc)} produces a DESC ORDER BY on the property field. */
  @Test
  public void orderByProperty_desc() {
    var admin = graph.traversal().V().order().by("name", Order.desc).asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, OrderGlobalStep.class);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.toString()).contains("name");
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("DESC");
  }

  /** Multi-key {@code order().by(...).by(...)} emits multiple ORDER BY items. */
  @Test
  public void orderByMultiKey_emitsMultipleItems() {
    var admin =
        graph.traversal().V().order().by("age", Order.asc).by("name", Order.desc).asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, OrderGlobalStep.class);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.getItems()).hasSize(2);
  }

  /** {@code Order.shuffle} has no MATCH equivalent and declines. */
  @Test
  public void orderShuffle_declines() {
    var admin = graph.traversal().V().order().by(Order.shuffle).asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, OrderGlobalStep.class);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.orderBy).isNull();
  }

  /** {@code limit(5)} is {@code RangeGlobalStep(0, 5)} → {@code LIMIT 5} only. */
  @Test
  public void limit_setsLimitOnly() {
    var admin = graph.traversal().V().limit(5).asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, RangeGlobalStep.class);

    var outcome = RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.limit).isNotNull();
    assertThat(ctx.limit.toString()).contains("5");
    assertThat(ctx.skip).isNull();
  }

  /** {@code skip(3)} is {@code RangeGlobalStep(3, -1)} → {@code SKIP 3} only. */
  @Test
  public void skip_setsSkipOnly() {
    var admin = graph.traversal().V().skip(3).asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, RangeGlobalStep.class);

    var outcome = RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.skip).isNotNull();
    assertThat(ctx.skip.toString()).contains("3");
    assertThat(ctx.limit).isNull();
  }

  /** {@code range(2, 7)} → {@code SKIP 2 LIMIT 5}. */
  @Test
  public void range_setsSkipAndLimit() {
    var admin = graph.traversal().V().range(2, 7).asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, RangeGlobalStep.class);

    var outcome = RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.skip.toString()).contains("2");
    assertThat(ctx.limit.toString()).contains("5");
  }

  /** {@code skip(0)} is a no-op: accepted with neither SKIP nor LIMIT set. */
  @Test
  public void skipZero_isNoOp() {
    var admin = graph.traversal().V().skip(0).asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, RangeGlobalStep.class);

    var outcome = RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.skip).isNull();
    assertThat(ctx.limit).isNull();
  }

  /** A second range/limit after one already captured declines (no Phase-1 composition). */
  @Test
  public void secondLimit_declines() {
    var admin = graph.traversal().V().limit(5).limit(2).asAdmin();
    var ctx = seededContext();
    var first = cursorAt(admin, RangeGlobalStep.class);
    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(first, ctx))
        .isEqualTo(Outcome.ACCEPTED);

    var second = cursorAt(admin, RangeGlobalStep.class);
    // Advance past the first RangeGlobalStep already consumed conceptually — rebuild cursor after
    // first take by finding the remaining RangeGlobalStep.
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    var seen = 0;
    while (cursor.peek() != null) {
      if (cursor.peek() instanceof RangeGlobalStep) {
        if (seen == 1) {
          break;
        }
        seen++;
        cursor.take();
        continue;
      }
      cursor.take();
    }
    assertThat(cursor.peek()).isInstanceOf(RangeGlobalStep.class);
    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /** A second {@code order()} after one already captured declines. */
  @Test
  public void secondOrder_declines() {
    var admin = graph.traversal().V().order().by("name").order().by("age").asAdmin();
    var ctx = seededContext();
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    while (cursor.peek() != null && !(cursor.peek() instanceof OrderGlobalStep)) {
      cursor.take();
    }
    assertThat(OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.ACCEPTED);
    while (cursor.peek() != null && !(cursor.peek() instanceof OrderGlobalStep)) {
      cursor.take();
    }
    assertThat(cursor.peek()).isInstanceOf(OrderGlobalStep.class);
    assertThat(OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /** {@code order().by(__.out())} declines — unsupported key modulator. */
  @Test
  public void orderByUnsupportedModulator_declines() {
    var admin =
        graph
            .traversal()
            .V()
            .order()
            .by(org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.out())
            .asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, OrderGlobalStep.class);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.orderBy).isNull();
  }

  /** {@code order().by(T.id)} sorts by {@code alias.@rid}. */
  @Test
  public void orderByIdToken_sortsByRid() {
    var admin =
        graph
            .traversal()
            .V()
            .order()
            .by(org.apache.tinkerpop.gremlin.structure.T.id)
            .asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, OrderGlobalStep.class);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("@rid");
  }

  private static WalkerContext seededContext() {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  private static StepStreamCursor cursorAt(Traversal.Admin<?, ?> admin, Class<?> stepType) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    while (cursor.peek() != null) {
      if (stepType.isInstance(cursor.peek())) {
        return cursor;
      }
      cursor.take();
    }
    throw new AssertionError("Step not found: " + stepType.getSimpleName());
  }
}
