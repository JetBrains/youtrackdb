package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link NoOpBarrierRecogniser}, which claims the {@link NoOpBarrierStep} {@code
 * LazyBarrierStrategy} wedges between chained hops so a multi-hop chain does not decline at the
 * barrier. The recogniser is a transparent pass-through: it reports one consumed step and mutates
 * nothing else. These drive it directly with a hand-built {@link WalkerContext}; the end-to-end
 * multi-hop recognition is covered by {@link EdgeTraversalEquivalenceTest}.
 */
public class NoOpBarrierRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";

  /**
   * A {@link NoOpBarrierStep} is claimed as exactly one consumed step, leaving every piece of
   * context — the pinned boundary, the RETURN column, the pattern, the alias counters, and the
   * cursor itself (the walker, not the recogniser, advances it) — exactly as it was. A barrier
   * contributes nothing to the MATCH plan, so this transparent pass-through is what lets the hops on
   * either side chain off each other.
   */
  @Test
  public void barrier_isClaimedAsOneConsumedStep() {
    var admin = graph.traversal().V().asAdmin();
    var ctx = contextWithStartBoundary(admin);
    NoOpBarrierStep<?> barrier = new NoOpBarrierStep<>(admin);

    var recognized = NoOpBarrierRecogniser.INSTANCE.recognize(barrier, ctx);

    assertThat(recognized).as("a top-level barrier consumes exactly one step").isEqualTo(1);
    assertThat(ctx.stepIndex)
        .as("the recogniser reports the count; it does not advance the cursor (the walker does)")
        .isEqualTo(1);
    // Nothing else moved: the boundary the prior hop pinned is intact, so a multi-hop chain resumes
    // exactly where it left off.
    assertThat(ctx.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.nextAnonVertexAlias())
        .as("the barrier mints no anonymous alias")
        .isEqualTo("$g2m_anon_0");
  }

  /**
   * A non-{@link NoOpBarrierStep} (defence-in-depth against a future registry mistake) declines
   * cleanly rather than advancing the cursor over a step it never validated. The start {@code
   * GraphStep} is fed to the recogniser directly.
   */
  @Test
  public void nonBarrierStep_declines() {
    var admin = graph.traversal().V().asAdmin();
    var ctx = contextWithStartBoundary(admin);
    var graphStep = admin.getSteps().getFirst();

    var recognized = NoOpBarrierRecogniser.INSTANCE.recognize(graphStep, ctx);

    assertThat(recognized).as("a non-barrier step must decline (0), not throw").isEqualTo(0);
    assertThat(ctx.stepIndex).as("cursor must not move on decline").isEqualTo(1);
  }

  /**
   * Builds a context pre-seeded as a prior hop would leave it: a pinned {@code $g2m_v0} boundary with
   * one RETURN column and the cursor positioned at the barrier. A claimed barrier must leave all of
   * that intact and only advance the cursor.
   */
  private static WalkerContext contextWithStartBoundary(Traversal.Admin<?, ?> admin) {
    var ctx = new WalkerContext(admin, true);
    ctx.patternBuilder.addNode(BOUNDARY_ALIAS, "V", null, false);
    ctx.boundaryAlias = BOUNDARY_ALIAS;
    ctx.outputType = BoundaryOutputType.ELEMENT;
    ctx.returnClass = Vertex.class;
    ctx.returnItems.add(new SQLExpression(new SQLIdentifier(BOUNDARY_ALIAS)));
    ctx.returnAliases.add(new SQLIdentifier(BOUNDARY_ALIAS));
    ctx.returnNestedProjections.add(null);
    ctx.stepIndex = 1; // positioned at the barrier, after the start step
    return ctx;
  }
}
