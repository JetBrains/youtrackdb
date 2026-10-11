package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.sortedIds;
import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.sortedStrings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBOrderRidTieBreakStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.PBiPredicate;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.structure.Column;
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

    var outcome = recognizeOrder(admin, ctx);

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

    var outcome = recognizeOrder(admin, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.toString()).contains("name");
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("DESC");
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("@rid");
    assertThat(ctx.orderBy.getItems()).hasSize(2);
  }

  /** Multi-key {@code order().by(...).by(...)} emits multiple ORDER BY items. */
  @Test
  public void orderByMultiKey_emitsMultipleItems() {
    var admin =
        graph.traversal().V().order().by("age", Order.asc).by("name", Order.desc).asAdmin();
    var ctx = seededContext();

    var outcome = recognizeOrder(admin, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.getItems()).hasSize(3);
  }

  /** Every converted item carries explicit placement, including the trailing RID tie-break. */
  @Test
  public void orderByMultiKey_writesExplicitPlacementOnEveryItem() {
    var admin =
        graph.traversal().V().order().by("age", Order.asc).by("name", Order.desc).asAdmin();
    var ctx = seededContext();
    ctx.setOrderByNullsPlacements(ResolvedOrderByNullsPlacement.REVERSED);

    assertThat(recognizeOrder(admin, ctx)).isEqualTo(Outcome.ACCEPTED);

    assertThat(ctx.orderBy.getItems())
        .extracting(SQLOrderByItem::getNullOrdering)
        .containsExactly(
            SQLOrderByItem.NULLS_LAST,
            SQLOrderByItem.NULLS_FIRST,
            SQLOrderByItem.NULLS_FIRST);
    var ridItemText = new StringBuilder();
    ctx.orderBy.getItems().getLast().toString(Map.of(), ridItemText);
    assertThat(ridItemText).asString().containsIgnoringCase("@rid");
  }

  /**
   * A multi-key sort whose last key is a property named {@code id} receives the record identifier
   * item like any other element sort. {@link YTDBOrderRidTieBreakStrategy} used to skip this shape,
   * on the assumption that such a property is unique per class. Nothing in the engine declares that,
   * so duplicate values in it tie, and a tie no key breaks is what let the two arms answer different
   * sequences.
   */
  @Test
  public void orderByDateThenId_stillGainsRidTieBreak() {
    var admin = graph.traversal().V().order()
        .by("creationDate", Order.desc).by("id", Order.asc).asAdmin();
    var ctx = seededContext();

    var outcome = recognizeOrder(admin, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.getItems()).hasSize(3);
    assertThat(ctx.orderBy.toString()).contains("creationDate");
    assertThat(ctx.orderBy.toString()).contains(".id");
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("@rid");
  }

  /** A sole {@code order().by("id")} gains the same trailing {@code @rid} item, for one reason. */
  @Test
  public void orderByIdOnly_stillGainsRidTieBreak() {
    var admin = graph.traversal().V().order().by("id", Order.asc).asAdmin();
    var ctx = seededContext();

    var outcome = recognizeOrder(admin, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.getItems()).hasSize(2);
    assertThat(ctx.orderBy.toString()).contains(".id");
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("@rid");
  }

  // ---------------------------------------------------------------------------
  // The boundary output type gates the two record identifier mappings.
  // ---------------------------------------------------------------------------

  /**
   * A trailing identity modulator over a map boundary must not become {@code alias.@rid}. The row a
   * map boundary hands back is built from the RETURN projection and carries no identifier of its
   * own, so {@code @rid} would sort by the entity behind the row rather than by the row itself. The
   * gate refuses the mapping and the whole shape declines, which is safe because both arms then run
   * the native pipeline. {@code valueMap(name)} is what pins the map boundary in production, and the
   * bare {@code order()} behind it arrives with one synthetic identity slot.
   */
  @Test
  public void bareOrderOverMapBoundary_declinesWithNoRidItem() {
    var admin = graph.traversal().V().valueMap("name").order().asAdmin();
    var ctx = seededContext(BoundaryOutputType.MAP);

    var outcome = recognizeOrder(admin, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.orderBy).as("a map boundary must produce no record identifier sort item")
        .isNull();
  }

  /**
   * The scalar half of the same gate. A {@code count()} boundary emits one aggregate value, so there
   * is no record behind the row to identify, and the identity modulator of the following bare
   * {@code order()} must not claim one.
   */
  @Test
  public void bareOrderOverScalarBoundary_declinesWithNoRidItem() {
    var admin = graph.traversal().V().count().order().asAdmin();
    var ctx = seededContext(BoundaryOutputType.SCALAR);

    var outcome = recognizeOrder(admin, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.orderBy)
        .as("a scalar boundary must produce no record identifier sort item")
        .isNull();
  }

  /**
   * The same gate on the appended sort key rather than on a bare identity. The strategy installs its
   * record identifier key on the trailing slot of {@code order().by(name)}, which is asserted here as
   * a precondition so the decline below cannot pass on a shape that never carried the key. Over a map
   * boundary that key must not resolve to {@code @rid} either, so the shape declines.
   */
  @Test
  public void appendedRidSortKeyOverMapBoundary_declinesWithNoRidItem() {
    var admin = graph.traversal().V().order().by("name").asAdmin();
    applyOrderTieBreak(admin);
    assertThat(trailingOrderModulator(admin))
        .as("the strategy must have installed the record identifier key, or the gate is untested")
        .isInstanceOf(RecordIdSortKeyTraversal.class);
    var ctx = seededContext(BoundaryOutputType.MAP);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(
        cursorAt(admin, OrderGlobalStep.class), ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.orderBy).isNull();
  }

  /** The scalar half of the appended-key gate, with the same precondition on the trailing slot. */
  @Test
  public void appendedRidSortKeyOverScalarBoundary_declinesWithNoRidItem() {
    var admin = graph.traversal().V().order().by("name").asAdmin();
    applyOrderTieBreak(admin);
    assertThat(trailingOrderModulator(admin)).isInstanceOf(RecordIdSortKeyTraversal.class);
    var ctx = seededContext(BoundaryOutputType.SCALAR);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(
        cursorAt(admin, OrderGlobalStep.class), ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.orderBy).isNull();
  }

  /**
   * The control that makes the four declines above attributable to the boundary output type and to
   * nothing else: the identical shape over an element boundary is accepted and does emit the record
   * identifier item. Without it a gate that declined every order step would satisfy all four.
   */
  @Test
  public void appendedRidSortKeyOverElementBoundary_isAcceptedWithARidItem() {
    var admin = graph.traversal().V().order().by("name").asAdmin();
    var ctx = seededContext(BoundaryOutputType.ELEMENT);

    var outcome = recognizeOrder(admin, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.getItems()).hasSize(2);
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("@rid");
  }

  /**
   * The gate does not swallow the single-value boundary, which is the one non-element payload that
   * does resolve a sort key: {@code values(name).order()} sorts the projected names, so the recorded
   * property projection answers the identity modulator and no {@code @rid} item is emitted. This is
   * the "different sort item" outcome beside the four declines.
   */
  @Test
  public void bareOrderOverSingleValueBoundary_sortsByTheProjectedProperty() {
    var admin = graph.traversal().V().values("name").order().asAdmin();
    var ctx = seededContext(BoundaryOutputType.SINGLE_VALUE);
    ctx.setLastPropertyProjection(
        new RecognitionContext.PropertyProjection(
            BOUNDARY_ALIAS, "name", ByModulatorTranslator.aliasProperty(BOUNDARY_ALIAS, "name")));

    var outcome = recognizeOrder(admin, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.getItems()).hasSize(1);
    assertThat(ctx.orderBy.toString()).contains("name");
    assertThat(ctx.orderBy.toString()).doesNotContain("@rid");
  }

  /** {@code order().by(select('k').by(key))} resolves through {@link ByModulatorTranslator}. */
  @Test
  public void orderBySelectModulator_resolvesLabel() {
    var admin =
        graph
            .traversal()
            // The productive-order rewrite is opted out of for the same reason the translator is
            // switched off below: it wraps a traversal modulator in a coalesce, and this test
            // reads the modulator by hand. In production the translator runs BEFORE that rewrite,
            // so a recogniser always sees the raw modulator.
            .with(YTDBQueryConfigParam.orderIncludesMissingKey, false)
            .V()
            .outE("knows")
            .as("k")
            .inV()
            .order()
            .by(__.select("k").by("since"), Order.asc)
            .asAdmin();
    // Keep this unit test focused on OrderGlobalStepRecogniser: if the whole traversal becomes
    // fully translatable, GremlinToMatchStrategy splices the step list and cursorAt would not
    // find the intermediate OrderGlobalStep anymore.
    support.withTranslator(false, admin::applyStrategies);
    var ctx = seededContext();
    ctx.userLabelToAlias.put("k", "$g2m_edge_0");
    ctx.patternBuilder.registerUserLabel("$g2m_edge_0", "k");
    assertThat(recognizeOrder(admin, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderAllowsSliceOnCurrentBoundary())
        .as("foreign sort keys are allowed when boundary is unchanged")
        .isTrue();
  }

  /**
   * A real slice after {@code ORDER BY} on a foreign-alias comparator is accepted when boundary is
   * unchanged — same tie contract as boundary-only sorts.
   */
  @Test
  public void limitAfterOrderOnForeignSortKey_accepts() {
    var admin =
        graph
            .traversal()
            // Opted out of the productive-order rewrite for the reason given in
            // orderBySelectModulator_resolvesLabel: the recogniser is driven by hand here.
            .with(YTDBQueryConfigParam.orderIncludesMissingKey, false)
            .V()
            .outE("knows")
            .as("k")
            .inV()
            .order()
            .by(__.select("k").by("since"), Order.asc)
            .limit(2)
            .asAdmin();
    support.withTranslator(false, admin::applyStrategies);
    var ctx = seededContext();
    ctx.userLabelToAlias.put("k", "$g2m_edge_0");
    ctx.patternBuilder.registerUserLabel("$g2m_edge_0", "k");
    assertThat(recognizeOrder(admin, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderAllowsSliceOnCurrentBoundary()).isTrue();

    var outcome =
        RangeGlobalStepRecogniser.INSTANCE.recognize(cursorAt(admin, RangeGlobalStep.class), ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.limit).isNotNull();
    assertThat(ctx.limit.toString()).contains("2");
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

  /**
   * With {@code emitGroupEntries}, {@code order().by(Column.values/keys)} maps to ORDER BY
   * {@code value}/{@code key} even though a GROUP BY is captured.
   */
  @Test
  public void orderByColumnOverGroupEntries_ordersByValueThenKey() {
    var admin = graph.traversal().V()
        .order().by(Column.values, Order.desc).by(Column.keys, Order.asc)
        .asAdmin();
    var ctx = seededGroupEntryContext();
    var cursor = cursorAt(admin, OrderGlobalStep.class);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("value");
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("DESC");
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("key");
    assertThat(ctx.orderBy.toString()).containsIgnoringCase("ASC");
    assertThat(ctx.orderAllowsSliceOnCurrentBoundary()).isTrue();
  }

  /** Property {@code by("age")} over group entries declines — wrong semantics for Map.Entry rows. */
  @Test
  public void orderByPropertyOverGroupEntries_declines() {
    var admin = graph.traversal().V().order().by("age").asAdmin();
    var ctx = seededGroupEntryContext();
    var cursor = cursorAt(admin, OrderGlobalStep.class);

    var outcome = OrderGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.orderBy).isNull();
  }

  /** Without emitGroupEntries, order after GROUP BY still declines. */
  @Test
  public void orderAfterGroupByWithoutEntries_declines() {
    var admin = graph.traversal().V().order().by("name").asAdmin();
    var ctx = seededContext();
    ctx.setGroupBy(new SQLGroupBy(-1));

    var outcome = recognizeOrder(admin, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.orderBy).isNull();
  }

  /**
   * With emitGroupEntries but no ORDER BY, {@code limit(n)} declines — HashMap vs first-seen cut
   * sets would diverge.
   */
  @Test
  public void limitOverGroupEntries_withoutOrder_declines() {
    var admin = graph.traversal().V().limit(2).asAdmin();
    var ctx = seededGroupEntryContext();
    var cursor = cursorAt(admin, RangeGlobalStep.class);

    var outcome = RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.limit).isNull();
  }

  /**
   * Without emitGroupEntries, {@code limit(n)} after GROUP BY is a no-op on the single map native
   * emits — accepted without a SQL LIMIT on the GROUP BY rows.
   */
  @Test
  public void limitAfterGroupByWithoutEntries_isNoOp() {
    var admin = graph.traversal().V().limit(2).asAdmin();
    var ctx = seededContext();
    ctx.setGroupBy(new SQLGroupBy(-1));
    var cursor = cursorAt(admin, RangeGlobalStep.class);

    var outcome = RangeGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.limit).isNull();
    assertThat(ctx.skip).isNull();
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
   * A real slice behind a captured {@code ORDER BY} on the same boundary is accepted and writes
   * {@code LIMIT}. Direct recogniser invocation — end-to-end coverage is in the ordered-slice
   * section below. Element-stream equal-key ties are RID-total-ordered on both arms.
   */
  @Test
  public void sliceAfterCapturedOrderBy_acceptsAndSetsLimit() {
    var admin = graph.traversal().V().order().by("name").limit(2).asAdmin();
    var ctx = seededContext();
    assertThat(recognizeOrder(admin, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy).isNotNull();
    assertThat(ctx.orderAllowsSliceOnCurrentBoundary()).isTrue();

    var outcome =
        RangeGlobalStepRecogniser.INSTANCE.recognize(cursorAt(admin, RangeGlobalStep.class), ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.limit).isNotNull();
    assertThat(ctx.limit.toString()).contains("2");
    assertThat(ctx.skip).isNull();
  }

  /**
   * Labelled multi-key {@code ORDER BY} plus {@code LIMIT}: recogniser writes {@code LIMIT 3}.
   * End-to-end pin: {@link #orderByTiedDateThenUniqueIdThenLimit_translatesAndMatchesNativeOrder}.
   */
  @Test
  public void sliceAfterCapturedOrderByOnPerson_acceptsAndSetsLimit() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    var admin =
        graph.traversal().V().hasLabel("Person")
            .order().by("creationDate", Order.desc).by("id", Order.asc)
            .limit(3)
            .asAdmin();
    var ctx = seededPersonContext();
    assertThat(
        OrderGlobalStepRecogniser.INSTANCE.recognize(cursorAt(admin, OrderGlobalStep.class), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.orderBy).isNotNull();
    assertThat(ctx.orderAllowsSliceOnCurrentBoundary()).isTrue();

    var outcome =
        RangeGlobalStepRecogniser.INSTANCE.recognize(cursorAt(admin, RangeGlobalStep.class), ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.limit).isNotNull();
    assertThat(ctx.limit.toString()).contains("3");
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
    applyOrderTieBreak(admin);
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

    applyOrderTieBreak(admin);
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

    var outcome = recognizeOrder(admin, ctx);

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
   * A slice behind {@code values(age)} promotes the absence drop into a pattern conjunct, so
   * {@code LIMIT} counts survivors. On this fixture only the last-scanned vertex carries {@code age},
   * so both arms return that one value. The reverse spelling {@code limit(1).values(age)} still
   * translates and still means something else: it takes one row then drops, and on this fixture
   * that row has no {@code age}.
   */
  @Test
  public void valuesThenLimit_translatesAndCountsSurvivors() {
    seedAgeOnLastScannedVertex();
    assertTranslatesAndMatchesNativeValues(
        "g.V().values(age).limit(1)",
        () -> graph.traversal().V().values("age").limit(1));
    support.assertEquivalent(
        "g.V().limit(1).values(age) — reverse spelling, drop after the slice",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedStrings,
        () -> graph.traversal().V().limit(1).values("age"));
  }

  /**
   * The skip half of the same promotion. Native drops first and has nothing left to skip, so both
   * arms return empty. The reverse spelling {@code skip(1).values(age)} keeps the aged vertex (it
   * scans last) and is the non-empty control that the two spellings still differ.
   */
  @Test
  public void valuesThenSkip_translatesAndCountsSurvivors() {
    seedAgeOnLastScannedVertex();
    support.assertEquivalent(
        "g.V().values(age).skip(1)",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedStrings,
        () -> graph.traversal().V().values("age").skip(1));
    assertTranslatesAndMatchesNativeValues(
        "g.V().skip(1).values(age) — reverse spelling, drop after the skip",
        () -> graph.traversal().V().skip(1).values("age"));
  }

  /**
   * Ordered sources expand in VertexStep neighbour order, then {@code limit} cuts the flat stream —
   * including mid-hub. Two hubs of two targets each, seeded so insertion order and sorted order
   * disagree: {@code limit(3)} cuts inside Zed's tie group. Before ordered-expand, statement LIMIT
   * after a MATCH hop kept the wrong neighbour; now ON==OFF on membership and sequence.
   */
  @Test
  public void orderThenHopThenLimit_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out(knows).limit(3).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows").limit(3).values("name"));
  }

  /**
   * Same composition with {@code range}: skip+limit on the expanded neighbour stream, not statement
   * SKIP/LIMIT after a join.
   */
  @Test
  public void orderThenHopThenRange_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out(knows).range(1, 3).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows").range(1, 3).values("name"));
  }

  /**
   * Early-stop cut: {@code limit(1)} is smaller than the first hub's out-degree, so expand must not
   * need the second hub's neighbours for membership.
   */
  @Test
  public void orderThenHopThenLimit_earlyStopInsideFirstHub_translates() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out(knows).limit(1).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows").limit(1).values("name"));
  }

  /** {@code in} direction uses the same ordered-expand path as {@code out}. */
  @Test
  public void orderThenInThenLimit_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).in(knows).limit(2).values(name)",
        () -> graph.traversal().V().order().by("name").in("knows").limit(2).values("name"));
  }

  /** {@code both} direction uses the same ordered-expand path as {@code out}. */
  @Test
  public void orderThenBothThenLimit_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).both(knows).limit(3).values(name)",
        () -> graph.traversal().V().order().by("name").both("knows").limit(3).values("name"));
  }

  /**
   * Neighbour {@code has(key, value)} before the cut filters during ordered-expand, so membership
   * matches native {@code order().hop().has().limit}.
   */
  @Test
  public void orderThenHopThenHasThenLimit_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out(knows).has(name, AbeTarget1).limit(1).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows").has("name", "AbeTarget1")
            .limit(1).values("name"));
  }

  /**
   * {@code has} that keeps only the second neighbour of the first sorted hub: limit(1) must see the
   * filtered stream, not the raw expand order.
   */
  @Test
  public void orderThenHopThenHasThenLimit_filtersBeforeCut_translates() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out(knows).has(name, AbeTarget2).limit(1).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows").has("name", "AbeTarget2")
            .limit(1).values("name"));
  }

  /**
   * Reverse composition: statement {@code LIMIT} cuts ordered sources, then expand-only
   * list-shaping. On {@link #seedTwoHubsWithTiedSortKey()}, {@code limit(1)} keeps Abe only, so
   * both neighbours are Abe's — not the mid-hub cut of {@code order().out().limit(3)}. Bare
   * {@code limit().out()} without {@code order()} still declines ({@link #limitThenHop_declinesAndReturnsNativeRows}).
   */
  @Test
  public void orderThenLimitThenHop_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).limit(1).out(knows).values(name)",
        () -> graph.traversal().V().order().by("name").limit(1).out("knows").values("name"));
  }

  /**
   * Source slice then hop then neighbour {@code has}: filters apply on the expand stage after the
   * statement top-N, matching native {@code order().limit().out().has()}.
   */
  @Test
  public void orderThenLimitThenHopThenHas_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).limit(1).out(knows).has(name, AbeTarget2).values(name)",
        () -> graph.traversal().V().order().by("name").limit(1).out("knows")
            .has("name", "AbeTarget2").values("name"));
  }

  /** Both parents feed one sorted hub. The post-hop cut counts merged bulk, not edge rows. */
  @Test
  public void repeatedSortedHub_postHopLimitSelectsBulkCopies() {
    seedTwoParentsWithSharedHub();
    assertTranslatesAndMatchesNative("bulk copies at post-hop limit",
        () -> graph.traversal().V().hasLabel("BulkParent").out("toHub")
            .order().by("name").out("toChild").limit(2));
  }

  /** The op-side source limit counts merged copies before the shared hub expands. */
  @Test
  public void repeatedSortedHub_sourceLimitKeepsNativeExpansionOrder() {
    seedTwoParentsWithSharedHub();
    assertTranslatesAndMatchesNative("source limit before expansion",
        () -> graph.traversal().V().hasLabel("BulkParent").out("toHub")
            .order().by("name").limit(2).out("toChild"));
  }

  /** Parallel edges repeat a neighbour; both cut positions retain its native multiplicity. */
  @Test
  public void repeatedSourceAndNeighbour_bothSlicePlacementsKeepMultiplicity() {
    seedTwoParentsWithSharedHub();
    var hub = graph.traversal().V().hasLabel("BulkHub").next();
    var a = graph.traversal().V().has("name", "A").next();
    hub.addEdge("toChild", a);
    graph.tx().commit();

    support.assertEquivalent("repeated sources and parallel edges, hop then cut",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().hasLabel("BulkParent").out("toHub")
            .order().by("name").out("toChild").limit(3));
    support.assertEquivalent("repeated sources and parallel edges, cut then hop",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().hasLabel("BulkParent").out("toHub")
            .order().by("name").limit(2).out("toChild"));
  }

  /** An unmergeable sack keeps the [A,B,A] sequence instead of bulk-merging to [A,A,B]. */
  @Test
  public void repeatedSortedHub_unmergeableSackMatchesNative() {
    seedTwoParentsWithSharedHub();
    support.assertEquivalent("unmergeable sack retains unmerged sequence",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().withSack(1).V().hasLabel("BulkParent")
            .out("toHub").order().by("name").out("toChild").limit(3));
    var unmerged = orderedNames(graph.traversal().withSack(1).V().hasLabel("BulkParent")
        .out("toHub").order().by("name").out("toChild").limit(3).toList());
    assertThat(unmerged).hasSize(3);
    assertThat(unmerged.get(0)).isEqualTo(unmerged.get(2)).isNotEqualTo(unmerged.get(1));
  }

  /** A null-valued sack merges, while a live sack keeps repeated sources separate. */
  @Test
  public void runtimeSackValue_controlsBothOrderedSlicePlacements() {
    seedTwoParentsWithSharedHub();
    support.assertEquivalent("null sack merges before hop cut", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().withSack(() -> (Integer) null).V().hasLabel("BulkParent")
            .out("toHub").order().by("name").out("toChild").limit(3));
    support.assertEquivalent("null sack merges before source cut", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().withSack(() -> (Integer) null).V().hasLabel("BulkParent")
            .out("toHub").order().by("name").limit(2).out("toChild"));
    support.assertEquivalent("live sack does not merge source copies", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().withSack(() -> 1).V().hasLabel("BulkParent")
            .out("toHub").order().by("name").limit(2).out("toChild"));
    support.assertEquivalent("splitter clears sack before source merge", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().withSack((java.util.function.Supplier<Integer>) () -> 1,
            (java.util.function.UnaryOperator<Integer>) x -> null).V()
            .hasLabel("BulkParent").out("toHub").order().by("name")
            .out("toChild").limit(3));
    support.assertEquivalent("splitter clears sack before neighbour barrier",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().withSack((java.util.function.Supplier<Integer>) () -> 1,
            (java.util.function.UnaryOperator<Integer>) x -> null).V()
            .hasLabel("BulkParent").out("toHub").order().by("name")
            .out("toChild").barrier(3).limit(3));
  }

  /** Native [a,b,a] has different sliced rows when a size-three merge precedes the cut. */
  @Test
  public void orderedHopBarrier_beforeVersusAfterLimit_matchesNativeInBothPositions() {
    var first = graph.addVertex(T.label, "BarrierSource", "name", "1");
    var second = graph.addVertex(T.label, "BarrierSource", "name", "2");
    var third = graph.addVertex(T.label, "BarrierSource", "name", "3");
    var a = graph.addVertex(T.label, "BarrierChild", "name", "A");
    var b = graph.addVertex(T.label, "BarrierChild", "name", "B");
    first.addEdge("barrierEdge", a);
    second.addEdge("barrierEdge", b);
    third.addEdge("barrierEdge", a);
    graph.tx().commit();
    assertTranslatesAndMatchesNative("barrier after limit",
        () -> graph.traversal().V().hasLabel("BarrierSource").order().by("name")
            .out("barrierEdge").limit(2).barrier(3));
    assertTranslatesAndMatchesNative("barrier before limit",
        () -> graph.traversal().V().hasLabel("BarrierSource").order().by("name")
            .out("barrierEdge").barrier(3).limit(2));
  }

  /** A two-entry window ends before the last a; a three-entry window merges it before limit. */
  @Test
  public void orderedHopBarrier_windowBoundaryChangesTheNativeCut() {
    var first = graph.addVertex(T.label, "WindowSource", "name", "1");
    var second = graph.addVertex(T.label, "WindowSource", "name", "2");
    var third = graph.addVertex(T.label, "WindowSource", "name", "3");
    var a = graph.addVertex(T.label, "WindowChild", "name", "A");
    var b = graph.addVertex(T.label, "WindowChild", "name", "B");
    first.addEdge("windowEdge", a);
    second.addEdge("windowEdge", b);
    third.addEdge("windowEdge", a);
    graph.tx().commit();
    assertTranslatesAndMatchesNative("two distinct entries per window",
        () -> graph.traversal().V().hasLabel("WindowSource").order().by("name")
            .out("windowEdge").barrier(2).limit(2));
    assertTranslatesAndMatchesNative("three distinct entries per window",
        () -> graph.traversal().V().hasLabel("WindowSource").order().by("name")
            .out("windowEdge").barrier(3).limit(2));
  }

  /** Filtering X from [A,X,B,A] before the size-three window selects [A,A], not [A,B]. */
  @Test
  public void orderedHopBarrier_afterFilter_matchesNative() {
    var a = graph.addVertex(T.label, "FilteredChild", "name", "A");
    var x = graph.addVertex(T.label, "FilteredChild", "name", "X");
    var b = graph.addVertex(T.label, "FilteredChild", "name", "B");
    for (int i = 0; i < 4; i++) {
      var source = graph.addVertex(T.label, "FilteredSource", "name", Integer.toString(i));
      source.addEdge("filteredEdge", switch (i) {
        case 0, 3 -> a;
        case 1 -> x;
        default -> b;
      });
    }
    graph.tx().commit();
    support.assertEquivalent("filter before barrier window", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().V().hasLabel("FilteredSource").order().by("name")
            .out("filteredEdge").has("name", P.neq("X")).barrier(3).limit(2));
    assertThat(orderedNames(graph.traversal().V().hasLabel("FilteredSource").order().by("name")
        .out("filteredEdge").has("name", P.neq("X")).barrier(3).limit(2).toList()))
        .containsExactly("A", "A");
  }

  /** The op-side source cut runs before the hop and its following filter and barrier. */
  @Test
  public void orderedSourceSlice_followedByFilterAndBarrier_matchesNative() {
    seedTwoParentsWithSharedHub();
    assertTranslatesAndMatchesNative("source cut then filtered hop and barrier",
        () -> graph.traversal().V().hasLabel("BulkParent").out("toHub")
            .order().by("name").limit(2).out("toChild")
            .has("name", "A").barrier(2));
  }

  /** A source-side barrier after select runs before expansion and keeps the sorted source alias. */
  @Test
  public void orderedSourceSlice_selectThenHopWithBarrier_matchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNative("source select barrier then expanded neighbours",
        () -> graph.traversal().V().as("s").order().by("name").limit(1)
            .select("s").out("knows").barrier(2));
  }

  /** A labelled ancestor survives through an unlabelled source and permits neighbour merging. */
  @Test
  public void orderedHopBarrier_unlabelledSourceUsesOnlyLabelledPath() {
    var root = graph.addVertex(T.label, "MergeRoot", "name", "root");
    var a = graph.addVertex(T.label, "MergeChild", "name", "A");
    var b = graph.addVertex(T.label, "MergeChild", "name", "B");
    for (int i = 0; i < 3; i++) {
      var source = graph.addVertex(T.label, "MergeSource", "name", Integer.toString(i));
      root.addEdge("mergeSource", source);
      source.addEdge("mergeChild", i == 1 ? b : a);
    }
    graph.tx().commit();
    support.assertEquivalent("unlabelled source cannot split a labelled path",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().V().hasLabel("MergeRoot").as("r")
            .out("mergeSource").order().by("name").out("mergeChild")
            .barrier(3).limit(2));
    assertThat(orderedNames(graph.traversal().V().hasLabel("MergeRoot").as("r")
        .out("mergeSource").order().by("name").out("mergeChild")
        .barrier(3).limit(2).toList())).containsExactly("A", "A");
  }

  /** A label attached directly to order distinguishes paths even with a shared neighbour. */
  @Test
  public void orderedHopBarrier_orderStepLabelPreventsNeighbourMerge() {
    seedThreeNamedBarrierSources();
    support.assertEquivalent("order-step label survives the hop", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().V().hasLabel("BarrierSource").order().by("name")
            .as("s").out("barrierEdge").barrier(3).limit(2));
    assertThat(orderedNames(graph.traversal().V().hasLabel("BarrierSource").order().by("name")
        .as("s").out("barrierEdge").barrier(3).limit(2).toList()))
        .containsExactly("A", "B");
  }

  /** A later select retracts the earlier parent path before the barrier merges its hub. */
  @Test
  public void orderedHopBarrier_selectRetractionDropsParentIdentity() {
    seedTwoParentsWithSharedHub();
    support.assertEquivalent("select path retraction at the barrier", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().V().hasLabel("BulkParent").as("a")
            .out("toHub").as("s").order().by("name").select("s")
            .out("toChild").limit(2));
  }

  /** Labels attached to a source-side barrier survive into a later neighbour merge. */
  @Test
  public void labelledBarrierAfterOrder_preservesDistinctSourcePaths() {
    seedThreeNamedBarrierSources();
    support.assertEquivalent("source-side barrier label remains live after expansion",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        OrderRangeStepRecogniserTest::orderedNames,
        () -> graph.traversal().V().hasLabel("BarrierSource").order().by("name")
            .barrier(3).as("s").out("barrierEdge").barrier(3).limit(2));
    assertThat(orderedNames(graph.traversal().V().hasLabel("BarrierSource")
        .order().by("name").barrier(3).as("s").out("barrierEdge")
        .barrier(3).limit(2).toList())).containsExactly("A", "B");
  }

  /** Nonpositive barrier sizes use native execution at every admitted position. */
  @Test
  public void nonpositiveBarriers_declineAndKeepNativeResults() {
    seedThreeNamedBarrierSources();
    for (int size : new int[] {0, -1}) {
      support.assertEquivalent("after order with hop, size " + size, Recognition.DECLINED,
          Cardinality.MAY_BE_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
          () -> graph.traversal().V().hasLabel("BarrierSource").order().by("name")
              .out("barrierEdge").barrier(size).limit(2));
      support.assertEquivalent("after order without hop, size " + size, Recognition.DECLINED,
          Cardinality.MAY_BE_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
          () -> graph.traversal().V().hasLabel("BarrierSource").order().by("name")
              .barrier(size).limit(2));
      support.assertEquivalent("before count, size " + size, Recognition.DECLINED,
          Cardinality.NON_EMPTY, TranslatorEquivalenceSupport::sortedIdsOrValues,
          () -> graph.traversal().V().hasLabel("BarrierSource").order().by("name")
              .barrier(size).count());
      support.assertEquivalent("before groupCount, size " + size, Recognition.DECLINED,
          Cardinality.NON_EMPTY, TranslatorEquivalenceSupport::sortedIdsOrValues,
          () -> graph.traversal().V().hasLabel("BarrierSource").order().by("name")
              .barrier(size).groupCount());
      support.assertEquivalent("unsliced hop then barrier, size " + size, Recognition.DECLINED,
          Cardinality.MAY_BE_EMPTY, OrderRangeStepRecogniserTest::orderedNames,
          () -> graph.traversal().V().hasLabel("BarrierSource").order().by("name")
              .out("barrierEdge").barrier(size));
    }
    assertThat(graph.traversal().V().hasLabel("BarrierSource").toList()).hasSize(3);
  }

  /** Same ordered [A,B,A] fixture used by the labelled-path and zero-window regressions. */
  private void seedThreeNamedBarrierSources() {
    var a = graph.addVertex(T.label, "BarrierChild", "name", "A");
    var b = graph.addVertex(T.label, "BarrierChild", "name", "B");
    for (int i = 0; i < 3; i++) {
      var source = graph.addVertex(T.label, "BarrierSource", "name", Integer.toString(i));
      source.addEdge("barrierEdge", i == 1 ? b : a);
    }
    graph.tx().commit();
  }

  private static List<String> orderedNames(List<?> rows) {
    return rows.stream().map(row -> (Object) ((Vertex) row).value("name"))
        .map(String::valueOf).toList();
  }

  /** Parent matching includes a Child only in polymorphic mode on both ordered-expand routes. */
  @Test
  public void orderedHopParentLabel_usesExplicitAndSessionPolymorphism() {
    seedNativeNeighbourFilters();
    for (boolean mode : new boolean[] {true, false}) {
      var expected = mode ? List.of("Child", "Parent") : List.of("Parent");
      assertNeighbourFilterResult("explicit parent, hop cut, " + mode, expected,
          () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, mode)
              .V().has("name", "Source").order().by("name").out("knows")
              .hasLabel("FilterParent").limit(4).values("name"));
      assertNeighbourFilterResult("explicit parent, source cut, " + mode, expected,
          () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, mode)
              .V().has("name", "Source").order().by("name").limit(1).out("knows")
              .hasLabel("FilterParent").values("name"));
      withPolymorphicDefault(mode, () -> assertNeighbourFilterResult(
          "session parent, hop cut, " + mode, expected,
          () -> graph.traversal().V().has("name", "Source").order().by("name")
              .out("knows").hasLabel("FilterParent").limit(4).values("name")));
      withPolymorphicDefault(mode, () -> assertNeighbourFilterResult(
          "session parent, source cut, " + mode, expected,
          () -> graph.traversal().V().has("name", "Source").order().by("name")
              .limit(1).out("knows").hasLabel("FilterParent").values("name")));
    }
  }

  /** A polymorphic multi-label predicate stays admitted on both the op and MATCH flush routes. */
  @Test
  public void orderedHopMultiLabel_matchesChildAndOtherOnBothDeferredRoutes() {
    seedNativeNeighbourFilters();
    var expected = List.of("Child", "Other", "Parent");
    assertNeighbourFilterResult("multi-label hop cut", expected,
        () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V().has("name", "Source").order().by("name").out("knows")
            .hasLabel("FilterParent", "FilterOther").limit(4).values("name"));
    assertNeighbourFilterResult("multi-label source cut", expected,
        () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V().has("name", "Source").order().by("name").limit(1).out("knows")
            .hasLabel("FilterParent", "FilterOther").values("name"));
    assertNeighbourFilterResult("multi-label MATCH flush", expected,
        () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V().has("name", "Source").order().by("name").out("knows")
            .hasLabel("FilterParent", "FilterOther").values("name"));
  }

  /** Label filters from barrier-separated HasSteps AND on both ordered-expand placements and flush. */
  @Test
  public void orderedHopSeparateHasLabelSteps_andRatherThanOr() {
    seedNativeNeighbourFilters();
    for (boolean sourceCut : new boolean[] {false, true}) {
      assertNeighbourFilterResult("separate labels, source cut " + sourceCut, List.of(),
          () -> sourceCut
              ? graph.traversal().V().has("name", "Source").order().by("name")
                  .limit(1).out("knows").hasLabel("FilterParent").barrier()
                  .hasLabel("FilterOther").values("name")
              : graph.traversal().V().has("name", "Source").order().by("name")
                  .out("knows").hasLabel("FilterParent").barrier()
                  .hasLabel("FilterOther").limit(4).values("name"));
    }
    assertNeighbourFilterResult("separate labels, MATCH flush", List.of(),
        () -> graph.traversal().V().has("name", "Source").order().by("name")
            .out("knows").hasLabel("FilterParent").barrier()
            .hasLabel("FilterOther").values("name"));
  }

  /**
   * Chained hasLabel calls fold into one conflicting HasStep after a hop. Every ordered route
   * must decline this shape and return the empty native multiset.
   */
  @Test
  public void orderedHopMixedSingleAndMultiLabels_orWithinHasStep() {
    seedNativeNeighbourFilters();
    for (boolean poly : new boolean[] {true, false}) {
      assertNeighbourFilterDeclines("mixed labels, hop cut " + poly, List.of(),
          () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, poly)
              .V().has("name", "Source").order().by("name").out("knows")
              .hasLabel("FilterParent").hasLabel("FilterOther", "MissingFilter")
              .limit(4).values("name"));
      assertNeighbourFilterDeclines("mixed labels, source cut " + poly, List.of(),
          () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, poly)
              .V().has("name", "Source").order().by("name").limit(1).out("knows")
              .hasLabel("FilterParent").hasLabel("FilterOther", "MissingFilter")
              .values("name"));
      assertNeighbourFilterDeclines("mixed labels, MATCH flush " + poly, List.of(),
          () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, poly)
              .V().has("name", "Source").order().by("name").out("knows")
              .hasLabel("FilterParent").hasLabel("FilterOther", "MissingFilter")
              .values("name"));
    }
  }

  /** An absent label contributes no rows, while a present alternative still matches. */
  @Test
  public void orderedHopAbsentLabelAlternatives_matchNativeOnBothRoutes() {
    seedNativeNeighbourFilters();
    for (boolean poly : new boolean[] {true, false}) {
      for (boolean sourceCut : new boolean[] {false, true}) {
        assertNeighbourFilterResult("absent plus present " + poly + "/" + sourceCut,
            List.of("Other"),
            () -> sourceCut
                ? graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, poly)
                    .V().has("name", "Source").order().by("name").limit(1)
                    .out("knows").hasLabel("MissingFilter", "FilterOther").values("name")
                : graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, poly)
                    .V().has("name", "Source").order().by("name")
                    .out("knows").hasLabel("MissingFilter", "FilterOther")
                    .limit(4).values("name"));
      }
      assertNeighbourFilterResult("absent plus present flush " + poly, List.of("Other"),
          () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, poly)
              .V().has("name", "Source").order().by("name").out("knows")
              .hasLabel("MissingFilter", "FilterOther").values("name"));
      assertNeighbourFilterResult("all absent hop cut " + poly, List.of(),
          () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, poly)
              .V().has("name", "Source").order().by("name").out("knows")
              .hasLabel("MissingFilter", "MissingOther").limit(4).values("name"));
      assertNeighbourFilterResult("all absent source cut " + poly, List.of(),
          () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, poly)
              .V().has("name", "Source").order().by("name").limit(1).out("knows")
              .hasLabel("MissingFilter", "MissingOther").values("name"));
      assertNeighbourFilterResult("all absent flush " + poly, List.of(),
          () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, poly)
              .V().has("name", "Source").order().by("name").out("knows")
              .hasLabel("MissingFilter", "MissingOther").values("name"));
    }
  }

  /** A ci property must match different letter cases before the hop's positional cut. */
  @Test
  public void orderedHopCollatedProperty_matchesDifferentCaseOnBothExpandRoutes() {
    seedNativeNeighbourFilters();
    assertNeighbourFilterResult("ci hop cut", List.of("Child"),
        () -> graph.traversal().V().has("name", "Source").order().by("name")
            .out("knows").has("nickname", "mixed").limit(1).values("name"));
    assertNeighbourFilterResult("ci source cut", List.of("Child"),
        () -> graph.traversal().V().has("name", "Source").order().by("name")
            .limit(1).out("knows").has("nickname", "mixed").values("name"));
    assertNeighbourFilterResult("ci and multi-label", List.of("Child"),
        () -> graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V().has("name", "Source").order().by("name").out("knows")
            .hasLabel("FilterParent", "FilterOther").has("nickname", "mixed")
            .limit(1).values("name"));
  }

  /** Deferred MATCH uses the class from a previous label group to gate a singleton comparison. */
  @Test
  public void deferredKnownLabelThenBarrierThenSingleton_matchesNativeOnFlushAndCuts() {
    var target = session.createVertexClass("StepOneTarget");
    target.createProperty("name", PropertyType.STRING);
    target.createProperty("age", PropertyType.INTEGER);
    var source = graph.addVertex(T.label, "V", "name", "StepOneSource");
    var alice = graph.addVertex(T.label, "StepOneTarget", "name", "Alice", "age", 31);
    var bob = graph.addVertex(T.label, "StepOneTarget", "name", "Bob", "age", 20);
    source.addEdge("stepOneEdge", alice);
    source.addEdge("stepOneEdge", bob);
    graph.tx().commit();

    for (boolean sourceCut : new boolean[] {false, true}) {
      assertNeighbourFilterResult("singleton on source/hop cut " + sourceCut, List.of(),
          () -> sourceCut
              ? graph.traversal().V().has("name", "StepOneSource").order().by("name")
                  .limit(1).out("stepOneEdge").hasLabel("StepOneTarget").barrier()
                  .has("name", P.eq(List.of("Alice"))).values("name")
              : graph.traversal().V().has("name", "StepOneSource").order().by("name")
                  .out("stepOneEdge").hasLabel("StepOneTarget").barrier()
                  .has("name", P.eq(List.of("Alice"))).limit(2).values("name"));
      assertNeighbourFilterResult("numeric on source/hop cut " + sourceCut, List.of("Alice"),
          () -> sourceCut
              ? graph.traversal().V().has("name", "StepOneSource").order().by("name")
                  .limit(1).out("stepOneEdge").hasLabel("StepOneTarget").barrier()
                  .has("age", P.gt(25)).values("name")
              : graph.traversal().V().has("name", "StepOneSource").order().by("name")
                  .out("stepOneEdge").hasLabel("StepOneTarget").barrier()
                  .has("age", P.gt(25)).limit(2).values("name"));
    }
    assertNeighbourFilterResult("singleton MATCH flush", List.of(),
        () -> graph.traversal().V().has("name", "StepOneSource").order().by("name")
            .out("stepOneEdge").hasLabel("StepOneTarget").barrier()
            .has("name", P.eq(List.of("Alice"))).values("name"));
    assertNeighbourFilterResult("numeric MATCH flush", List.of("Alice"),
        () -> graph.traversal().V().has("name", "StepOneSource").order().by("name")
            .out("stepOneEdge").hasLabel("StepOneTarget").barrier()
            .has("age", P.gt(25)).values("name"));
  }

  /**
   * Multi-label filters translate across MATCH flush, hop cut and source cut. A folded single-label
   * filter conflicts and must decline. A barrier keeps the filters separate and must translate.
   * With polymorphism off, the single-label filter removes Child from the native rows.
   */
  @Test
  public void deferredMultiLabelWithOptionalNarrowing_matchesNativeOnFlushAndCuts() {
    seedNativeNeighbourFilters();
    for (boolean polymorphic : new boolean[] {true, false}) {
      withPolymorphicDefault(polymorphic, () -> {
        for (boolean narrow : new boolean[] {true, false}) {
          List<String> expected = !narrow || polymorphic ? List.of("Child") : List.of();
          for (boolean barrier : new boolean[] {false, true}) {
            for (int route = 0; route < 3; route++) {
              int selectedRoute = route;
              Supplier<GraphTraversal<?, ?>> shape = () -> {
                var traversal = graph.traversal().V().has("name", "Source")
                    .order().by("name");
                if (selectedRoute == 2) {
                  traversal = traversal.limit(1);
                }
                var targets = traversal.out("knows")
                    .hasLabel("FilterParent", "FilterChild");
                if (barrier) {
                  targets = targets.barrier(2);
                }
                if (narrow) {
                  targets = targets.hasLabel("FilterParent");
                }
                targets = targets.has("name", "Child");
                if (selectedRoute == 1) {
                  targets = targets.limit(2);
                }
                return targets.values("name");
              };
              String tag = "deferred multi poly=" + polymorphic + " narrow=" + narrow
                  + " barrier=" + barrier + " route=" + route;
              if (narrow && !barrier) {
                assertNeighbourFilterDeclines(tag, expected, shape);
              } else {
                assertNeighbourFilterResult(tag, expected, shape);
              }
            }
          }
        }
      });
    }
  }

  /** Unsupported MATCH predicates still use native ordered-expand filtering at either cut. */
  @Test
  public void deferredCustomPredicate_keepsBothOrderedExpandRoutes() {
    seedNativeNeighbourFilters();
    PBiPredicate<Object, Object> same = Object::equals;
    assertNeighbourFilterResult("custom hop cut", List.of("Child"),
        () -> graph.traversal().V().has("name", "Source").order().by("name")
            .out("knows").has("name", new P<>(same, "Child"))
            .limit(1).values("name"));
    assertNeighbourFilterResult("custom source cut", List.of("Child"),
        () -> graph.traversal().V().has("name", "Source").order().by("name")
            .limit(1).out("knows").has("name", new P<>(same, "Child"))
            .values("name"));
  }

  private void seedNativeNeighbourFilters() {
    var parent = session.createVertexClass("FilterParent");
    parent.createProperty("nickname", PropertyType.STRING).setCollate("ci");
    session.getSchema().createClass("FilterChild", parent);
    session.createVertexClass("FilterOther");
    var source = graph.addVertex(T.label, "FilterParent", "name", "Source");
    var child = graph.addVertex(T.label, "FilterChild", "name", "Child", "nickname", "MiXeD");
    var direct = graph.addVertex(T.label, "FilterParent", "name", "Parent");
    var other = graph.addVertex(T.label, "FilterOther", "name", "Other");
    source.addEdge("knows", child);
    source.addEdge("knows", direct);
    source.addEdge("knows", other);
    graph.tx().commit();
  }

  private void withPolymorphicDefault(boolean value, Runnable body) {
    var configuration = session.getConfiguration();
    var previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, value);
    try {
      body.run();
    } finally {
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

  private void assertNeighbourFilterResult(
      String scenario, List<String> expected, Supplier<GraphTraversal<?, ?>> shape) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(false);
      var nativeTraversal = shape.get().asAdmin();
      nativeTraversal.applyStrategies();
      assertThat(countBoundarySteps(nativeTraversal)).as(scenario + " native").isZero();
      var nativeRows = nativeTraversal.toList().stream().map(String::valueOf).sorted().toList();
      assertThat(nativeRows).as(scenario + " fixture").containsExactlyElementsOf(expected);

      setTranslatorEnabled(true);
      var translated = shape.get().asAdmin();
      translated.applyStrategies();
      assertThat(countBoundarySteps(translated)).as(scenario + " translated").isEqualTo(1);
      assertThat(translated.toList().stream().map(String::valueOf).sorted().toList())
          .as(scenario + " translated rows").isEqualTo(nativeRows);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** The translator must decline and return the native row multiset from the expected fixture. */
  private void assertNeighbourFilterDeclines(
      String scenario, List<String> expected, Supplier<GraphTraversal<?, ?>> shape) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(false);
      var nativeTraversal = shape.get().asAdmin();
      nativeTraversal.applyStrategies();
      assertThat(countBoundarySteps(nativeTraversal)).as(scenario + " native").isZero();
      var nativeRows = nativeTraversal.toList().stream().map(String::valueOf).sorted().toList();
      assertThat(nativeRows).as(scenario + " fixture").containsExactlyElementsOf(expected);

      setTranslatorEnabled(true);
      var translated = shape.get().asAdmin();
      translated.applyStrategies();
      assertThat(countBoundarySteps(translated)).as(scenario + " boundary").isZero();
      assertThat(translated.toList().stream().map(String::valueOf).sorted().toList())
          .as(scenario + " rows").isEqualTo(nativeRows);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** Distinct labelled parents of a shared source keep their path identities at order(). */
  @Test
  public void labelledSourceSlice_multiAliasSelectStillThrowsNativeCast() {
    seedTwoParentsWithSharedHub();
    assertSelectHopThrowsNativeCast("labelled source cut before map select",
        () -> graph.traversal().V().hasLabel("BulkParent").as("a")
            .out("toHub").as("s").order().by("name").limit(2)
            .select("a", "s").by("name").by("name").out("toChild"));
    assertSelectHopEmpty("labelled source cut before nonproductive map select",
        () -> graph.traversal().withoutStrategies(ProductiveByStrategy.class)
            .V().hasLabel("BulkParent").as("a").out("toHub").as("s")
            .order().by("name").range(1, 1).select("a", "s")
            .by("missing").by("missing").out("toChild"));
  }

  /** A foreign labelled alias is part of barrier equality after the expanded hop. */
  @Test
  public void labelledForeignAliasOrder_explicitBarrierKeepsNativeMultiplicity() {
    seedTwoParentsWithSharedHub();
    assertTranslatesAndMatchesNative("foreign alias sorted source with barrier",
        () -> graph.traversal().V().hasLabel("BulkParent").as("a")
            .out("toHub").as("s").order().by(__.select("a").by("name"))
            .out("toChild").barrier(3).limit(3));
  }

  /** A bare select of the sorted source still feeds an element to the hop after a source cut. */
  @Test
  public void selectedSortedSource_thenSliceThenHop_matchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "select(s).limit(1).out(knows)",
        () -> graph.traversal().V().as("s").order().by("name").limit(1)
            .select("s").out("knows").values("name"));
  }

  /** A bare select before the hop also keeps the sorted source for the post-hop cut. */
  @Test
  public void selectedSortedSource_thenHopThenSlice_matchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "select(s).out(knows).limit(1)",
        () -> graph.traversal().V().as("s").order().by("name")
            .select("s").out("knows").limit(1).values("name"));
  }

  /** A trailing neighbour filter preserves bare selected-source admission in both placements. */
  @Test
  public void selectedSortedSource_trailingHasBothPlacementsMatchNative() {
    seedTwoHubsWithTiedSortKey();
    support.assertEquivalent("bare select, cut before hop, trailing has",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().as("s").order().by("name").limit(1)
            .select("s").out("knows").has("name", "AbeTarget1"));
    support.assertEquivalent("bare select, cut after hop, trailing has",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedIds,
        () -> graph.traversal().V().as("s").order().by("name")
            .select("s").out("knows").has("name", "AbeTarget1").limit(1));
  }

  /** A scalar selected before the hop raises VertexStep's ClassCastException in both placements. */
  @Test
  public void modulatedSelectBeforeOrderedHop_bothPlacementsThrowNativeCast() {
    seedTwoHubsWithTiedSortKey();
    assertSelectHopThrowsNativeCast("hop then slice with scalar source",
        () -> graph.traversal().V().as("s").order().by("name")
            .select("s").by("name").out("knows").limit(1));
    assertSelectHopThrowsNativeCast("source slice then hop with scalar source",
        () -> graph.traversal().V().as("s").order().by("name").limit(1)
            .select("s").by("name").out("knows"));
  }

  /** A trailing filter cannot prevent VertexStep casting a scalar or map selected source. */
  @Test
  public void selectedNonVertex_trailingHasBothPlacementsThrowNativeCast() {
    seedTwoHubsWithTiedSortKey();
    assertSelectHopThrowsNativeCast("scalar select and trailing has, source cut",
        () -> graph.traversal().V().as("s").order().by("name").limit(1)
            .select("s").by("name").out("knows").has("name", "AbeTarget1"));
    assertSelectHopThrowsNativeCast("map select and trailing has, hop cut",
        () -> graph.traversal().V().as("s", "t").order().by("name")
            .select("s", "t").out("knows").has("name", "AbeTarget1").limit(1));
  }

  /** Empty post-hop cuts still pull the String payload before native RangeGlobalStep stops. */
  @Test
  public void zeroWidthPostHopSlice_scalarSelectThrowsNativeCast() {
    seedTwoHubsWithTiedSortKey();
    assertSelectHopThrowsNativeCast("scalar range(1,1)",
        () -> graph.traversal().V().as("s").order().by("name")
            .select("s").by("name").out("knows").range(1, 1));
    assertSelectHopThrowsNativeCast("scalar limit(0)",
        () -> graph.traversal().V().as("s").order().by("name")
            .select("s").by("name").out("knows").limit(0));
    assertSelectHopThrowsNativeCast("scalar skip(1).limit(0)",
        () -> graph.traversal().V().as("s").order().by("name")
            .select("s").by("name").out("knows").skip(1).limit(0));
  }

  /** A skipped zero-width cut still pulls its next neighbour and runs its predicate. */
  @Test
  public void zeroWidthPostHopSlice_afterSkip_runsNativeSecondPredicate() {
    var source = graph.addVertex(T.label, "Person", "name", "Source");
    var first = graph.addVertex(T.label, "Person", "name", "First");
    var second = graph.addVertex(T.label, "Person", "name", "Second");
    source.addEdge("knows", first);
    source.addEdge("knows", second);
    graph.tx().commit();

    for (boolean translated : new boolean[] {false, true}) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var predicate = new org.apache.tinkerpop.gremlin.process.traversal.P<>(
          (org.apache.tinkerpop.gremlin.process.traversal.PBiPredicate<Object, Object>) (a, b) -> {
            if (calls.incrementAndGet() == 2) {
              throw new IllegalStateException("second neighbour pulled");
            }
            return true;
          }, "unused");
      var original = translatorEnabled();
      try {
        setTranslatorEnabled(translated);
        var traversal = graph.traversal().V().has("name", "Source")
            .order().by("name").out("knows").has("name", predicate).range(1, 1)
            .asAdmin();
        traversal.applyStrategies();
        assertThat(countBoundarySteps(traversal.getSteps()))
            .as("translated=" + translated).isEqualTo(translated ? 1 : 0);
        assertThatThrownBy(traversal::toList).isInstanceOf(IllegalStateException.class)
            .hasMessage("second neighbour pulled");
        assertThat(calls.get()).isEqualTo(2);
      } finally {
        setTranslatorEnabled(original);
      }
    }
  }

  /** A multi-label map is cast even when the following cut emits no rows. */
  @Test
  public void zeroWidthPostHopSlice_multiLabelSelectThrowsNativeCast() {
    seedTwoHubsWithTiedSortKey();
    assertSelectHopThrowsNativeCast("map range(1,1)",
        () -> graph.traversal().V().as("s", "t").order().by("name")
            .select("s", "t").out("knows").range(1, 1));
    assertSelectHopThrowsNativeCast("map limit(0)",
        () -> graph.traversal().V().as("s", "t").order().by("name")
            .select("s", "t").out("knows").limit(0));
  }

  /** Empty sources and nonproductive selects never reach VertexStep's cast. */
  @Test
  public void zeroWidthPostHopSlice_emptyUpstreamDoesNotCast() {
    seedTwoHubsWithTiedSortKey();
    assertSelectHopEmpty("empty source before scalar range(1,1)",
        () -> graph.traversal().V().has("name", "Absent").as("s").order().by("name")
            .select("s").by("name").out("knows").range(1, 1));
    assertSelectHopEmpty("nonproductive select before range(1,1)",
        () -> graph.traversal().withoutStrategies(ProductiveByStrategy.class)
            .V().as("s").order().by("name").select("s").by("missing")
            .out("knows").range(1, 1));
    assertSelectHopEmpty("empty source slice before scalar hop",
        () -> graph.traversal().V().as("s").order().by("name").limit(0)
            .select("s").by("name").out("knows"));
  }

  /** A skipped, zero-width source cut stops before projecting the selected non-vertex payload. */
  @Test
  public void zeroWidthSourceSlice_afterSkipDoesNotCastSelectedPayload() {
    seedTwoHubsWithTiedSortKey();
    assertSelectHopEmpty("scalar select after source range(1,1)",
        () -> graph.traversal().V().as("s").order().by("name").range(1, 1)
            .select("s").by("name").out("knows"));
    assertSelectHopEmpty("map select after source skip(1).limit(0)",
        () -> graph.traversal().V().as("s", "t").order().by("name")
            .skip(1).limit(0).select("s", "t").out("knows"));
    assertSelectHopEmpty("nonproductive select after source range(1,1)",
        () -> graph.traversal().withoutStrategies(ProductiveByStrategy.class)
            .V().as("s").order().by("name").range(1, 1)
            .select("s").by("missing").out("knows"));
  }

  /** A zero-width source cut with a skip never reaches the post-hop predicate. */
  @Test
  public void zeroWidthSourceSlice_afterSkipDoesNotRunNeighbourPredicate() {
    seedTwoHubsWithTiedSortKey();
    for (boolean translated : new boolean[] {false, true}) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var predicate = new org.apache.tinkerpop.gremlin.process.traversal.P<>(
          (org.apache.tinkerpop.gremlin.process.traversal.PBiPredicate<Object, Object>) (a, b) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("neighbour predicate must not run");
          }, "unused");
      support.withTranslator(translated, () -> {
        var traversal = graph.traversal().V().order().by("name").range(1, 1)
            .out("knows").has("name", predicate).asAdmin();
        traversal.applyStrategies();
        assertThat(countBoundarySteps(traversal))
            .as("translator on=" + translated).isEqualTo(translated ? 1 : 0);
        assertThat(traversal.toList()).isEmpty();
      });
      assertThat(calls.get()).as("post-hop predicate calls, translator on=" + translated)
          .isZero();
    }
  }

  /** An actual vertex expands before the zero-width cut, but returns no rows. */
  @Test
  public void zeroWidthPostHopSlice_vertexPayloadReturnsEmpty() {
    seedTwoHubsWithTiedSortKey();
    assertSelectHopEmpty("vertex range(1,1)",
        () -> graph.traversal().V().as("s").order().by("name")
            .select("s").out("knows").range(1, 1));
    assertSelectHopEmpty("vertex limit(0)",
        () -> graph.traversal().V().as("s").order().by("name")
            .select("s").out("knows").limit(0));
  }

  /** A multi-label selected map cannot be cast to Vertex in either ordered-hop placement. */
  @Test
  public void multiLabelSelectBeforeOrderedHop_bothPlacementsThrowNativeCast() {
    seedTwoHubsWithTiedSortKey();
    assertSelectHopThrowsNativeCast("hop then slice with map source",
        () -> graph.traversal().V().as("s", "t").order().by("name")
            .select("s", "t").out("knows").limit(1));
    assertSelectHopThrowsNativeCast("source slice then hop with map source",
        () -> graph.traversal().V().as("s", "t").order().by("name").limit(1)
            .select("s", "t").out("knows"));
  }

  /** Nonproductive by(missing) drops sources before VertexStep can cast a payload. */
  @Test
  public void nonproductiveSelectBeforeOrderedHop_bothPlacementsAreEmpty() {
    seedTwoHubsWithTiedSortKey();
    assertSelectHopEmpty("hop then slice after absent by(missing)",
        () -> graph.traversal().withoutStrategies(ProductiveByStrategy.class)
            .V().as("s").order().by("name").select("s").by("missing")
            .out("knows").limit(1));
    assertSelectHopEmpty("source slice then hop after absent by(missing)",
        () -> graph.traversal().withoutStrategies(ProductiveByStrategy.class)
            .V().as("s").order().by("name").limit(1).select("s").by("missing")
            .out("knows"));
  }

  /** A source with no outgoing edges keeps an empty translated result after selection and cut. */
  @Test
  public void selectedSortedSource_withNoNeighbours_returnsEmpty() {
    graph.addVertex(T.label, "Person", "name", "Abe");
    graph.tx().commit();
    assertTranslatesAndMatchesNativeValuesAllowEmpty(
        "selected source has no outgoing neighbours",
        () -> graph.traversal().V().as("s").order().by("name").limit(1)
            .select("s").out("knows").values("name"));
  }

  /** A second hop stays excluded even when the source carries a select label. */
  @Test
  public void selectedSortedSource_twoHops_declinesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    var first = graph.traversal().V().has("name", "AbeTarget1").next();
    var second = graph.traversal().V().has("name", "ZedTarget1").next();
    first.addEdge("knows", second);
    graph.tx().commit();
    assertOrderedSliceDeclinesWithRemainingSteps(
        "selected source followed by two hops",
        () -> graph.traversal().V().as("s").order().by("name")
            .select("s").out("knows").out("knows").limit(1).values("name"));
  }

  /** Native select reads a side effect before a same-named path label, even with a source cut. */
  @Test
  public void sideEffectCollidingWithSelectedSource_declinesAndMatchesNative() {
    graph.addVertex(T.label, "Person", "name", "Abe", "n", 1);
    var other = graph.addVertex(T.label, "Person", "name", "Bob", "n", 2);
    var target = graph.addVertex(T.label, "Person", "name", "Target");
    other.addEdge("knows", target);
    graph.tx().commit();
    assertOrderedSliceDeclinesWithRemainingSteps(
        "side effect takes precedence over the selected source label",
        () -> graph.traversal().withSideEffect("s", other).V().has("n", 1).as("s")
            .order().by("n").limit(1).select("s").out("knows").values("name"));
  }

  /** A warm label-select template must not hide side-effect precedence in a later traversal. */
  @Test
  public void sideEffectCollisionAfterPlainSelectWarmup_stillDeclines() {
    graph.addVertex(T.label, "Person", "name", "Abe");
    var other = graph.addVertex(T.label, "Person", "name", "Bob");
    var target = graph.addVertex(T.label, "Person", "name", "Target");
    other.addEdge("knows", target);
    graph.tx().commit();
    assertTranslatesAndMatchesNativeValues(
        "plain select before side-effect collision",
        () -> graph.traversal().V().has("name", "Abe").as("s").select("s").values("name"));
    assertDeclinesOverTheSameNativeRows(
        "same select key is also a side effect",
        () -> graph.traversal().withSideEffect("s", other).V().has("name", "Abe")
            .as("s").select("s").values("name"));
  }

  /** A multi-key SelectStep also reads side effects before path labels. */
  @Test
  public void multiKeySelectSideEffectCollision_declines() {
    graph.addVertex(T.label, "Person", "name", "Abe");
    graph.tx().commit();
    assertDeclinesOverTheSameNativeRows("multi-key select reads a side effect",
        () -> graph.traversal().withSideEffect("s", "value").V().as("s", "t")
            .select("s", "t"));
  }

  /** Collision detection descends into a where child's select before a cached walk can start. */
  @Test
  public void childSelectSideEffectCollision_declines() {
    graph.addVertex(T.label, "Person", "name", "Abe");
    graph.tx().commit();
    var shape = graph.traversal().withSideEffect("s", "value").V().as("s")
        .where(__.select("s")).values("name").asAdmin();
    // withSideEffect installs its traversal-side key during strategy application; install it
    // explicitly for this direct pre-application check of the recursive child inspection.
    shape.getSideEffects().register("s", () -> "value", (a, b) -> b);
    assertThat(GremlinStepWalker.selectCollidesWithSideEffect(shape)).isTrue();
    assertDeclinesOverTheSameNativeRows("child select reads a side effect",
        () -> graph.traversal().withSideEffect("s", "value").V().as("s")
            .where(__.select("s")).values("name"));
  }

  /** A side effect with a distinct key leaves ordinary select translation eligible. */
  @Test
  public void nonCollidingSideEffect_keepsSelectTranslated() {
    graph.addVertex(T.label, "Person", "name", "Abe");
    graph.tx().commit();
    support.assertEquivalent("non-colliding side effect",
        Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedStrings,
        () -> graph.traversal().withSideEffect("other", "value").V().as("s")
            .select("s").values("name"));
  }

  /** {@code skip} half of source-slice-then-hop: drop first sorted hub, expand from the rest. */
  @Test
  public void orderThenSkipThenHop_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).skip(1).out(knows).values(name)",
        () -> graph.traversal().V().order().by("name").skip(1).out("knows").values("name"));
  }

  /**
   * Non-equality {@code has} uses the same {@link org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer#test}
   * path as native.
   */
  @Test
  public void orderThenHopThenHasNeqThenLimit_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out(knows).has(name, neq(AbeTarget1)).limit(2).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows")
            .has("name", P.neq("AbeTarget1"))
            .limit(2).values("name"));
  }

  /** {@code hasLabel} before the cut filters neighbours via {@code HasContainer.test}. */
  @Test
  public void orderThenHopThenHasLabelThenLimit_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out(knows).hasLabel(Person).limit(2).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows").hasLabel("Person")
            .limit(2).values("name"));
  }

  /** {@code P.within} before the cut is deferred the same way as equality {@code has}. */
  @Test
  public void orderThenHopThenHasWithinThenLimit_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out(knows).has(name, within(AbeTarget1, AbeTarget2)).limit(1)"
            + ".values(name)",
        () -> graph.traversal().V().order().by("name").out("knows")
            .has("name", P.within("AbeTarget1", "AbeTarget2"))
            .limit(1).values("name"));
  }

  /**
   * No following slice: deferred hop flushes into MATCH, and neighbour {@code has} becomes a MATCH
   * filter on the target alias — {@code order().hop().has().values} without a cut.
   */
  @Test
  public void orderThenHopThenHasThenValues_flushesIntoMatchAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out(knows).has(name, AbeTarget1).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows")
            .has("name", "AbeTarget1").values("name"));
  }

  /**
   * Label-less {@code out()} uses the null edge-label expand arm (all edge types) on the
   * ordered-expand stage.
   */
  @Test
  public void orderThenBareOutThenLimit_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).out().limit(2).values(name)",
        () -> graph.traversal().V().order().by("name").out().limit(2).values("name"));
  }

  /**
   * Source-slice then hop then {@code values} on a sparse key: absent properties drop after expand
   * without filling the source top-N quota from later hubs.
   */
  @Test
  public void orderThenLimitThenHopThenValuesSparse_translatesAndMatchesNative() {
    seedTwoHubsWithTiedSortKey();
    // Only AbeTarget1 carries age — expand from Abe (limit 1) yields one value or empty if filtered.
    var abeTarget1 = graph.traversal().V().has("name", "AbeTarget1").next();
    abeTarget1.property("age", 31);
    graph.tx().commit();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(name).limit(1).out(knows).values(age)",
        () -> graph.traversal().V().order().by("name").limit(1).out("knows").values("age"));
  }

  /**
   * Two hops between order and slice stay declined (only a single deferred hop is in v1 scope).
   * Fixture adds a second-hop edge so the shape is non-empty on the native arm.
   */
  @Test
  public void orderThenTwoHopsThenLimit_stillDeclines() {
    seedTwoHubsWithTiedSortKey();
    // AbeTarget1 -> ZedTarget1 so out().out() yields at least one neighbour.
    var abeTarget1 = graph.traversal().V().has("name", "AbeTarget1").next();
    var zedTarget1 = graph.traversal().V().has("name", "ZedTarget1").next();
    abeTarget1.addEdge("knows", zedTarget1);
    graph.tx().commit();
    assertOrderedSliceDeclines(
        "g.V().order().by(name).out(knows).out(knows).limit(1).values(name)",
        () -> graph.traversal().V().order().by("name").out("knows").out("knows").limit(1)
            .values("name"),
        () -> graph.traversal().V().out("knows").out("knows").limit(1).values("name"));
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
   * The three sort-plus-slice-plus-projection spellings all decline, and the same prefix without the
   * slice still translates. Two orderings of the same three stages are covered — projection before
   * the sort ({@code values(name).order().limit(2)}, {@code values(name).order().range(1, 3)}) and
   * projection after the slice ({@code order().by(name).range(1, 3).values(name)}) — because the
   * gates that refuse them are different ones and a reader should not have to guess that the family
   * is closed on both sides.
   *
   * <p><b>Why the boundary count is the whole point here.</b> These spellings were once reported as
   * a translator-on / translator-off divergence over an indexed fixture with tied sort keys, and
   * the report was retired on the ground that both arms came back with the same rows. A declined
   * shape runs the native pipeline on both arms, so its rows agree whatever a translation would have
   * done: over a shape that declines, a row comparison holds by construction and settles nothing.
   * The assertions below are therefore engagement assertions with the row comparison riding along,
   * not the other way round — a change that re-admitted any of these three shapes would fail on the
   * boundary count first, which is the signal a row comparison cannot give.
   *
   * <p>The decline of the first two is keyed on the captured {@code ORDER BY}: after the drop-on-absent
   * promotion a slice behind {@code values(k)} would otherwise translate, so stripping the slice
   * (rather than the sort) is still the control that proves the fixture can engage a boundary step.
   * {@code order().by(name).range().values(name)} translates — same boundary, no hop; equal names
   * are RID-total-ordered on both arms.
   */
  @Test
  public void sortedSliceOverValues_declines_orderThenRangeTranslates() {
    seedHubWithReverseSortedTargets();

    assertDeclinesOverTheSameNativeRows(
        "g.V().out(knows).values(name).order().limit(2)",
        () -> graph.traversal().V().out("knows").values("name").order().limit(2));
    assertDeclinesOverTheSameNativeRows(
        "g.V().out(knows).values(name).order().range(1, 3)",
        () -> graph.traversal().V().out("knows").values("name").order().range(1, 3));

    // Distinct names on this fixture — sequence equality holds for the translating spelling.
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().out(knows).order().by(name).range(1, 3).values(name)",
        () -> graph.traversal().V().out("knows").order().by("name").range(1, 3).values("name"));

    support.assertEquivalent(
        "control: g.V().out(knows).values(name).order()",
        Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY,
        results -> results.stream().map(String::valueOf).toList(),
        () -> graph.traversal().V().out("knows").values("name").order());
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

  // ---------------------------------------------------------------------------
  // Ordered slice behind a captured ORDER BY on the current boundary (RID ties).
  // ---------------------------------------------------------------------------

  /**
   * {@code order().by(creationDate, desc).by(id, asc).limit(3)} translates. UNIQUE {@code id} makes
   * on/off sequences agree even when {@code creationDate} ties across the cut. LDBC multi-key
   * spelling; non-unique single-key twin:
   * {@link #orderByNonUniqueFirstNameThenLimit_translatesAndMatchesNativeOrder}.
   */
  @Test
  public void orderByTiedDateThenUniqueIdThenLimit_translatesAndMatchesNativeOrder() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().hasLabel(Person).order().by(creationDate, desc).by(id, asc).limit(3).values(id)",
        () -> graph.traversal().V().hasLabel("Person")
            .order().by("creationDate", Order.desc).by("id", Order.asc)
            .limit(3)
            .values("id"));
  }

  /**
   * The {@code skip} spelling of the same unique-{@code id} order. {@code skip(2)} over five people
   * ordered by unique {@code id} keeps a determined suffix, so both arms must return the same
   * sequence.
   */
  @Test
  public void orderByUniqueIdThenSkip_translatesAndMatchesNativeOrder() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().hasLabel(Person).order().by(id).skip(2).values(id)",
        () -> graph.traversal().V().hasLabel("Person").order().by("id").skip(2).values("id"));
  }

  /**
   * The {@code range} spelling of the same unique-{@code id} order. {@code range(1, 4)} keeps three
   * of the five unique-id rows in determined order.
   */
  @Test
  public void orderByUniqueIdThenRange_translatesAndMatchesNativeOrder() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().hasLabel(Person).order().by(id).range(1, 4).values(id)",
        () -> graph.traversal().V().hasLabel("Person").order().by("id").range(1, 4).values("id"));
  }

  /**
   * NOTUNIQUE {@code firstName} + {@code LIMIT 2} translates. Four Anns share the key across the
   * cut; {@code YTDBOrderRidTieBreakStrategy} pins which two survive, so on/off sequences agree.
   * Twin with UNIQUE {@code id}: {@link #orderByUniqueIdThenLimit_translates}.
   */
  @Test
  public void orderByNonUniqueFirstNameThenLimit_translatesAndMatchesNativeOrder() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().hasLabel(Person).order().by(firstName).limit(2).values(id)",
        () -> graph.traversal().V().hasLabel("Person").order().by("firstName").limit(2)
            .values("id"));
  }

  /**
   * Discriminating twin of
   * {@link #orderByNonUniqueFirstNameThenLimit_translatesAndMatchesNativeOrder}: UNIQUE {@code id}
   * makes the primary key alone separate rows before the RID secondary key runs.
   */
  @Test
  public void orderByUniqueIdThenLimit_translates() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().hasLabel(Person).order().by(id).limit(2).values(id)",
        () -> graph.traversal().V().hasLabel("Person").order().by("id").limit(2).values("id"));
  }

  /**
   * Hop between sort and slice: ordered-expand list-shaping, not statement LIMIT after join. Twin:
   * {@link #orderByUniqueIdThenLimit_translates}.
   */
  @Test
  public void orderByUniqueIdThenHopThenLimit_translates() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().hasLabel(Person).order().by(id).out(knows).limit(2).values(id)",
        () -> graph.traversal().V().hasLabel("Person").order().by("id").out("knows").limit(2)
            .values("id"));
  }

  /**
   * Discriminating twin of {@link #orderByUniqueIdThenHopThenLimit_translates} — sort and slice on
   * the hop target only (hop, then order, then limit).
   *
   * <p>Runs under the PORTABLE OPT-OUT. Under the shipped productive-order default the order key
   * emits no {@code IS DEFINED} conjunct, the sorted alias carries no filter at all, and the
   * planner then roots an INDEX-ORDERED scan on the unique {@code id} index. That scan preserves
   * the fan-in multiplicity, which
   * {@link #orderByUniqueIdOnFanInHopTarget_underDefault_keepsDuplicateRows} asserts.
   */
  @Test
  public void orderByUniqueIdOnHopTargetThenLimit_underPortableOptOut_translates() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    withOrderIncludesMissingKey(false, () -> assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().hasLabel(Person).as(src).out(knows).as(dst).hasLabel(Person)"
            + ".order().by(id).limit(2).values(id)",
        () -> graph.traversal().V().hasLabel("Person").as("src").out("knows").as("dst")
            .hasLabel("Person").order().by("id").limit(2).values("id")));
  }

  /**
   * CONTRACT. Under the shipped productive-order default a fan-in hop whose order key carries an
   * index keeps every row, so the translated arm matches native Gremlin exactly.
   *
   * <p>The fixture reaches {@code b} from both {@code a} and {@code e}, and likewise {@code c}, so
   * both arms return four rows, {@code [b, b, c, c]}. With the order-key presence conjunct gone
   * the sorted alias carries no filter and the planner roots an index-ordered scan on the unique
   * {@code id} index. That scan reaches every target once, so it has to recover the row
   * multiplicity from the reverse edges of each target rather than emit one row per target.
   *
   * <p>This assertion was formerly a defect pin recorded as BG1600, which the index-ordered step
   * fix inverted. The collapse it pinned needed a fan-in hop, an INDEXED order key, and the order
   * alias being the fan-in target, so this shape is the narrowest reproduction of it.
   */
  @Test
  public void orderByUniqueIdOnFanInHopTarget_underDefault_keepsDuplicateRows() {
    seedPeopleWithTiedCreationDateAndUniqueId();

    withOrderIncludesMissingKey(true, () -> {
      var original = translatorEnabled();
      try {
        setTranslatorEnabled(false);
        var nativeAdmin = graph.traversal().V().hasLabel("Person").as("src").out("knows").as("dst")
            .hasLabel("Person").order().by("id").values("id").asAdmin();
        nativeAdmin.applyStrategies();
        assertThat(nativeAdmin.toList().stream().map(String::valueOf).toList())
            .as("native Gremlin keeps one row per (src, dst) pair")
            .containsExactly("b", "b", "c", "c");

        setTranslatorEnabled(true);
        var translatedAdmin = graph.traversal().V().hasLabel("Person").as("src").out("knows")
            .as("dst").hasLabel("Person").order().by("id").values("id").asAdmin();
        translatedAdmin.applyStrategies();
        assertThat(countBoundarySteps(translatedAdmin))
            .as("the shape does translate, so the loss below is a plan defect, not a decline")
            .isEqualTo(1);
        assertThat(translatedAdmin.toList().stream().map(String::valueOf).toList())
            .as("the index-ordered root keeps one row per (src, dst) pair, like native Gremlin")
            .containsExactly("b", "b", "c", "c");
      } finally {
        setTranslatorEnabled(original);
      }
    });
  }

  /**
   * CONTRACT. The LIMIT spelling of the fan-in shape above, which is the discriminating case: a
   * cut placed over the formerly collapsed rows returned DIFFERENT records rather than merely
   * fewer of them, so a row-count assertion alone would not have caught the defect.
   *
   * <p>Both arms sort {@code [b, b, c, c]} and keep the first two, so both return {@code [b, b]}.
   * Before the index-ordered step fix the translated plan sorted {@code [b, c]} and kept both.
   */
  @Test
  public void orderByUniqueIdOnFanInHopTargetThenLimit_underDefault_matchesNativeRows() {
    seedPeopleWithTiedCreationDateAndUniqueId();

    withOrderIncludesMissingKey(true, () -> {
      var original = translatorEnabled();
      try {
        setTranslatorEnabled(false);
        var nativeAdmin = graph.traversal().V().hasLabel("Person").as("src").out("knows").as("dst")
            .hasLabel("Person").order().by("id").limit(2).values("id").asAdmin();
        nativeAdmin.applyStrategies();
        assertThat(nativeAdmin.toList().stream().map(String::valueOf).toList())
            .as("native Gremlin cuts the first two of four sorted rows")
            .containsExactly("b", "b");

        setTranslatorEnabled(true);
        var translatedAdmin = graph.traversal().V().hasLabel("Person").as("src").out("knows")
            .as("dst").hasLabel("Person").order().by("id").limit(2).values("id").asAdmin();
        translatedAdmin.applyStrategies();
        assertThat(countBoundarySteps(translatedAdmin))
            .as("the shape translates, so the difference below is a plan defect, not a decline")
            .isEqualTo(1);
        assertThat(translatedAdmin.toList().stream().map(String::valueOf).toList())
            .as("the cut keeps the first two of four sorted rows, exactly as native Gremlin does")
            .containsExactly("b", "b");
      } finally {
        setTranslatorEnabled(original);
      }
    });
  }

  /**
   * REGRESSION GUARD for the shipped promise on the ordered-index path. An INDEX ORDERED MATCH
   * scan over an index that ignores null values still returns the record that lacks the ordered
   * key, sorted as a null key.
   *
   * <p>A review predicted the opposite: an index ignores null values by default, so a key-less
   * record holds no index entry and an index-rooted scan could never emit it. Execution refutes
   * that prediction. The assertion below runs on the plan the prediction named, which the shape
   * comment records, and the key-less target is the FIRST row of the translated result.
   *
   * <p>The duplicate-row loss formerly pinned above was present on the very same plan, which is
   * why both live here. Any ordered-scan bounding work must not break either assertion.
   */
  @Test
  public void orderByIndexedKeyOnHopTarget_underDefault_keepsTheRecordLackingTheKey() {
    seedFanInWhereOneTargetLacksTheIndexedKey();

    withOrderIncludesMissingKey(true, () -> {
      var original = translatorEnabled();
      try {
        setTranslatorEnabled(true);
        var translatedAdmin = graph.traversal().V().hasLabel("Person").as("src").out("knows")
            .as("dst").hasLabel("Person").order().by("id").values("name").asAdmin();
        translatedAdmin.applyStrategies();
        assertThat(countBoundarySteps(translatedAdmin))
            .as("the shape translates through the INDEX ORDERED MATCH plan on Person.id")
            .isEqualTo(1);
        var rows = translatedAdmin.toList().stream().map(String::valueOf).toList();
        assertThat(rows)
            .as("the index-ordered scan still emits the key-less target, sorted as a null key")
            .startsWith("Nemo")
            .contains("Bea", "Cid");
      } finally {
        setTranslatorEnabled(original);
      }
    });
  }

  /**
   * The fan-in fixture plus one target that carries no {@code id}. Two sources reach three
   * targets, so the duplicate collapse and the key-less question can be asked of one plan. The
   * unique index holds a single key-less record, which is legal. A second one would raise a
   * duplicate-key error unrelated to the question under test.
   */
  private void seedFanInWhereOneTargetLacksTheIndexedKey() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING).createIndex(INDEX_TYPE.UNIQUE);
    var ann = graph.addVertex(T.label, "Person", "id", "a", "name", "Ann");
    var eve = graph.addVertex(T.label, "Person", "id", "e", "name", "Eve");
    var bea = graph.addVertex(T.label, "Person", "id", "b", "name", "Bea");
    var cid = graph.addVertex(T.label, "Person", "id", "c", "name", "Cid");
    var nemo = graph.addVertex(T.label, "Person", "name", "Nemo");
    ann.addEdge("knows", bea);
    ann.addEdge("knows", cid);
    ann.addEdge("knows", nemo);
    eve.addEdge("knows", bea);
    eve.addEdge("knows", cid);
    eve.addEdge("knows", nemo);
    graph.tx().commit();
  }

  /**
   * Bare {@code g.V().order().by(id).limit(2)} translates without a labelled class. Sequence
   * equality holds because {@code id} values are unique on this fixture.
   */
  @Test
  public void orderByIdOnBareVThenLimit_translates() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().order().by(id).limit(2).values(id)",
        () -> graph.traversal().V().order().by("id").limit(2).values("id"));
  }

  /**
   * Post-cardinality bare {@code select} is projection-only and may follow {@code order}+{@code
   * limit}. Both arms return the same ordered id sequence on this UNIQUE-{@code id} fixture.
   */
  @Test
  public void orderLimitThenBareSelect_translatesAndMatchesNative() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().hasLabel(Person).as(p).order().by(id).limit(2).select(p)",
        () -> graph.traversal().V().hasLabel("Person").as("p")
            .order().by("id").limit(2).select("p"));
  }

  /**
   * {@code select().by(key)} after {@code order}+{@code limit} translates: presence drops after the
   * cut via post-plan shaping, not pattern {@code IS DEFINED}. Unique {@code id} keeps on/off
   * sequences aligned. Single-label {@code select} is {@code SelectOneStep}.
   */
  @Test
  public void orderLimitThenSelectBy_translatesAndMatchesNative() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeOrderedValues(
        "g.V().hasLabel(Person).as(p).order().by(id).limit(2).select(p).by(id)",
        () -> graph.traversal().V().hasLabel("Person").as("p")
            .order().by("id").limit(2).select("p").by("id"));
  }

  /**
   * Multi-alias {@code select(a,b).by…} after {@code order}+{@code limit} — presence on different
   * aliases, whole-row drop semantics. Both kept rows share dst id {@code b}, so on/off may disagree
   * on src tie order; compare as a multiset.
   */
  @Test
  public void orderLimitThenMultiSelectBy_translatesAndMatchesNative() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeValues(
        "g.V().hasLabel(Person).as(src).out(knows).as(dst).order().by(id).limit(2)"
            + ".select(src,dst).by(id).by(id)",
        () -> graph.traversal().V().hasLabel("Person").as("src").out("knows").as("dst")
            .order().by("id").limit(2).select("src", "dst").by("id").by("id"));
  }

  /**
   * Pre-cardinality {@code select().by} then {@code limit} still translates (pattern {@code IS
   * DEFINED} before the cut — same order as Gremlin). Limit above the fixture size so both arms
   * keep every id.
   */
  @Test
  public void selectByThenLimit_translatesAndMatchesNative() {
    seedPeopleWithTiedCreationDateAndUniqueId();
    assertTranslatesAndMatchesNativeValues(
        "g.V().hasLabel(Person).as(p).select(p).by(id).limit(10)",
        () -> graph.traversal().V().hasLabel("Person").as("p").select("p").by("id").limit(10));
  }

  /**
   * A slice behind {@code select().by(key)} promotes the post-plan presence into a pattern
   * {@code IS DEFINED} conjunct, so the cut counts survivors. Only the last-scanned vertex carries
   * {@code age}: native drops the four ageless rows at the {@code by} and then keeps the one left,
   * so {@code limit(1)} returns {@code 44} and {@code skip(1)} returns nothing.
   *
   * <p>This is the arm of the promotion that had no coverage. Its neighbour
   * {@link #selectByThenLimit_translatesAndMatchesNative} uses {@code limit(10)} over a key every
   * vertex carries, so the promotion is unobservable there: nothing is dropped and no cut bites.
   * Here a failure to promote leaves the drop on the plan's output, which cuts first and then
   * drops — returning nothing for {@code limit(1)} and {@code 44} for {@code skip(1)}, both the
   * exact inverse of native.
   */
  @Test
  public void selectByThenSliceOnASparseKey_promotesTheDropBeforeTheCut() {
    seedAgeOnLastScannedVertex();

    assertTranslatesAndMatchesNativeValues(
        "g.V().as(p).select(p).by(age).limit(1)",
        () -> graph.traversal().V().as("p").select("p").by("age").limit(1));
    assertTranslatesAndMatchesNativeValuesAllowEmpty(
        "g.V().as(p).select(p).by(age).skip(1)",
        () -> graph.traversal().V().as("p").select("p").by("age").skip(1));

    support.withTranslator(
        false,
        () -> {
          assertThat(graph.traversal().V().as("p").select("p").by("age").limit(1).toList())
              .as("native drops the four ageless rows first, so limit(1) keeps the one age")
              .containsExactly(44);
          assertThat(graph.traversal().V().as("p").select("p").by("age").skip(1).toList())
              .as("native has nothing left to skip past")
              .isEmpty();
        });
  }

  /**
   * {@code limit(1)} then {@code select().by(age)} with {@code age} only on the last-scanned
   * vertex: both arms empty (slice first, then drop). Pattern {@code IS DEFINED} before the cut
   * would keep the aged row and return {@code 44} — the empty match pins post-plan presence.
   */
  @Test
  public void limitThenSelectBySparseProperty_dropAfterCutMatchesNative() {
    seedAgeOnLastScannedVertex();
    assertTranslatesAndMatchesNativeValuesAllowEmpty(
        "g.V().limit(1).as(p).select(p).by(age)",
        () -> graph.traversal().V().limit(1).as("p").select("p").by("age"));
  }

  // ---------------------------------------------------------------------------
  // Bare element-returning LIMIT / SKIP / RANGE — the row-equality gap.
  //
  // G2 semantics. An unordered slice is non-deterministic by the Gremlin spec:
  // limit(n) keeps "the first n" in whatever order the source enumerates, and
  // nothing pins that order. Measured on this engine both arms enumerate in the
  // same storage (RID/cluster) order and so the translated SQL LIMIT and native
  // Gremlin limit keep the identical prefix — but that agreement rests on a shared
  // implementation detail, not on a contract, so asserting exact multiset equality
  // over a real slice would pin engine internals and could turn flaky against a
  // future ordering change in either arm. So the row-equality claim is made only
  // where the slice is an identity (bound >= total), and a real slice is pinned by
  // the two invariants that DO hold by contract: both arms return exactly n rows,
  // and the translated arm's rows are all members of the full unsliced set.
  // ---------------------------------------------------------------------------

  /**
   * A bare {@code limit} whose bound is at or above the scan total is an identity slice, so it keeps
   * every row on both arms and the multiset equality holds without resting on the two pipelines
   * agreeing about order. This is the strict-equality half of the bare-slice coverage: five seeded
   * vertices, {@code limit(10)}, so the slice removes nothing and the translated SQL {@code LIMIT}
   * and native {@code limit} must return the same five elements.
   */
  @Test
  public void bareLimitAtOrAboveTotal_isIdentityAndMatchesNative() {
    seedSingleHub();
    assertTranslatesAndMatchesNative(
        "g.V().hasLabel(Person).limit(10)",
        () -> graph.traversal().V().hasLabel("Person").limit(10));
  }

  /** The skip half of the identity slice: {@code skip(0)} drops nothing and keeps every row. */
  @Test
  public void bareSkipZero_isIdentityAndMatchesNative() {
    seedSingleHub();
    assertTranslatesAndMatchesNative(
        "g.V().hasLabel(Person).skip(0)",
        () -> graph.traversal().V().hasLabel("Person").skip(0));
  }

  /**
   * A real bare {@code limit(3)} over five vertices translates and both arms return exactly three
   * rows, all of them members of the full unsliced set. Exact multiset equality is deliberately
   * <em>not</em> asserted: an unordered {@code limit} is non-deterministic by the Gremlin spec, so
   * even though this engine's two arms happen to keep the same storage-order prefix, pinning that
   * would pin an implementation detail rather than a contract. The size-and-subset invariants are
   * the strongest ones that hold by contract, and they still catch the failures that matter — a
   * translated {@code LIMIT} that returned the wrong count, or rows outside the scanned set.
   */
  @Test
  public void bareLimitBelowTotal_matchesSizeAndSubset() {
    seedSingleHub();
    assertBareSliceSizeAndSubset(
        "g.V().hasLabel(Person).limit(3)",
        () -> graph.traversal().V().hasLabel("Person").limit(3),
        () -> graph.traversal().V().hasLabel("Person"),
        3);
  }

  /**
   * The skip half of the same non-deterministic slice: {@code skip(2)} over five vertices leaves
   * three on both arms, all members of the full set. Same reasoning as
   * {@link #bareLimitBelowTotal_matchesSizeAndSubset} — which three are kept is not a contract, so
   * only the surviving count and set membership are asserted.
   */
  @Test
  public void bareSkipBelowTotal_matchesSizeAndSubset() {
    seedSingleHub();
    assertBareSliceSizeAndSubset(
        "g.V().hasLabel(Person).skip(2)",
        () -> graph.traversal().V().hasLabel("Person").skip(2),
        () -> graph.traversal().V().hasLabel("Person"),
        3);
  }

  /**
   * The range spelling: {@code range(1, 4)} keeps three of the five vertices on both arms, all
   * members of the full set. Rounds out the bare-slice family across the three DSL entry points that
   * all normalise to SQL {@code SKIP}/{@code LIMIT}.
   */
  @Test
  public void bareRangeBelowTotal_matchesSizeAndSubset() {
    seedSingleHub();
    assertBareSliceSizeAndSubset(
        "g.V().hasLabel(Person).range(1, 4)",
        () -> graph.traversal().V().hasLabel("Person").range(1, 4),
        () -> graph.traversal().V().hasLabel("Person"),
        3);
  }

  /**
   * Five vertices, then {@code age} put on whichever one scans <em>last</em>, read back at seed time
   * rather than assumed. Scan order is not stable across JVM forks, so the fixture discovers it
   * instead of predicting it. Native drops the four property-less rows before slicing and so
   * always sees exactly one value; a translation that sliced first would land on a property-less
   * row. The promotion this suite now pins keeps the two arms in agreement.
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
   * Five Person vertices with a UNIQUE {@code id}, a tying {@code creationDate}, and a NOTUNIQUE
   * {@code firstName}. Inserted so the unique-id sequence is not the insertion sequence. {@code a}
   * and {@code e} know {@code b} and {@code c}, which gives the hop and {@code select} cases rows
   * without making the source key decide membership after the hop.
   */
  private void seedPeopleWithTiedCreationDateAndUniqueId() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING).createIndex(INDEX_TYPE.UNIQUE);
    person.createProperty("creationDate", PropertyType.LONG);
    person.createProperty("firstName", PropertyType.STRING).createIndex(INDEX_TYPE.NOTUNIQUE);
    var a = graph.addVertex(T.label, "Person", "id", "a", "creationDate", 100L, "firstName", "Ann");
    var e = graph.addVertex(T.label, "Person", "id", "e", "creationDate", 200L, "firstName", "Ann");
    var c = graph.addVertex(T.label, "Person", "id", "c", "creationDate", 100L, "firstName", "Ann");
    var b = graph.addVertex(T.label, "Person", "id", "b", "creationDate", 100L, "firstName", "Ann");
    graph.addVertex(T.label, "Person", "id", "d", "creationDate", 50L, "firstName", "Bob");
    a.addEdge("knows", b);
    a.addEdge("knows", c);
    e.addEdge("knows", b);
    e.addEdge("knows", c);
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
  private void seedTwoParentsWithSharedHub() {
    var first = graph.addVertex(T.label, "BulkParent", "name", "ParentA");
    var second = graph.addVertex(T.label, "BulkParent", "name", "ParentB");
    var hub = graph.addVertex(T.label, "BulkHub", "name", "Hub");
    var a = graph.addVertex(T.label, "BulkChild", "name", "A");
    var b = graph.addVertex(T.label, "BulkChild", "name", "B");
    first.addEdge("toHub", hub);
    second.addEdge("toHub", hub);
    hub.addEdge("toChild", a);
    hub.addEdge("toChild", b);
    graph.tx().commit();
  }

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
   * One hub with three {@code knows} targets, seeded reverse-alphabetically so the sorted sequence
   * is not the one an unsorted read hands back. The hub itself is outside the hop's output, so
   * {@code out("knows")} yields exactly the three targets and a slice of two or of {@code [1, 3)}
   * lands strictly inside them.
   */
  private void seedHubWithReverseSortedTargets() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    var cal = graph.addVertex(T.label, "Person", "name", "Cal");
    var ben = graph.addVertex(T.label, "Person", "name", "Ben");
    var ann = graph.addVertex(T.label, "Person", "name", "Ann");
    hub.addEdge("knows", cal);
    hub.addEdge("knows", ben);
    hub.addEdge("knows", ann);
    graph.tx().commit();
  }

  /**
   * The suite-local name for "this shape declines and both arms return the same non-empty native
   * multiset", delegating to the shared driver so the engagement pin and the two anti-vacuity pins
   * come from one place rather than another hand-rolled two-arm body.
   */
  private void assertDeclinesOverTheSameNativeRows(
      String scenario, Supplier<GraphTraversal<?, ?>> shape) {
    support.assertEquivalent(
        scenario,
        Recognition.DECLINED,
        Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedStrings,
        shape);
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
   * The {@code ORDER BY} sibling of {@link #assertClauseThenStepDeclines} for shapes that still
   * decline after a captured {@code order()} (for example two hops before the slice). {@code shape}
   * must return native's rows <em>in native's order</em>. The comparison is ordered rather than a
   * multiset one because {@code order()} makes the sequence part of the answer.
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
      String explainDump;
      try {
        var exAdmin = shape.get().asAdmin();
        exAdmin.applyStrategies();
        explainDump = exAdmin.explain().toString();
      } catch (Exception e) {
        explainDump = "explain-failed: " + e;
      }
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
          .as(scenario + ": this ordered-slice shape must decline the whole walk")
          .isEqualTo(0);
      assertThat(onRows)
          .as(scenario + ": translator-on and translator-off rows must match in native's order")
          .isEqualTo(offRows);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Asserts that {@code shape} declines (zero boundary steps), still has a non-empty native step
   * list after strategy application, and returns a non-empty native sequence. Used for ordered-slice
   * shapes that remain out of scope (for example a second hop between {@code order()} and the
   * slice). The non-empty step list keeps a decline from being satisfied by a walk that produced no
   * plan and no steps.
   */
  private void assertOrderedSliceDeclinesWithRemainingSteps(
      String scenario, Supplier<GraphTraversal<?, ?>> shape) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = shape.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var stepsOn = onAdmin.getSteps();
      var onRows = onAdmin.toList().stream().map(String::valueOf).toList();

      setTranslatorEnabled(false);
      var offAdmin = shape.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offRows = offAdmin.toList().stream().map(String::valueOf).toList();

      assertThat(stepsOn)
          .as(scenario + ": a declined walk must still carry a non-empty step list")
          .isNotEmpty();
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onRows)
          .as(scenario + " must return rows, or the comparison is vacuous")
          .isNotEmpty();
      assertThat(boundaryOn)
          .as(scenario + ": ordered-slice shape must decline the whole walk")
          .isEqualTo(0);
      assertThat(onRows)
          .as(scenario + ": translator-on and translator-off rows must match in native's order")
          .isEqualTo(offRows);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * The ordered sibling of {@link #assertTranslatesAndMatchesNativeValues}: sequence equality,
   * because {@code order()} makes the sequence the answer. A discriminating-key top-N that agreed
   * as a multiset but disagreed on positions would fail this pin.
   */
  /** Compare the user-visible exception and require the translator boundary in the on arm. */
  private void assertSelectHopThrowsNativeCast(
      String scenario, Supplier<GraphTraversal<?, ?>> shape) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(false);
      var nativeTraversal = shape.get().asAdmin();
      nativeTraversal.applyStrategies();
      assertThat(countBoundarySteps(nativeTraversal.getSteps())).isZero();
      assertThatThrownBy(nativeTraversal::toList)
          .as(scenario + " (native)").isInstanceOf(ClassCastException.class);

      setTranslatorEnabled(true);
      var translated = shape.get().asAdmin();
      translated.applyStrategies();
      assertThat(countBoundarySteps(translated.getSteps())).as(scenario).isEqualTo(1);
      assertThatThrownBy(translated::toList)
          .as(scenario + " (translated)").isInstanceOf(ClassCastException.class);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** Require exact empty results, not a decline or an exception, on both execution paths. */
  private void assertSelectHopEmpty(String scenario, Supplier<GraphTraversal<?, ?>> shape) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(false);
      var nativeTraversal = shape.get().asAdmin();
      nativeTraversal.applyStrategies();
      assertThat(countBoundarySteps(nativeTraversal.getSteps())).isZero();
      assertThat(nativeTraversal.toList()).as(scenario + " (native)").isEmpty();

      setTranslatorEnabled(true);
      var translated = shape.get().asAdmin();
      translated.applyStrategies();
      assertThat(countBoundarySteps(translated.getSteps())).as(scenario).isEqualTo(1);
      assertThat(translated.toList()).as(scenario + " (translated)").isEmpty();
    } finally {
      setTranslatorEnabled(original);
    }
  }

  private void assertTranslatesAndMatchesNativeOrderedValues(
      String scenario, Supplier<GraphTraversal<?, ?>> shape) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = shape.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onRows = onAdmin.toList().stream().map(String::valueOf).toList();

      setTranslatorEnabled(false);
      var offAdmin = shape.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offRows = offAdmin.toList().stream().map(String::valueOf).toList();

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
          .as(scenario + ": translator-on and translator-off sequences must match")
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
    assertTranslatesAndMatchesNativeValues(scenario, shape, true);
  }

  /**
   * Like {@link #assertTranslatesAndMatchesNativeValues} but allows an empty multiset — used when
   * the correct Gremlin result is empty and a wrong pre-cut {@code IS DEFINED} would not be.
   */
  private void assertTranslatesAndMatchesNativeValuesAllowEmpty(
      String scenario, Supplier<GraphTraversal<?, ?>> shape) {
    assertTranslatesAndMatchesNativeValues(scenario, shape, false);
  }

  private void assertTranslatesAndMatchesNativeValues(
      String scenario, Supplier<GraphTraversal<?, ?>> shape, boolean requireNonEmpty) {
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
      if (requireNonEmpty) {
        assertThat(onRows)
            .as(scenario + " must return rows, or the comparison is vacuous")
            .isNotEmpty();
      }
      assertThat(onRows)
          .as(scenario + ": translator-on and translator-off multisets must match")
          .isEqualTo(offRows);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Asserts that a bare, unordered slice translates and that the two contract-level invariants hold:
   * both arms return exactly {@code expectedSize} rows, and every element the translated arm returns
   * is a member of the full unsliced set. Exact multiset equality is intentionally not asserted —
   * see the section comment above {@link #bareLimitAtOrAboveTotal_isIdentityAndMatchesNative} for
   * why an unordered slice's kept subset is not a contract. The off-arm boundary count is pinned at
   * zero so a kill-switch flip that never reached the traversal cannot leave both arms translated.
   */
  private void assertBareSliceSizeAndSubset(
      String scenario,
      Supplier<GraphTraversal<?, ?>> slice,
      Supplier<GraphTraversal<?, ?>> fullUnsliced,
      int expectedSize) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = slice.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onIds = sortedIds(onAdmin.toList());

      setTranslatorEnabled(false);
      var offAdmin = slice.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offIds = sortedIds(offAdmin.toList());
      var fullIds = sortedIds(drain(fullUnsliced));

      assertThat(boundaryOn)
          .as(scenario + " must translate — exactly one boundary step")
          .isEqualTo(1);
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      // The fixture precondition that makes the slice a real one: the full set is strictly larger
      // than the slice, so a translated LIMIT that silently kept everything would fail the size pin.
      assertThat(fullIds)
          .as(scenario
              + ": the full unsliced set must exceed the slice, or the size pin is vacuous")
          .hasSizeGreaterThan(expectedSize);
      assertThat(onIds)
          .as(scenario + " (translator on) must return exactly " + expectedSize + " rows")
          .hasSize(expectedSize);
      assertThat(offIds)
          .as(scenario + " (translator off) must return exactly " + expectedSize + " rows")
          .hasSize(expectedSize);
      assertThat(fullIds)
          .as(scenario + ": every translated row must be a member of the full unsliced set")
          .containsAll(onIds);
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

  /** Runs {@code body} with the productive-order setting forced, restoring the previous value. */
  private void withOrderIncludesMissingKey(boolean value, Runnable body) {
    var config = session.getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, value);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, previous);
    }
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
    return seededContext(BoundaryOutputType.ELEMENT);
  }

  /**
   * The same seeded context with the boundary pinned to {@code outputType}, so a test can name the
   * payload shape the boundary hands back rather than the element default.
   */
  private static WalkerContext seededContext(BoundaryOutputType outputType) {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, outputType, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  /** The modulator on the last comparator slot of the traversal's first {@code order()} step. */
  private static Object trailingOrderModulator(Traversal.Admin<?, ?> admin) {
    var orderStep = (OrderGlobalStep<?, ?>) stepOf(admin, OrderGlobalStep.class);
    return orderStep.getComparators().getLast().getValue0();
  }

  /** GROUP BY captured and entry emit enabled — as after {@code groupCount().unfold()}. */
  private static WalkerContext seededGroupEntryContext() {
    var ctx = seededContext();
    ctx.setGroupBy(new SQLGroupBy(-1));
    ctx.enableGroupEntryEmit();
    return ctx;
  }

  private static Outcome recognizeOrder(Traversal.Admin<?, ?> admin, WalkerContext ctx) {
    applyOrderTieBreak(admin);
    return OrderGlobalStepRecogniser.INSTANCE.recognize(
        cursorAt(admin, OrderGlobalStep.class), ctx);
  }

  /** Mirrors production: tie-break runs before {@link GremlinToMatchStrategy}. */
  private static void applyOrderTieBreak(Traversal.Admin<?, ?> admin) {
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
  }

  /**
   * A seeded context whose boundary is re-typed to {@code Person} (and carries the live schema
   * snapshot like a production walk). Used after {@link #seedPeopleWithTiedCreationDateAndUniqueId}.
   */
  private WalkerContext seededPersonContext() {
    var ctx = new WalkerContext(true, false, session.getSchema());
    ctx.addNode(BOUNDARY_ALIAS, "Person");
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
