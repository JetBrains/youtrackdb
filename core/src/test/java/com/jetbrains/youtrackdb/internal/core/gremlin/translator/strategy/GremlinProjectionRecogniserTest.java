package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AliasPropertyPresence;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ValuesFlatMapListShapingOp;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ElementMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/**
 * Unit tests for projection recognisers ({@link PropertiesStepRecogniser}, {@link
 * SelectStepRecogniser}, {@link PropertyMapStepRecogniser}): RETURN wiring, output-type pinning, and
 * decline paths.
 */
public class GremlinProjectionRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of();

  /**
   * {@code values("name")} pins {@code SINGLE_VALUE} and {@code dropOnAbsent}. RETURN is the
   * boundary entity column only — the plan step reads {@code name} via presence; the key stays in
   * {@code presencePropertyKeys}, not as a parallel {@code alias.key} RETURN column. Field IR for a
   * following aggregate still records {@code $g2m_v0.name}.
   */
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
    assertThat(ctx.returnItems).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.returnItems.getFirst().toString()).doesNotContain(".name");
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

  /** Multi-key {@code values("a","b")} pins ELEMENT projection + {@link ValuesFlatMapListShapingOp}. */
  @Test
  public void valuesMultiKey_pinsFlatMapShaping() {
    var admin = graph.traversal().V().values("a", "b").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertiesStep.class);

    var outcome = PropertiesStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.ELEMENT);
    assertThat(ctx.shaping().listShapingOps()).hasSize(1);
    assertThat(ctx.shaping().listShapingOps().getFirst())
        .isInstanceOf(ValuesFlatMapListShapingOp.class);
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

  /**
   * {@code valueMap("name")} pins {@code MAP} with list-wrap. RETURN is the boundary entity only;
   * {@code name} is a presence / emit key, not a parallel RETURN column.
   */
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
    assertThat(ctx.shaping().mapEmitColumnOrder()).containsExactly("name");
    assertThat(ctx.returnAliases.stream().map(a -> a == null ? null : a.getStringValue()))
        .containsExactly(BOUNDARY_ALIAS);
    assertThat(ctx.returnItems).hasSize(1);
    assertThat(ctx.returnItems.getFirst().toString()).doesNotContain(".name");
  }

  /** Bare {@code valueMap()} on the generic {@code V} root declines. */
  @Test
  public void bareValueMap_declines() {
    var admin = graph.traversal().V().valueMap().asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertyMapStep.class);

    var outcome = PropertyMapStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
  }

  /** Keyless {@code valueMap()} on {@code hasLabel(Person)} enumerates schema-declared keys. */
  @Test
  public void valueMap_keyless_onHasLabelPerson_accepts() {
    session.getSchema().createClass("Person", session.getSchema().getClass("V"));
    session.getSchema().getClass("Person").createProperty("name", PropertyType.STRING);
    session.getSchema().getClass("Person").createProperty("age", PropertyType.INTEGER);
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    var admin = graph.traversal().V().hasLabel("Person").valueMap().asAdmin();
    var ctx = new WalkerContext(true, false, session.getSchema());
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    assertThat(StartStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(HasStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);

    var outcome = PropertyMapStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.shaping().presencePropertyKeys()).containsExactly("age", "name");
  }

  /**
   * {@code elementMap("name")} RETURNs the boundary entity plus id/label token columns. The property
   * key is presence / emit only — not a fourth RETURN alias.
   */
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
            GremlinProjectionAssembler.ELEMENT_MAP_KEY_LABEL);
    assertThat(ctx.shaping().presencePropertyKeys()).containsExactly("name");
    assertThat(ctx.shaping().mapEmitColumnOrder())
        .containsExactly(
            GremlinProjectionAssembler.ELEMENT_MAP_KEY_ID,
            GremlinProjectionAssembler.ELEMENT_MAP_KEY_LABEL,
            "name");
  }

  /**
   * {@code select("v").by("name")} RETURNs a {@code $g2m_pe_*} entity column (not user label
   * {@code "v"}). The user label is the map emit key; the entity expression has no {@code .name}
   * field access — the plan step reads the property via {@link AliasPropertyPresence}.
   */
  @Test
  public void selectWithBy_appliesModulatorToBoundLabel() {
    var admin = graph.traversal().V().as("v").select("v").by("name").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, SelectOneStep.class);

    var outcome = SelectOneStepRecogniser.INSTANCE.recognize(cursor, ctx);

    var peAlias = ResultShaping.presenceEntityColumnAlias(BOUNDARY_ALIAS);
    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(peAlias);
    assertThat(ctx.returnItems.getFirst().toString()).doesNotContain(".name");
    assertThat(ctx.shaping().mapEmitColumnOrder()).containsExactly("v");
    assertThat(ctx.shaping().dropOnAbsent()).isTrue();
    assertThat(ctx.shaping().aliasPropertyPresences())
        .containsExactly(new AliasPropertyPresence(peAlias, "name", "v"));
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
   * Multi-label {@code select("a","b").by("name").by("age")} RETURNs a {@code $g2m_pe_*} entity
   * column per label; user labels stay in {@code mapEmitColumnOrder} / alias-presence map keys.
   * unwrapSingletonMap stays false.
   */
  @Test
  public void selectMultiLabelWithMatchingBys_pinsMapColumns() {
    var admin =
        graph.traversal().V().as("a").out().as("b").select("a", "b").by("name").by("age").asAdmin();
    var ctx = contextThroughVertexHop(admin);
    var cursor =
        cursorAt(admin, org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep.class);

    var outcome = SelectStepRecogniser.INSTANCE.recognize(cursor, ctx);

    var peA = ResultShaping.presenceEntityColumnAlias(BOUNDARY_ALIAS);
    var peB = ResultShaping.presenceEntityColumnAlias("$g2m_anon_0");
    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.shaping().unwrapSingletonMap()).isFalse();
    assertThat(ctx.returnAliases.stream().map(a -> a.getStringValue())).containsExactly(peA, peB);
    assertThat(ctx.returnItems.get(0).toString()).doesNotContain(".name");
    assertThat(ctx.returnItems.get(1).toString()).doesNotContain(".age");
    assertThat(ctx.shaping().mapEmitColumnOrder()).containsExactly("a", "b");
    assertThat(ctx.shaping().dropOnAbsent()).isTrue();
    assertThat(ctx.shaping().aliasPropertyPresences())
        .containsExactly(
            new AliasPropertyPresence(peA, "name", "a"),
            new AliasPropertyPresence(peB, "age", "b"));
  }

  /** {@code order().by(name)} ahead of the select keeps the same two-label/two-by shape. */
  @Test
  public void selectMultiLabelAfterOrder_keepsMatchingByCount() {
    var admin =
        graph.traversal().V().as("a").out().as("b").order().by("name")
            .select("a", "b").by("name").by("age").asAdmin();
    var select =
        (org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep<?,
            ?>) cursorAt(admin,
                org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep.class)
                .peek();
    assertThat(select.getSelectKeys()).containsExactly("a", "b");
    assertThat(select.getLocalChildren()).hasSize(2);
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

  /**
   * {@code valueMap("name","age")} pins both keys as presence / emit order under MAP with
   * list-wrap; RETURN stays the boundary entity only.
   */
  @Test
  public void valueMapMultiKey_pinsBothPresenceKeys() {
    var admin = graph.traversal().V().valueMap("name", "age").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAt(admin, PropertyMapStep.class);

    var outcome = PropertyMapStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.MAP);
    assertThat(ctx.shaping().presencePropertyKeys()).containsExactly("name", "age");
    assertThat(ctx.shaping().mapEmitColumnOrder()).containsExactly("name", "age");
    assertThat(ctx.returnAliases.stream().map(a -> a.getStringValue()))
        .containsExactly(BOUNDARY_ALIAS);
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
