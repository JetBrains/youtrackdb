package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
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
    assertThat(ctx.dropNullRows).isFalse();
  }

  /** Non-polymorphic {@code count()} declines so {@code YTDBGraphCountStrategy} covers it. */
  @Test
  public void count_nonPolymorphic_declines() {
    var admin = graph.traversal().V().count().asAdmin();
    var ctx = new WalkerContext(false, false);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    var cursor = cursorAt(admin, CountGlobalStep.class);

    assertThat(CountGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
  }

  /** {@code values("age").mean()} re-points at {@code lastPropertyProjection} and sets dropNullRows. */
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
    assertThat(ctx.dropNullRows).isTrue();
    assertThat(ctx.returnItems.getFirst().toString()).containsIgnoringCase("mean");
    assertThat(ctx.returnItems.getFirst().toString()).contains("age");
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
    assertThat(ctx.accumulateMap).isTrue();
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
    assertThat(ctx.accumulateMap).isTrue();
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
