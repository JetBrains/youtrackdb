package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
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

  /**
   * TinkerPop: {@code -1} on the high range emits remaining traversers after {@code low}. Maps to
   * skip-only ({@code SKIP 2}, no LIMIT) — same as {@code skip(2)}.
   */
  @Test
  public void rangeUnboundedHigh_setsSkipOnly() {
    var admin = graph.traversal().V().range(2, -1).asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, RangeGlobalStep.class);

    var outcome = RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.skip).isNotNull();
    assertThat(ctx.skip.toString()).contains("2");
    assertThat(ctx.limit).isNull();
  }

  /**
   * {@code range(Scope.local, …)} is {@code RangeLocalStep}: collection-local slicing, not global
   * MATCH SKIP/LIMIT. No recogniser is registered, so the production walker declines the whole
   * traversal (native Gremlin keeps the local semantics).
   */
  @Test
  public void rangeScopeLocal_walkerDeclines() {
    var admin =
        graph
            .traversal()
            .V()
            .valueMap()
            .range(org.apache.tinkerpop.gremlin.process.traversal.Scope.local, 1, 2)
            .asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
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

  /**
   * A second post-union range declines the same way the single-plan path refuses a second {@code
   * limit}: there is no composition rule for two slices over one concatenation. This is asserted
   * against the recogniser rather than end to end because TinkerPop folds two adjacent {@code
   * limit} steps into one before any strategy sees them, so the shape is unreachable from a
   * strategy-applied traversal.
   *
   * <p>The fixture carries the {@code count()} so that the existing-op check is the <em>only</em>
   * surviving reason to decline. A bare {@code limit(1)} would also fail the positional gate, and
   * the test would then stay green with the check it exists to pin deleted.
   */
  @Test
  public void secondPostUnionRange_declines() {
    var ctx = unionCarrierContext();
    ctx.appendPostConcatOp(new PostConcatOp.Range(0L, 2L));
    var admin = graph.traversal().V().limit(1).count().asAdmin();

    var cursor = cursorAt(admin, RangeGlobalStep.class);

    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /**
   * A post-union range after a count declines: the count already collapsed the concatenation to one
   * scalar row, so there is nothing left to slice. The fixture's trailing {@code count()} keeps the
   * existing-op check the only surviving decline reason, as in the test above.
   */
  @Test
  public void postUnionRangeAfterCount_declines() {
    var ctx = unionCarrierContext();
    ctx.appendPostConcatOp(PostConcatOp.Count.INSTANCE);
    var admin = graph.traversal().V().limit(1).count().asAdmin();

    var cursor = cursorAt(admin, RangeGlobalStep.class);

    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /**
   * A post-union range whose {@code count()} follows immediately is accepted as a {@link
   * PostConcatOp.Range} rather than as SQL {@code SKIP}/{@code LIMIT} clauses: a union's slice
   * applies to the concatenation, so pushing it into each child would slice every arm separately.
   * {@code range(1, 3)} normalises to skip 1, limit {@code high - low}.
   */
  @Test
  public void postUnionRangeBeforeCount_appendsPostConcatOpAndLeavesSqlClausesAlone() {
    var ctx = unionCarrierContext();
    var admin = graph.traversal().V().range(1, 3).count().asAdmin();

    var cursor = cursorAt(admin, RangeGlobalStep.class);

    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.postConcatOps()).containsExactly(new PostConcatOp.Range(1L, 2L));
    assertThat(ctx.skip).as("a union slice must not become a per-child SQL SKIP").isNull();
    assertThat(ctx.limit).as("a union slice must not become a per-child SQL LIMIT").isNull();
  }

  /**
   * The same range without a {@code count()} behind it declines. The multi-plan boundary emits the
   * children back to back while native {@code union(...)} interleaves them, so a slice that survives
   * to the caller would hand back rows from positions native never put there. Only a following
   * count — which reduces the slice to a cardinality — is order-independent.
   */
  @Test
  public void postUnionRangeWithNoCountBehindIt_declines() {
    var ctx = unionCarrierContext();
    var admin = graph.traversal().V().range(1, 3).asAdmin();

    var cursor = cursorAt(admin, RangeGlobalStep.class);

    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.postConcatOps()).isEmpty();
  }

  /**
   * A step between the slice and the count declines too: {@code limit(2).dedup().count()} counts
   * the distinct rows of whichever two arrived first, which is exactly what the two orders disagree
   * about. Only an immediately following count qualifies.
   */
  @Test
  public void postUnionRangeWithCountBehindAnotherStep_declines() {
    var ctx = unionCarrierContext();
    var admin = graph.traversal().V().limit(2).dedup().count().asAdmin();

    var cursor = cursorAt(admin, RangeGlobalStep.class);

    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.postConcatOps()).isEmpty();
  }

  /**
   * The positional check the walker's pre-fork look-ahead reads answers {@code false} for a step
   * that is not a range shape at all. Production reaches it only through the class-keyed lookup,
   * which routes just the two range classes here; answering {@code false} rather than throwing keeps
   * a mis-registered class declining at the recogniser one fork later instead of failing the query.
   */
  @Test
  public void selectsPositionally_nonRangeStep_isFalse() {
    var admin = graph.traversal().V().count().asAdmin();

    assertThat(
        RangeGlobalStepRecogniser.INSTANCE.selectsPositionally(
            stepOf(admin, CountGlobalStep.class)))
        .isFalse();
  }

  /**
   * A {@code skip(0)} selects no position, so the same check calls it non-positional and the
   * look-ahead lets it reach the fork. This is the carve-out that keeps the look-ahead from being
   * stricter than {@link RangeGlobalStepRecogniser}, which accepts a normalised-away slice.
   */
  @Test
  public void selectsPositionally_noOpSkip_isFalse() {
    var admin = graph.traversal().V().skip(0).asAdmin();

    assertThat(
        RangeGlobalStepRecogniser.INSTANCE.selectsPositionally(
            stepOf(admin, RangeGlobalStep.class)))
        .isFalse();
  }

  /**
   * A negative low reaches the same check from the DSL, so the un-normalisable arm is live rather
   * than reachable only through a mis-registered step class: {@code RangeGlobalStep}'s constructor
   * rejects a range only when both bounds are set and {@code low > high}, which {@code skip(-5)}
   * ({@code low = -5}, {@code high = -1}) does not trip. The check answers {@code false}, so the
   * look-ahead applies no positional gate and lets the shape through to the recogniser, which
   * declines it at the same normalisation one fork later.
   */
  @Test
  public void selectsPositionally_negativeLow_isFalse() {
    var admin = graph.traversal().V().skip(-5).asAdmin();

    assertThat(
        RangeGlobalStepRecogniser.INSTANCE.selectsPositionally(
            stepOf(admin, RangeGlobalStep.class)))
        .isFalse();
  }

  /**
   * The complement at the recogniser: the same negative low declines post-union rather than
   * translating, so the look-ahead's {@code false} above costs a discarded fork and never a wrong
   * answer. {@code normalize} refuses the shape before the positional gate is consulted, which is
   * why the fixture needs no {@code count()} behind it.
   */
  @Test
  public void postUnionNegativeLow_declines() {
    var ctx = unionCarrierContext();
    var admin = graph.traversal().V().skip(-5).count().asAdmin();

    var cursor = cursorAt(admin, RangeGlobalStep.class);

    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.postConcatOps()).isEmpty();
  }

  /**
   * An unbounded high normalises to a skip-only {@link PostConcatOp.Range}: {@code range(1, -1)}
   * keeps the skip and carries {@code -1} through as the limit rather than collapsing to a no-op
   * the way {@code range(0, -1)} does. Pins the normalisation the end-to-end row count for this
   * shape would otherwise be the only claim about.
   */
  @Test
  public void postUnionRangeUnboundedHighBeforeCount_appendsSkipOnlyRange() {
    var ctx = unionCarrierContext();
    var admin = graph.traversal().V().range(1, -1).count().asAdmin();

    var cursor = cursorAt(admin, RangeGlobalStep.class);

    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.postConcatOps()).containsExactly(new PostConcatOp.Range(1L, -1L));
  }

  /** A post-union {@code skip(0)} is a no-op: accepted, but it appends no reduction. */
  @Test
  public void postUnionSkipZero_appendsNothing() {
    var ctx = unionCarrierContext();
    var admin = graph.traversal().V().skip(0).asAdmin();

    var cursor = cursorAt(admin, RangeGlobalStep.class);

    assertThat(RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.postConcatOps()).isEmpty();
  }

  /**
   * A seeded context that additionally carries a union carrier, which routes the recognisers down
   * their post-union branches. The stashed child is a bare single-alias plan — the branches under
   * test read the op list and the boundary alias, never the child's pattern.
   */
  private static WalkerContext unionCarrierContext() {
    var ctx = seededContext();
    ctx.stashUnionChildren(
        List.of(MatchPlanInputs.builder(new Pattern()).build()), List.of(Map.of()), List.of(true));
    return ctx;
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

  /**
   * The {@link #cursorAt} sibling for tests that want the step itself rather than a cursor parked on
   * it. Naming the type says which step the assertion means and fails with the same readable message
   * when a TinkerPop upgrade changes what the builder emits, where a bare list index would say
   * nothing and throw {@code IndexOutOfBoundsException}.
   */
  private static Step<?, ?> stepOf(Traversal.Admin<?, ?> admin, Class<?> stepType) {
    for (var step : admin.getSteps()) {
      if (stepType.isInstance(step)) {
        return step;
      }
    }
    throw new AssertionError("Step not found: " + stepType.getSimpleName());
  }
}
