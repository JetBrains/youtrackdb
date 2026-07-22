package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link DedupGlobalStepRecogniser}: anonymous {@code dedup()} sets {@code RETURN
 * DISTINCT}; named {@code dedup(labels...)} projects bound {@code as(...)} labels then sets
 * distinct; unbound labels and {@code by(...)} modulators decline.
 */
public class DedupGlobalStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /** {@code dedup()} with no scope keys sets {@code returnDistinct} and leaves RETURN columns alone. */
  @Test
  public void anonymousDedup_setsReturnDistinct() {
    var admin = graph.traversal().V().dedup().asAdmin();
    var ctx = seededContext();
    var cursor = cursorAtDedup(admin);

    var outcome = DedupGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.returnDistinct).isTrue();
    assertThat(ctx.returnItems).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
  }

  /**
   * {@code dedup("v")} after {@code as("v")} replaces RETURN with the internal alias surfaced under
   * the user label and sets distinct.
   */
  @Test
  public void namedDedup_projectsBoundLabel() {
    var admin = graph.traversal().V().as("v").dedup("v").asAdmin();
    var ctx = contextAfterStart(admin);
    var cursor = cursorAtDedup(admin);

    var outcome = DedupGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.returnDistinct).isTrue();
    assertThat(ctx.returnItems).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo("v");
    assertThat(ctx.returnItems.getFirst().toString()).contains(BOUNDARY_ALIAS);
  }

  /** {@code dedup("missing")} declines when the label was never bound by an accepted {@code as}. */
  @Test
  public void namedDedup_unboundLabel_declines() {
    var admin = graph.traversal().V().dedup("missing").asAdmin();
    var ctx = seededContext();
    var cursor = cursorAtDedup(admin);

    var outcome = DedupGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.returnDistinct).isFalse();
  }

  /** {@code dedup().by(...)} carries a modulator child and declines until ByModulatorTranslator. */
  @Test
  public void dedupWithByChild_declines() {
    var admin = graph.traversal().V().dedup().by("name").asAdmin();
    var ctx = seededContext();
    var cursor = cursorAtDedup(admin);

    var outcome = DedupGlobalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.returnDistinct).isFalse();
  }

  /** {@code g.V().as("v")} binds the start label through {@link StartStepRecogniser}. */
  @Test
  public void startStep_bindStepLabels_registersUserLabel() {
    var admin = graph.traversal().V().as("v").dedup().asAdmin();
    var ctx = contextAfterStart(admin);

    assertThat(ctx.userLabelToAlias).containsEntry("v", BOUNDARY_ALIAS);
    assertThat(ctx.patternBuilder.registeredUserLabels())
        .containsEntry(BOUNDARY_ALIAS, Set.of("v"));
  }

  /** Two labels on the same step both bind to the same internal alias ({@code as("a").as("b")}). */
  @Test
  public void startStep_multipleLabelsOnSameStep_bindBoth() {
    var admin = graph.traversal().V().as("a").as("b").dedup().asAdmin();
    var ctx = contextAfterStart(admin);

    assertThat(ctx.userLabelToAlias).containsEntry("a", BOUNDARY_ALIAS);
    assertThat(ctx.userLabelToAlias).containsEntry("b", BOUNDARY_ALIAS);
  }

  /** Reusing a user label on a later hop declines the whole walk. */
  @Test
  public void hopStep_labelCollision_declinesWholeWalk() {
    var admin = graph.traversal().V().as("x").out().as("x").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  private static WalkerContext seededContext() {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(BOUNDARY_ALIAS, WalkerContext.VERTEX_ROOT_CLASS);
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  private static WalkerContext contextAfterStart(Traversal.Admin<?, ?> admin) {
    var ctx = new WalkerContext(true, false);
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    var outcome = StartStepRecogniser.INSTANCE.recognize(cursor, ctx);
    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    return ctx;
  }

  private static StepStreamCursor cursorAtDedup(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    while (cursor.peek() != null) {
      if (cursor.peek() instanceof DedupGlobalStep) {
        return cursor;
      }
      cursor.take();
    }
    throw new AssertionError("DedupGlobalStep not found in traversal");
  }
}
