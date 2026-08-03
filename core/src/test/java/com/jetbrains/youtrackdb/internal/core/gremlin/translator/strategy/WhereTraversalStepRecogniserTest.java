package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
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
 * Unit tests for {@link TraversalFilterStepRecogniser}'s {@code where(traversal)} branch. End-to-end
 * multiset equivalence for {@code where(traversal)} lives in {@link PredicateTraversalEquivalenceTest}
 * and {@link EdgeTraversalEquivalenceTest}.
 */
public class WhereTraversalStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";
  private static final Set<Class<?>> TRANSPARENT =
      Set.of(NoOpBarrierStep.class, WhereTraversalStep.WhereStartStep.class,
          WhereTraversalStep.WhereEndStep.class);

  /** {@code where(has(age))} merges the captured pure-filter child into the boundary WHERE. */
  @Test
  public void pureFilterChild_mergesBoundaryPredicate() {
    var admin = graph.traversal().V().where(__.has("age", P.eq(30))).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAtTraversalFilter(admin);

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).contains("age");
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
  }

  /**
   * {@code where(out(knows))} declines. Appending the hop to the positive pattern turns the
   * existence test into a join that emits the source once per matching target, so the filter has to
   * stay on the native pipeline; the context must carry no hop alias and no edge afterwards.
   */
  @Test
  public void edgeBearingChild_declines() {
    var admin = graph.traversal().V().where(__.out("knows")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAtTraversalFilter(admin);

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isFalse();
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
    assertThat(ctx.aliasFilters).isEmpty();
  }

  /** A declined child sub-walk declines the whole {@code where(traversal)} filter. */
  @Test
  public void declinedChild_declinesWholeWhereTraversal() {
    var admin = graph.traversal().V().where(__.count()).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAtTraversalFilter(admin);

    var outcome = TraversalFilterStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).isEmpty();
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
  }

  /**
   * Path-scoped {@code where(__.as("a").has(...))} is a {@link WhereTraversalStep} (not
   * TraversalFilterStep). Pure-filter child merges into the boundary WHERE.
   */
  @Test
  public void whereTraversalStep_pathScopedPureFilter_mergesBoundaryPredicate() {
    var admin = graph.traversal().V().as("a").where(__.as("a").has("age", P.eq(30))).asAdmin();
    var ctx = contextAfterStartWithRegistry(admin);
    var cursor = cursorAtWhereTraversal(admin);

    var outcome = WhereTraversalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).contains("age");
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
  }

  /**
   * The path-scoped {@link WhereTraversalStep} class reaches the same edge-bearing gate as the
   * {@code TraversalFilterStep} spelling above: {@code where(__.as("a").out("knows"))} declines and
   * commits nothing. Pinned separately because the two spellings enter through different recogniser
   * classes, and a gate added on only one of them would leave the over-emission live on the other.
   */
  @Test
  public void whereTraversalStep_edgeBearingChild_declines() {
    var admin = graph.traversal().V().as("a").where(__.as("a").out("knows")).asAdmin();
    var ctx = contextAfterStartWithRegistry(admin);
    var cursor = cursorAtWhereTraversal(admin);

    var outcome = WhereTraversalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isFalse();
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
    assertThat(ctx.aliasFilters).isEmpty();
  }

  /** Path-scoped where whose child declines (e.g. {@code count()}) declines the whole filter. */
  @Test
  public void whereTraversalStep_declinedChild_declines() {
    var admin = graph.traversal().V().as("a").where(__.as("a").count()).asAdmin();
    var ctx = contextAfterStartWithRegistry(admin);
    var cursor = cursorAtWhereTraversal(admin);

    var outcome = WhereTraversalStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
  }

  /** WhereTraversalStep without a boundary alias declines. */
  @Test
  public void whereTraversalStep_nullBoundary_declines() {
    var admin = graph.traversal().V().as("a").where(__.as("a").has("age", P.eq(30))).asAdmin();
    var ctx = new WalkerContext(true, false, null, productionRegistry());
    var cursor = cursorAtWhereTraversal(admin);

    assertThat(WhereTraversalStepRecogniser.INSTANCE.recognize(cursor, ctx))
        .isEqualTo(Outcome.DECLINE);
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

  private WalkerContext contextWithRegistry(boolean polymorphic, Schema schema) {
    var ctx = new WalkerContext(polymorphic, false, schema, productionRegistry());
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  private WalkerContext contextAfterStartWithRegistry(Traversal.Admin<?, ?> admin) {
    var ctx = new WalkerContext(true, false, session.getSchema(), productionRegistry());
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    assertThat(StartStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    return ctx;
  }

  private static StepStreamCursor cursorAtTraversalFilter(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    while (cursor.peek() != null) {
      if (cursor.peek() instanceof TraversalFilterStep) {
        return cursor;
      }
      cursor.take();
    }
    throw new AssertionError("TraversalFilterStep not found in traversal");
  }

  private static StepStreamCursor cursorAtWhereTraversal(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    while (cursor.peek() != null) {
      if (cursor.peek() instanceof WhereTraversalStep) {
        return cursor;
      }
      cursor.take();
    }
    throw new AssertionError(
        "WhereTraversalStep not found in traversal; steps=" + admin.getSteps());
  }

  private static String renderBoundaryFilter(WalkerContext ctx) {
    var clause = ctx.aliasFilters.get(BOUNDARY_ALIAS);
    assertThat(clause).isNotNull();
    var sb = new StringBuilder();
    clause.getBaseExpression().toGenericStatement(sb);
    return sb.toString();
  }
}
