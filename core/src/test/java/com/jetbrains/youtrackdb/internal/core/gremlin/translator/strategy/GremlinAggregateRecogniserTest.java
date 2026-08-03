package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupCountStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MeanGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for aggregate recognisers: {@code count}/{@code mean}/{@code group}/{@code
 * groupCount} RETURN wiring, output types, and decline paths.
 */
public class GremlinAggregateRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /** {@code count()} pins {@code SCALAR} and projects {@code count(*)}. */
  @Test
  public void count_pinsScalarCountStar() {
    var admin = graph.traversal().V().count().asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, CountGlobalStep.class);

    var outcome = CountGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.SCALAR);
    assertThat(ctx.returnItems.getFirst().toString()).containsIgnoringCase("count(*)");
    assertThat(ctx.shaping().dropNullRows()).isFalse();
  }

  /**
   * Non-polymorphic {@code count()} still translates; MATCH short-circuit folds exact {@code
   * @class} filters into leaf-exact {@code CountFromClassStep}.
   */
  @Test
  public void count_nonPolymorphic_accepts() {
    var admin = graph.traversal().V().count().asAdmin();
    var ctx = new WalkerContext(false, false);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    var cursor = cursorAt(admin, CountGlobalStep.class);

    assertThat(CountGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.SCALAR);
  }

  /**
   * {@code values("age").mean()} re-points at {@code lastPropertyProjection}, sets dropNullRows and
   * emits the SQL {@code mean} aggregate — not {@code avg}, which divides integer input in integer
   * arithmetic and would answer 30 where Gremlin's mean() answers 30.75.
   */
  @Test
  public void mean_afterValues_repointsAndDropsNullRows() {
    var admin = graph.traversal().V().values("age").mean().asAdmin();
    var ctx = seededContext();
    var valuesCursor = cursorAt(admin, PropertiesStep.class);
    assertThat(PropertiesStepRecogniser.INSTANCE.recognize(valuesCursor, ctx))
        .isEqualTo(Outcome.ACCEPTED);
    var meanCursor = cursorAt(admin, MeanGlobalStep.class);

    var outcome = PropertyAggregateStepRecogniser.INSTANCE.recognize(meanCursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.SCALAR);
    assertThat(ctx.shaping().dropNullRows()).isTrue();
    assertThat(ctx.returnItems.getFirst().toString()).containsIgnoringCase("mean");
    assertThat(ctx.returnItems.getFirst().toString()).doesNotContainIgnoringCase("avg");
    assertThat(ctx.returnItems.getFirst().toString()).contains("age");
    // The values("age") drop lived in the row projection this recogniser just replaced, so it has
    // to reappear as a pattern conjunct on the boundary alias. Without it the aggregate reduces the
    // null-valued rows too and sum()'s zero survives where Gremlin emits no traverser.
    assertThat(ctx.aliasFilters).containsKey(BOUNDARY_ALIAS);
    assertThat(ctx.aliasFilters.get(BOUNDARY_ALIAS).toString())
        .containsIgnoringCase("age is defined");
  }

  /** {@code mean()} without a preceding {@code values(key)} declines. */
  @Test
  public void mean_withoutValuesPrefix_declines() {
    var admin = graph.traversal().V().mean().asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, MeanGlobalStep.class);

    assertThat(PropertyAggregateStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /** {@code group().by("name").by(__.count())} sets GROUP BY + MAP columns. */
  @Test
  public void group_byKeyAndCount_setsGroupByAndMap() {
    var admin = graph.traversal().V().group().by("name").by(__.count()).asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, GroupStep.class);

    var outcome = GroupStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.groupBy).isNotNull();
    assertThat(ctx.returnAliases).extracting(a -> a.getStringValue())
        .containsExactly(
            GremlinAggregateAssembler.GROUP_KEY_ALIAS,
            GremlinAggregateAssembler.GROUP_VALUE_ALIAS);
    assertThat(ctx.returnItems.get(1).toString()).containsIgnoringCase("count(*)");
    assertThat(ctx.shaping().accumulateMap()).isTrue();
  }

  /** {@code groupCount().by("name")} is GROUP BY + count(*). */
  @Test
  public void groupCount_byKey_setsCountValue() {
    var admin = graph.traversal().V().groupCount().by("name").asAdmin();
    var ctx = seededContext();
    var cursor = cursorAt(admin, GroupCountStep.class);

    var outcome = GroupCountStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.groupBy).isNotNull();
    assertThat(ctx.returnItems.get(1).toString()).containsIgnoringCase("count(*)");
    assertThat(ctx.shaping().accumulateMap()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // B1 — a reducing / grouping terminator declines after a captured cardinality
  // clause (limit / skip / dedup). MATCH applies SKIP / LIMIT / RETURN DISTINCT
  // *after* the aggregate projection while Gremlin applies them to the reducer's
  // input, so the two diverge; declining hands the whole traversal to the native
  // pipeline, which orders the operations correctly. Each case seeds the context
  // so the terminator would otherwise ACCEPT, then sets exactly one clause and
  // asserts the gate flips the outcome to DECLINE.
  // ---------------------------------------------------------------------------

  /**
   * {@code count()} declines when a {@code LIMIT} was captured earlier ({@code limit(5).count()}):
   * MATCH would ignore the limit and count the whole class, so the walk must go native.
   */
  @Test
  public void count_afterCapturedLimit_declines() {
    var admin = graph.traversal().V().count().asAdmin();
    var ctx = seededContext();
    ctx.setLimit(ProjectionExpressionFactories.limit(5));
    var cursor = cursorAt(admin, CountGlobalStep.class);

    assertThat(CountGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /**
   * {@code count()} declines when a {@code SKIP} was captured earlier ({@code skip(2).count()}):
   * SKIP after the aggregate would drop the single count row, so the walk must go native.
   */
  @Test
  public void count_afterCapturedSkip_declines() {
    var admin = graph.traversal().V().count().asAdmin();
    var ctx = seededContext();
    ctx.setSkip(ProjectionExpressionFactories.skip(2));
    var cursor = cursorAt(admin, CountGlobalStep.class);

    assertThat(CountGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /**
   * {@code count()} declines when {@code RETURN DISTINCT} was captured earlier (the {@code
   * out().dedup().count()} shape): {@code RETURN DISTINCT count(*)} would count duplicates, so the
   * walk must go native where dedup runs before the count.
   */
  @Test
  public void count_afterReturnDistinct_declines() {
    var admin = graph.traversal().V().count().asAdmin();
    var ctx = seededContext();
    ctx.setReturnDistinct(true);
    var cursor = cursorAt(admin, CountGlobalStep.class);

    assertThat(CountGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /**
   * A property aggregate ({@code mean}) declines when a {@code LIMIT} was captured earlier ({@code
   * limit(2).values(age).mean()}). The preceding {@code values(age)} sets {@code
   * lastPropertyProjection} so the aggregate would otherwise ACCEPT — proving it is the captured
   * limit, not a missing field, that forces the decline.
   */
  @Test
  public void propertyAggregateMean_afterCapturedLimit_declines() {
    var admin = graph.traversal().V().values("age").mean().asAdmin();
    var ctx = seededContext();
    assertThat(PropertiesStepRecogniser.INSTANCE.recognize(cursorAt(admin, PropertiesStep.class),
        ctx))
        .isEqualTo(Outcome.ACCEPTED);
    ctx.setLimit(ProjectionExpressionFactories.limit(2));

    assertThat(PropertyAggregateStepRecogniser.INSTANCE.recognize(
        cursorAt(admin, MeanGlobalStep.class), ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /**
   * {@code group().by("name").by(__.count())} declines when a {@code LIMIT} was captured earlier:
   * the reducer runs before the limit in Gremlin but after it in MATCH, so the walk must go native.
   */
  @Test
  public void group_afterCapturedLimit_declines() {
    var admin = graph.traversal().V().group().by("name").by(__.count()).asAdmin();
    var ctx = seededContext();
    ctx.setLimit(ProjectionExpressionFactories.limit(5));
    var cursor = cursorAt(admin, GroupStep.class);

    assertThat(GroupStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
  }

  /**
   * {@code groupCount().by("name")} declines when a {@code LIMIT} was captured earlier, for the same
   * reducer-before-vs-after-limit divergence as {@code group()}.
   */
  @Test
  public void groupCount_afterCapturedLimit_declines() {
    var admin = graph.traversal().V().groupCount().by("name").asAdmin();
    var ctx = seededContext();
    ctx.setLimit(ProjectionExpressionFactories.limit(5));
    var cursor = cursorAt(admin, GroupCountStep.class);

    assertThat(GroupCountStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
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
