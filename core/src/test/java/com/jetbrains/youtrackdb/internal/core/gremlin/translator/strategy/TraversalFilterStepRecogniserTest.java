package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link TraversalFilterStepRecogniser}, the recogniser for the {@code has(key)}
 * presence form (which desugars to {@code TraversalFilterStep(__.values(key))}). Each test drives the
 * recogniser directly with a {@link StepStreamCursor} over the raw DSL step list and a hand-built
 * {@link WalkerContext}, pinning the {@code IS DEFINED} contribution and each decline path. End-to-end
 * multiset equivalence lives in {@link PredicateTraversalEquivalenceTest}.
 */
public class TraversalFilterStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /**
   * {@code has("nickname")} is claimed and contributes {@code nickname IS DEFINED} on the boundary
   * alias — the entity-presence predicate, distinct from a value comparison. Consumes one step.
   */
  @Test
  public void hasKeyPresence_contributesIsDefined() {
    var admin = graph.traversal().V().has("nickname").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("has(key) presence is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before).as("consumes one step").isEqualTo(1);
    assertThat(renderBoundaryFilter(ctx)).containsIgnoringCase("nickname is defined");
  }

  /**
   * The {@code properties(key)} presence form is also claimed: an optimisation strategy rewrites the
   * {@code has(key)} desugar's {@code values(key)} child into {@code properties(key)} before g2m runs,
   * and both mean "the element has the property". {@code filter(__.properties("nickname"))} therefore
   * maps to {@code nickname IS DEFINED} too. This pins that both properties-step return types are
   * accepted, matching the shape production actually produces.
   */
  @Test
  public void propertiesKeyPresence_contributesIsDefined() {
    var admin = graph.traversal().V().filter(__.properties("nickname")).asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("properties(key) presence is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).containsIgnoringCase("nickname is defined");
  }

  /**
   * A general {@code filter(sub-traversal)} declines: its filter traversal is not the single {@code
   * values(key)} step the presence form desugars to, so the recogniser contributes nothing. General
   * filter translation is a later track's concern.
   */
  @Test
  public void generalFilterTraversal_declines() {
    var admin = graph.traversal().V().filter(__.out("knows")).asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a general filter(...) must decline").isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).doesNotContainKey(BOUNDARY_ALIAS);
  }

  /**
   * A reserved-namespace presence key ({@code has("@class")}) declines: as a bare {@code IS DEFINED}
   * identifier the {@code @}-key would resolve as record metadata rather than a plain property,
   * diverging from native. The recogniser contributes nothing.
   */
  @Test
  public void reservedKeyPresence_declines() {
    var admin = graph.traversal().V().has("@class").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a reserved-key presence must decline").isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).doesNotContainKey(BOUNDARY_ALIAS);
  }

  /** A presence filter reaching the recogniser with no pinned boundary declines — nothing to filter. */
  @Test
  public void nullBoundary_declines() {
    var admin = graph.traversal().V().has("nickname").asAdmin();
    var ctx = new WalkerContext(true, false, null); // boundaryAlias stays null
    var cursor = cursorAfterStart(admin);

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a presence filter with no boundary must decline")
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.boundaryAlias).isNull();
  }

  /** A non-TraversalFilterStep head declines cleanly rather than throwing (defence in depth). */
  @Test
  public void nonTraversalFilterStep_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin); // head is a VertexStep, not a TraversalFilterStep

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a non-TraversalFilterStep head must decline")
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).doesNotContainKey(BOUNDARY_ALIAS);
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  private static WalkerContext contextWithStartBoundary() {
    var ctx = new WalkerContext(true, false, null);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  private static StepStreamCursor cursorAfterStart(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take(); // consume the start GraphStep, leaving the head at the filter step (or hop)
    return cursor;
  }

  private static String renderBoundaryFilter(WalkerContext ctx) {
    SQLWhereClause clause = ctx.aliasFilters.get(BOUNDARY_ALIAS);
    assertThat(clause).as("a filter was contributed on the boundary alias").isNotNull();
    var sb = new StringBuilder();
    clause.getBaseExpression().toGenericStatement(sb);
    return sb.toString();
  }
}
