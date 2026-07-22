package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ElementMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.junit.Test;

/**
 * Unit tests for projection recognisers ({@link PropertiesStepRecogniser}, {@link
 * SelectStepRecogniser}, {@link PropertyMapStepRecogniser}): RETURN wiring, output-type pinning, and
 * decline paths.
 */
public class GremlinProjectionRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of();

  /** {@code values("name")} pins {@code SINGLE_VALUE}, sets {@code dropOnAbsent}, records field IR. */
  @Test
  public void valuesSingleKey_pinsSingleValueAndDropOnAbsent() {
    var admin = graph.traversal().V().values("name").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertiesStep.class);

    var outcome = PropertiesStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.SINGLE_VALUE);
    assertThat(ctx.dropOnAbsent).isTrue();
    assertThat(ctx.lastPropertyProjection).isNotNull();
    assertThat(ctx.lastPropertyProjection.toString()).contains("name");
    assertThat(ctx.returnItems).hasSize(2);
    assertThat(ctx.presencePropertyKeys).containsExactly("name");
  }

  /** Optimised {@code properties("name")} terminal step is accepted like {@code values}. */
  @Test
  public void propertiesSingleKey_acceptedLikeValues() {
    var admin = graph.traversal().V().properties("name").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertiesStep.class);

    var outcome = PropertiesStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.SINGLE_VALUE);
  }

  /** Multi-key {@code values("a","b")} declines — flatMap has no boundary equivalent yet. */
  @Test
  public void valuesMultiKey_declines() {
    var admin = graph.traversal().V().values("a", "b").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertiesStep.class);

    var outcome = PropertiesStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
  }

  /** {@code select("v")} after {@code as("v")} surfaces the label in RETURN as {@code MAP}. */
  @Test
  public void selectBoundLabel_pinsMapProjection() {
    var admin = graph.traversal().V().as("v").select("v").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, SelectOneStep.class);

    var outcome = SelectOneStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo("v");
    assertThat(ctx.returnItems.getFirst().toString()).contains(BOUNDARY_ALIAS);
  }

  /** {@code select("missing")} declines when the label was never bound. */
  @Test
  public void selectUnboundLabel_declines() {
    var admin = graph.traversal().V().select("missing").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, SelectOneStep.class);

    var outcome = SelectOneStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
  }

  /** {@code valueMap("name")} emits one named RETURN column and pins {@code MAP}. */
  @Test
  public void valueMapSingleKey_pinsMapProjection() {
    var admin = graph.traversal().V().valueMap("name").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertyMapStep.class);

    var outcome = PropertyMapStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.wrapMapValuesInLists).isTrue();
    assertThat(ctx.presencePropertyKeys).containsExactly("name");
    assertThat(ctx.returnAliases.stream().map(a -> a == null ? null : a.getStringValue()))
        .contains(BOUNDARY_ALIAS, "name");
    assertThat(ctx.returnItems.get(1).toString()).contains("name");
  }

  /** Bare {@code valueMap()} declines — all-property enumeration is deferred. */
  @Test
  public void bareValueMap_declines() {
    var admin = graph.traversal().V().valueMap().asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertyMapStep.class);

    var outcome = PropertyMapStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
  }

  /** {@code elementMap("name")} includes id/label token columns plus the property key. */
  @Test
  public void elementMap_includesIdLabelAndPropertyColumns() {
    var admin = graph.traversal().V().elementMap("name").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, ElementMapStep.class);

    var outcome = ElementMapStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.wrapMapValuesInLists).isFalse();
    assertThat(ctx.returnAliases.stream().map(a -> a.getStringValue()))
        .containsExactly(
            BOUNDARY_ALIAS,
            GremlinProjectionAssembler.ELEMENT_MAP_KEY_ID,
            GremlinProjectionAssembler.ELEMENT_MAP_KEY_LABEL,
            "name");
  }

  /** {@code select("v").by("name")} applies the modulator to the bound label's internal alias. */
  @Test
  public void selectWithBy_appliesModulatorToBoundLabel() {
    var admin = graph.traversal().V().as("v").select("v").by("name").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, SelectOneStep.class);

    var outcome = SelectOneStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo("v");
    assertThat(ctx.returnItems.getFirst().toString()).contains("name");
  }

  /** {@code project("n").by("name")} builds one modulated RETURN column per project key. */
  @Test
  public void projectWithBy_pinsMapProjection() {
    var admin = graph.traversal().V().project("n").by("name").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor =
        cursorAt(admin, org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep.class);

    var outcome = ProjectStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo("n");
    assertThat(ctx.returnItems.getFirst().toString()).contains("name");
  }

  private static WalkerContext contextAfterStart(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> admin) {
    var ctx = new WalkerContext(true, false);
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    assertThat(StartStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    return ctx;
  }

  private static StepStreamCursor cursorAt(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> admin,
      Class<?> stepType) {
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
