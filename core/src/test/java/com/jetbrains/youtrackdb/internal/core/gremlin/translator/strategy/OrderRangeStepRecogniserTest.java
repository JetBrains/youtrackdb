package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.sortedIds;
import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.sortedStrings;
import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link OrderGlobalStepRecogniser} and {@link RangeGlobalStepRecogniser}: ORDER BY
 * wiring, skip/limit translation, and decline paths.
 *
 * <p>The last group runs end to end instead — measured translator-on / translator-off equivalence
 * for the rule that a captured single-plan slice ends the walk. That rule is enforced by {@link
 * GremlinStepWalker}'s dispatch loop rather than by the recogniser, so a cursor-level assertion
 * cannot see it, and the defect it closes is a silently different multiset that only an executed
 * pair of arms exposes.
 */
public class OrderRangeStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

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

  /**
   * A real slice declines once an {@code ORDER BY} is on the context, and it sets neither clause on
   * the way out. MATCH's {@code ORDER BY} on a repeated key is a partial order, so a bound cutting
   * inside a tie group keeps an arbitrary member where Gremlin's stable sort keeps arrival order —
   * measured end to end in {@link #orderThenHopThenLimit_declinesAndReturnsNativeRows}. This is the
   * recogniser-level pin on the same guard.
   */
  @Test
  public void sliceAfterCapturedOrderBy_declines() {
    var admin = graph.traversal().V().order().by("name").limit(2).asAdmin();
    var ctx = seededContext();
    assertThat(
        OrderGlobalStepRecogniser.INSTANCE.recognize(cursorAt(admin, OrderGlobalStep.class),
            ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy).isNotNull();

    var outcome =
        RangeGlobalStepRecogniser.INSTANCE.recognize(cursorAt(admin, RangeGlobalStep.class), ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.limit).isNull();
    assertThat(ctx.skip).isNull();
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

  // ---------------------------------------------------------------------------
  // A captured single-plan cardinality clause ends the walk — measured translator-on /
  // translator-off equivalence, not a recogniser unit assertion.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().limit(2).out("knows")} declines and returns native's rows. Before the walker's
   * slice gate it compiled to the same statement as {@code g.V().out("knows").limit(2)} and sliced
   * the hop's output: two rows against native's zero or three, depending on where the hub landed in
   * the scan.
   */
  @Test
  public void limitThenHop_declinesAndReturnsNativeRows() {
    seedSingleHub();
    assertClauseThenStepDeclines(
        "g.V().limit(2).out(knows)",
        () -> graph.traversal().V().limit(2).out("knows"),
        () -> graph.traversal().V().out("knows").limit(2));
  }

  /**
   * The skip half of the same defect: {@code g.V().skip(2).out("knows")} dropped two rows of the
   * hop's output (leaving one) where native drops two vertices from the scan and hops from what is
   * left (leaving zero or three).
   */
  @Test
  public void skipThenHop_declinesAndReturnsNativeRows() {
    seedSingleHub();
    assertClauseThenStepDeclines(
        "g.V().skip(2).out(knows)",
        () -> graph.traversal().V().skip(2).out("knows"),
        () -> graph.traversal().V().out("knows").skip(2));
  }

  /**
   * The gate keys on position, not on shape: the same {@code limit} still translates when it is the
   * walk's last step. The bound is three — the hop's full output — so both arms return every row
   * and the comparison does not rest on the two pipelines agreeing about which rows come first.
   */
  @Test
  public void hopThenLimit_stillTranslates() {
    seedSingleHub();
    assertTranslatesAndMatchesNative(
        "g.V().out(knows).limit(3)", () -> graph.traversal().V().out("knows").limit(3));
  }

  /**
   * A slice that normalises away to nothing does not arm the gate. {@code skip(0)} selects no
   * position, so the recogniser accepts it without setting a clause and the following hop still
   * translates — the carve-out that keeps the gate from declining a shape it has no reason to.
   */
  @Test
  public void noopSkipThenHop_stillTranslates() {
    seedSingleHub();
    assertTranslatesAndMatchesNative(
        "g.V().skip(0).out(knows)", () -> graph.traversal().V().skip(0).out("knows"));
  }

  /**
   * The mirror ordering: a slice <em>behind</em> a row-dropping projection. {@code values(age)}
   * drops property-less rows through post-plan shaping, so a statement-level {@code LIMIT 1} counted
   * a row the drop then removed and returned nothing, where native drops first and returns the one
   * age. Both arms enumerate rows in the same order, so this is not order luck — with {@code age}
   * on the vertex that scans last, the translated arm's {@code LIMIT 1} always lands on a
   * property-less row.
   */
  @Test
  public void valuesThenLimit_declinesAndReturnsNativeRows() {
    seedAgeOnLastScannedVertex();
    assertClauseThenStepDeclines(
        "g.V().values(age).limit(1)",
        () -> graph.traversal().V().values("age").limit(1),
        () -> graph.traversal().V().limit(1).values("age"));
  }

  /** The skip half of the same defect, and it errs in the opposite direction: the translated arm
   *  kept the one age where native, having dropped first, has nothing left to skip past. */
  @Test
  public void valuesThenSkip_declinesAndReturnsNativeRows() {
    seedAgeOnLastScannedVertex();
    assertClauseThenStepDeclines(
        "g.V().values(age).skip(1)",
        () -> graph.traversal().V().values("age").skip(1),
        () -> graph.traversal().V().skip(1).values("age"));
  }

  /**
   * A real slice declines once an {@code ORDER BY} has been captured, and this is the shape where
   * the divergence reaches the row set rather than only its order. Two hubs of two targets each,
   * seeded so insertion order and sorted order disagree, give {@code order().by(name).out(knows)}
   * four rows in two tie groups of two — the sort key is the hub, so a hub's two targets tie.
   * {@code limit(3)} cuts inside the second tie group, and before the decline the translated arm
   * kept {@code ZedTarget1} where native kept {@code ZedTarget2}: a different row, silently,
   * under a switch that defaults on.
   *
   * <p>The comparison is ordered because {@code order()} makes the sequence the answer. The control
   * the helper takes is the same traversal without the {@code order()} prefix, which still
   * translates — so the decline is attributable to the captured {@code ORDER BY} and not to some
   * other gate this path crosses.
   */
  @Test
  public void orderThenHopThenLimit_declinesAndReturnsNativeRows() {
    seedTwoHubsWithTiedSortKey();
    assertOrderedSliceDeclines(
        "g.V().order().by(name).out(knows).limit(3).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows").limit(3).values("name"),
        () -> graph.traversal().V().out("knows").limit(3).values("name"));
  }

  /**
   * The {@code ORDER BY} decline is keyed on a real slice, exactly as the drop-on-absent one is: a
   * {@code skip(0)} selects no position, so it can cut into no tie group and rides through. This
   * pins where the new guard sits in {@link RangeGlobalStepRecogniser#recognize} — moving it above
   * the no-op normalisation would decline this shape.
   */
  @Test
  public void orderThenNoopSliceThenValues_stillTranslates() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeValues(
        "g.V().order().by(name).skip(0).values(name)",
        () -> graph.traversal().V().order().by("name").skip(0).values("name"));
  }

  /**
   * {@code dedup()} captures {@code RETURN DISTINCT}, which MATCH also applies after the pattern, so
   * a hop after it reads a stream the {@code DISTINCT} has not collapsed yet:
   * {@code g.V().in("knows").dedup().out("knows")} returned two rows against native's three, the
   * duplicate being the shared target reached from both of its in-neighbours.
   */
  @Test
  public void dedupThenHop_declinesAndReturnsNativeRows() {
    seedSharedTargetWithDuplicateNames();
    assertClauseThenStepDeclines(
        "g.V().in(knows).dedup().out(knows)",
        () -> graph.traversal().V().in("knows").dedup().out("knows"),
        () -> graph.traversal().V().in("knows").out("knows").dedup());
  }

  /**
   * The allow-list holds across {@code DISTINCT} too, and the reason rests on a detail:
   * {@code values(k)} projects the boundary entity alongside the value, so {@code RETURN DISTINCT}
   * ranges over {@code (entity, name)} and cannot collapse two distinct vertices that share a name
   * — which is exactly what native {@code dedup()} on elements also refuses to do. The fixture
   * holds two differently-identified vertices both named {@code Dup}, both reached by the hop, so a
   * {@code DISTINCT} that ranged over the name alone would return one row here instead of two.
   */
  @Test
  public void dedupThenValues_stillTranslates() {
    seedSharedTargetWithDuplicateNames();
    assertTranslatesAndMatchesNativeValues(
        "g.V().in(knows).dedup().values(name)",
        () -> graph.traversal().V().in("knows").dedup().values("name"));
  }

  /**
   * A pure projection is the exception the gate allows: {@code values(k)} writes RETURN columns and
   * result shaping, both of which land after the statement's {@code LIMIT}, so it keeps
   * translating. The bound is ten against five vertices, so every row survives the slice and the
   * comparison does not turn on the two pipelines agreeing about row order.
   */
  @Test
  public void sliceThenValues_stillTranslates() {
    seedSingleHub();
    assertTranslatesAndMatchesNativeValues(
        "g.V().limit(10).values(name)", () -> graph.traversal().V().limit(10).values("name"));
  }

  /**
   * The map projections are allowed for the same reason. Pinning one of them keeps the allow-list
   * from being read as a carve-out for {@code values(k)} alone.
   */
  @Test
  public void sliceThenValueMap_stillTranslates() {
    seedSingleHub();
    assertTranslatesAndMatchesNativeValues(
        "g.V().limit(10).valueMap(name)",
        () -> graph.traversal().V().limit(10).valueMap("name"));
  }

  /**
   * Five vertices, then {@code age} put on whichever one scans <em>last</em>, read back at seed time
   * rather than assumed. Scan order is not stable across JVM forks, so the fixture discovers it
   * instead of predicting it; what the cases need is only that the aged vertex is not among the
   * first rows a slice would take. Native drops the four property-less rows before slicing and so
   * always sees exactly one value; the translated arm slices first and lands on a property-less row.
   */
  private void seedAgeOnLastScannedVertex() {
    for (var i = 0; i < 5; i++) {
      graph.addVertex(T.label, "Person", "name", "Person" + i);
    }
    graph.tx().commit();

    var original = translatorEnabled();
    try {
      setTranslatorEnabled(false);
      var scanned = graph.traversal().V().toList();
      assertThat(scanned).as("fixture must seed five vertices").hasSize(5);
      ((Vertex) scanned.get(scanned.size() - 1)).property("age", 44);
      graph.tx().commit();
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Two vertices pointing at one shared target, which in turn points at a leaf. {@code in(knows)}
   * reaches the shared target twice — once from each in-neighbour — which is the duplicate
   * {@code dedup()} exists to remove and the reason a hop after it sees a different row count than
   * a {@code DISTINCT} applied at the end. The two in-neighbours carry the <em>same</em> name under
   * different identities, so the fixture separately catches a {@code DISTINCT} that ranged over a
   * projected value instead of over the element.
   */
  private void seedSharedTargetWithDuplicateNames() {
    var first = graph.addVertex(T.label, "Person", "name", "Dup");
    var second = graph.addVertex(T.label, "Person", "name", "Dup");
    var shared = graph.addVertex(T.label, "Person", "name", "Shared");
    var leaf = graph.addVertex(T.label, "Person", "name", "Leaf");
    first.addEdge("knows", shared);
    second.addEdge("knows", shared);
    shared.addEdge("knows", leaf);
    graph.tx().commit();
  }

  /**
   * Five vertices where exactly one — the hub — carries outgoing {@code knows} edges, three of
   * them. That asymmetry is what makes the slice-then-hop cases discriminate under any scan order:
   * the hop's full output is three rows, so a statement-level {@code LIMIT 2} yields two and a
   * {@code SKIP 2} yields one, while native slices the scan first and so reaches either all three
   * of the hub's neighbours or none of them. Two and one are both unreachable natively, so neither
   * case can pass by accident on a lucky scan order.
   */
  private void seedSingleHub() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    var ann = graph.addVertex(T.label, "Person", "name", "Ann");
    var ben = graph.addVertex(T.label, "Person", "name", "Ben");
    var cal = graph.addVertex(T.label, "Person", "name", "Cal");
    graph.addVertex(T.label, "Person", "name", "Isolate");
    hub.addEdge("knows", ann);
    hub.addEdge("knows", ben);
    hub.addEdge("knows", cal);
    graph.tx().commit();
  }

  /**
   * Two hubs of two {@code knows} targets each, seeded {@code Zed} before {@code Abe} so insertion
   * order and sorted order disagree — the branch has already retracted one measurement taken on a
   * fixture where they coincided, and a fixture that cannot tell RID order from sorted order cannot
   * witness an ordering defect. Sorting by {@code name} keys the hop's four rows on their hub, so
   * each hub's two targets tie; a {@code LIMIT 3} therefore cuts inside the second tie group, which
   * is the only place the two pipelines are free to disagree.
   */
  private void seedTwoHubsWithTiedSortKey() {
    var zed = graph.addVertex(T.label, "Person", "name", "Zed");
    var zedTargetOne = graph.addVertex(T.label, "Person", "name", "ZedTarget1");
    var zedTargetTwo = graph.addVertex(T.label, "Person", "name", "ZedTarget2");
    var abe = graph.addVertex(T.label, "Person", "name", "Abe");
    var abeTargetOne = graph.addVertex(T.label, "Person", "name", "AbeTarget1");
    var abeTargetTwo = graph.addVertex(T.label, "Person", "name", "AbeTarget2");
    zed.addEdge("knows", zedTargetOne);
    zed.addEdge("knows", zedTargetTwo);
    abe.addEdge("knows", abeTargetOne);
    abe.addEdge("knows", abeTargetTwo);
    graph.tx().commit();
  }

  /**
   * Asserts that {@code shape} — a captured cardinality clause followed by another step — declines
   * to the native pipeline and returns native's rows. Rows are compared by {@code toString}, which
   * carries the RID for an element and the value itself for a projection, so the same helper serves
   * both; sorting keeps the comparison a multiset one.
   *
   * <p>{@code clauseLastSpelling} is the traversal the pre-gate translation collapsed {@code shape}
   * onto, and its native result is asserted to <em>differ</em> from {@code shape}'s. That third
   * assertion is what makes the case a witness rather than a tautology: a decline compares native
   * against native, which agrees no matter what the fixture holds, so without it the test would
   * still pass on a fixture where both spellings mean the same thing and would pin nothing.
   *
   * <p>The off arm is built through {@code applyStrategies()} rather than drained blind, so its
   * boundary count is available and pinned at zero. That pin is what would catch a kill-switch flip
   * that never reached the traversal: the flag defaults on, and a stuck-on off arm would satisfy
   * every other assertion here.
   */
  private void assertClauseThenStepDeclines(
      String scenario,
      Supplier<GraphTraversal<?, ?>> shape,
      Supplier<GraphTraversal<?, ?>> clauseLastSpelling) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = shape.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onIds = sortedStrings(onAdmin.toList());

      setTranslatorEnabled(false);
      var offAdmin = shape.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offIds = sortedStrings(offAdmin.toList());
      var clauseLastIds = sortedStrings(drain(clauseLastSpelling));

      // Fixture precondition first: if the two spellings agree natively, the case witnesses
      // nothing and the two assertions below would hold for the wrong reason.
      assertThat(offIds)
          .as(scenario + ": the fixture must separate the two spellings, or the assertions below "
              + "witness nothing — native " + scenario + " and its slice-last spelling would then "
              + "be interchangeable and the shared statement harmless")
          .isNotEqualTo(clauseLastIds);
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onIds)
          .as(scenario + ": translator-on and translator-off multisets must match")
          .isEqualTo(offIds);
      assertThat(boundaryOn)
          .as(scenario + ": a step after a captured slice must decline the whole walk")
          .isEqualTo(0);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * The {@code ORDER BY} sibling of {@link #assertClauseThenStepDeclines}: {@code shape} carries an
   * {@code order()} before a real slice, must decline, and must return native's rows <em>in native's
   * order</em>. The comparison is ordered rather than a multiset one because {@code order()} makes
   * the sequence part of the answer, and the tie-group divergence this decline closes shows up in
   * the sequence even on the fixtures where it leaves the row set alone.
   *
   * <p>{@code sameShapeWithoutOrder} is the control, and it is what stops the case being satisfied
   * by some other gate: strip the {@code order()} prefix and the identical suffix must still
   * translate. Without it, a boundary count of zero would be consistent with the walk declining for
   * any reason at all, which is the failure shape this package keeps rediscovering.
   */
  private void assertOrderedSliceDeclines(
      String scenario,
      Supplier<GraphTraversal<?, ?>> shape,
      Supplier<GraphTraversal<?, ?>> sameShapeWithoutOrder) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = shape.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onRows = onAdmin.toList().stream().map(String::valueOf).toList();

      var controlAdmin = sameShapeWithoutOrder.get().asAdmin();
      controlAdmin.applyStrategies();
      var boundaryControl = countBoundarySteps(controlAdmin.getSteps());

      setTranslatorEnabled(false);
      var offAdmin = shape.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offRows = offAdmin.toList().stream().map(String::valueOf).toList();

      assertThat(boundaryControl)
          .as(scenario + ": the same suffix without the order() prefix must still translate, or "
              + "the decline below is not attributable to the captured ORDER BY")
          .isEqualTo(1);
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onRows)
          .as(scenario + " must return rows, or the comparison is vacuous")
          .isNotEmpty();
      assertThat(boundaryOn)
          .as(scenario + ": a real slice behind a captured ORDER BY must decline the whole walk")
          .isEqualTo(0);
      assertThat(onRows)
          .as(scenario + ": translator-on and translator-off rows must match in native's order")
          .isEqualTo(offRows);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * The complement: {@code shape} engages exactly one boundary step with the translator on and
   * returns the same non-empty multiset either way. The non-empty guard keeps the multiset
   * comparison from holding vacuously over two empty results, and the off arm's boundary count is
   * pinned at zero so a kill-switch flip that never reached the traversal cannot leave both arms
   * translated and the equality trivially true.
   */
  private void assertTranslatesAndMatchesNative(
      String scenario, Supplier<GraphTraversal<?, ?>> shape) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = shape.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onIds = sortedIds(onAdmin.toList());

      setTranslatorEnabled(false);
      var offAdmin = shape.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offIds = sortedIds(offAdmin.toList());

      assertThat(boundaryOn)
          .as(scenario + " must translate — exactly one boundary step")
          .isEqualTo(1);
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onIds).as(scenario + " must return rows, or the comparison is vacuous")
          .isNotEmpty();
      assertThat(onIds)
          .as(scenario + ": translator-on and translator-off multisets must match")
          .isEqualTo(offIds);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * The {@link #assertTranslatesAndMatchesNative} sibling for shapes whose rows are values rather
   * than elements, compared on {@code toString} because a projected value has no RID to sort on. It
   * carries the same off-arm boundary pin and for the same reason.
   */
  private void assertTranslatesAndMatchesNativeValues(
      String scenario, Supplier<GraphTraversal<?, ?>> shape) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = shape.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onRows = sortedStrings(onAdmin.toList());

      setTranslatorEnabled(false);
      var offAdmin = shape.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offRows = sortedStrings(offAdmin.toList());

      assertThat(boundaryOn)
          .as(scenario + " must translate — exactly one boundary step")
          .isEqualTo(1);
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onRows)
          .as(scenario + " must return rows, or the comparison is vacuous")
          .isNotEmpty();
      assertThat(onRows)
          .as(scenario + ": translator-on and translator-off multisets must match")
          .isEqualTo(offRows);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** Builds a fresh traversal from the supplier, applies strategies, and drains it. */
  private static List<?> drain(Supplier<GraphTraversal<?, ?>> shape) {
    var admin = shape.get().asAdmin();
    admin.applyStrategies();
    return admin.toList();
  }

  private boolean translatorEnabled() {
    return support.translatorEnabled();
  }

  private void setTranslatorEnabled(boolean enabled) {
    support.setTranslatorEnabled(enabled);
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
