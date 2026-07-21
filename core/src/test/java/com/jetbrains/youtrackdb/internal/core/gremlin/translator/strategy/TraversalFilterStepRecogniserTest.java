package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WherePredicateStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
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
   * An unrecognised {@code filter(sub-traversal)} declines even with a production registry — e.g.
   * {@code count()} has no recogniser on the sub-walk path.
   */
  @Test
  public void unrecognisedFilterTraversal_declines() {
    var admin = graph.traversal().V().filter(__.count()).asAdmin();
    var ctx = contextWithRegistry(null);
    var cursor = cursorAfterStart(admin);

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("an unrecognised filter(...) must decline").isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).doesNotContainKey(BOUNDARY_ALIAS);
  }

  /** {@code where(out(knows))} (also {@code filter(out(knows))}) appends a hop via the sub-walker. */
  @Test
  public void whereTraversal_outKnows_appendsHop() {
    var admin = graph.traversal().V().where(__.out("knows")).asAdmin();
    var ctx = contextWithRegistry(null);
    var cursor = cursorAfterStart(admin);

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isEqualTo(1);
  }

  /**
   * A reserved-namespace presence key ({@code has("@class")}) declines: as a bare {@code IS DEFINED}
   * identifier the {@code @}-key would resolve as record metadata rather than a plain property,
   * diverging from native. The recogniser contributes nothing.
   */
  @Test
  public void reservedKeyPresence_declines() {
    var admin = graph.traversal().V().has("@class").asAdmin();
    var ctx = contextWithRegistry(null);
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
    return contextWithRegistry(null);
  }

  private static WalkerContext contextWithRegistry(Schema schema) {
    var ctx = new WalkerContext(true, false, schema, productionRegistry());
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  private static Map<Class<?>, StepRecogniser> productionRegistry() {
    return Map.of(
        GraphStep.class, StartStepRecogniser.INSTANCE,
        VertexStep.class, VertexStepRecogniser.INSTANCE,
        VertexStepPlaceholder.class, VertexStepRecogniser.INSTANCE,
        HasStep.class, HasStepRecogniser.INSTANCE,
        TraversalFilterStep.class, TraversalFilterStepRecogniser.INSTANCE,
        AndStep.class, AndStepRecogniser.INSTANCE,
        OrStep.class, OrStepRecogniser.INSTANCE,
        NotStep.class, NotStepRecogniser.INSTANCE,
        WhereTraversalStep.class, WhereTraversalStepRecogniser.INSTANCE,
        WherePredicateStep.class, WherePredicateStepRecogniser.INSTANCE);
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
