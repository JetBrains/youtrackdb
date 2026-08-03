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
    assertThat(ctx.shaping().dropOnAbsent()).isTrue();
    assertThat(ctx.lastPropertyProjection).isNotNull();
    // Read the halves separately. The projection is a record whose dump carries "name" in three
    // fields, so a contains() over toString() would still pass if the key half went missing.
    assertThat(ctx.lastPropertyProjection.propertyKey()).isEqualTo("name");
    assertThat(ctx.lastPropertyProjection.alias()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.lastPropertyProjection.expression().toString())
        .isEqualTo(BOUNDARY_ALIAS + ".name");
    assertThat(ctx.returnItems).hasSize(2);
    assertThat(ctx.shaping().presencePropertyKeys()).containsExactly("name");
  }

  /**
   * The element-returning {@code properties("name")} form declines where {@code values("name")} is
   * accepted. {@code properties(key)} emits the {@code VertexProperty} element and {@code values(key)}
   * emits its payload, so projecting the former as a field access would hand a downstream step the
   * value in place of the element — measured as {@code properties(k).has(metaKey, v)} returning nothing
   * translated against one row natively. The pairing with {@code valuesSingleKey_*} above is the
   * positive control: the decline has to be specific to the element form, not a blanket withdrawal of
   * the step class.
   */
  @Test
  public void propertiesElementForm_declines_whereValuesIsAccepted() {
    var admin = graph.traversal().V().properties("name").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertiesStep.class);

    var outcome = PropertiesStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.lastPropertyProjection)
        .as("a declined step must leave no projection behind on the context")
        .isNull();
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
    assertThat(ctx.shaping().wrapMapValuesInLists()).isTrue();
    assertThat(ctx.shaping().presencePropertyKeys()).containsExactly("name");
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
    assertThat(ctx.shaping().wrapMapValuesInLists()).isFalse();
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

  /**
   * Multi-label {@code select("a","b").by("name").by("age")} projects each label through its
   * matching key modulator into a MAP (unwrapSingletonMap stays false).
   */
  @Test
  public void selectMultiLabelWithMatchingBys_pinsMapColumns() {
    var admin =
        graph.traversal().V().as("a").out().as("b").select("a", "b").by("name").by("age").asAdmin();
    var ctx = contextThroughVertexHop(admin);
    var cursor =
        cursorAt(admin, org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep.class);

    var outcome = SelectStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.shaping().unwrapSingletonMap()).isFalse();
    assertThat(ctx.returnAliases.stream().map(a -> a.getStringValue())).containsExactly("a", "b");
    assertThat(ctx.returnItems.get(0).toString()).contains("name");
    assertThat(ctx.returnItems.get(1).toString()).contains("age");
  }

  /** {@code select("a","b").by("name")} declines when modulator count ≠ label count. */
  @Test
  public void selectMultiLabel_modulatorCountMismatch_declines() {
    var admin = graph.traversal().V().as("a").out().as("b").select("a", "b").by("name").asAdmin();
    var ctx = contextThroughVertexHop(admin);
    var cursor =
        cursorAt(admin, org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep.class);

    assertThat(SelectStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
  }

  /** {@code select("a","b").by("name").by(__.out())} declines an unsupported key modulator. */
  @Test
  public void selectMultiLabel_unsupportedBy_declines() {
    var admin =
        graph
            .traversal()
            .V()
            .as("a")
            .out()
            .as("b")
            .select("a", "b")
            .by("name")
            .by(org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.out())
            .asAdmin();
    var ctx = contextThroughVertexHop(admin);
    var cursor =
        cursorAt(admin, org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep.class);

    assertThat(SelectStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
  }

  /** One unbound label under {@code select(...).by(...)} declines the whole select. */
  @Test
  public void selectMultiLabel_unboundLabelUnderBy_declines() {
    var admin =
        graph.traversal().V().as("a").select("a", "missing").by("name").by("age").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor =
        cursorAt(admin, org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep.class);

    assertThat(SelectStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
  }

  /** {@code select(Pop.first, ...)} declines — only Pop.last is Phase-1. */
  @Test
  public void selectMultiLabel_popFirst_declines() {
    var admin =
        graph
            .traversal()
            .V()
            .as("a")
            .out()
            .as("b")
            .select(org.apache.tinkerpop.gremlin.process.traversal.Pop.first, "a", "b")
            .asAdmin();
    var ctx = contextThroughVertexHop(admin);
    var cursor =
        cursorAt(admin, org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep.class);

    assertThat(SelectStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
  }

  /** {@code valueMap("name","age")} emits both property columns under MAP with list-wrap. */
  @Test
  public void valueMapMultiKey_pinsBothPresenceKeys() {
    var admin = graph.traversal().V().valueMap("name", "age").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertyMapStep.class);

    var outcome = PropertyMapStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.shaping().presencePropertyKeys()).containsExactly("name", "age");
  }

  /** {@code select(Pop.first,"v")} declines — SelectOneStep only accepts Pop.last. */
  @Test
  public void selectOne_popFirst_declines() {
    var admin =
        graph
            .traversal()
            .V()
            .as("v")
            .select(org.apache.tinkerpop.gremlin.process.traversal.Pop.first, "v")
            .asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, SelectOneStep.class);

    assertThat(SelectOneStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
  }

  private static WalkerContext contextAfterStart(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> admin) {
    var ctx = new WalkerContext(true, false);
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    assertThat(StartStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    return ctx;
  }

  /** Start + first vertex hop so multi-label {@code as} bindings are registered. */
  private static WalkerContext contextThroughVertexHop(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> admin) {
    var ctx = new WalkerContext(true, false);
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    assertThat(StartStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(VertexStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
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
