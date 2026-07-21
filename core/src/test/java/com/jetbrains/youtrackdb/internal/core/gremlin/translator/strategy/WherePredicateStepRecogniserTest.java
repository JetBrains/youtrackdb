package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
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
 * Unit tests for {@link WherePredicateStepRecogniser}. Label-reference {@code where(P)} shapes emit
 * {@code $matched.<label>} accessors; full end-to-end parity with {@code as(label)} wiring lands in
 * Track 6.
 */
public class WherePredicateStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT =
      Set.of(NoOpBarrierStep.class, WhereTraversalStep.WhereStartStep.class,
          WhereTraversalStep.WhereEndStep.class);

  /** {@code where(P.eq("a"))} maps to {@code @rid = $matched.a.@rid} on the boundary alias. */
  @Test
  public void labelEq_comparesBoundaryRidToMatchedAlias() {
    var admin = graph.traversal().V().as("a").where(P.eq("a")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAtWherePredicate(admin);

    var outcome = WherePredicateStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    var rendered = renderBoundaryFilter(ctx);
    assertThat(rendered).containsIgnoringCase("@rid");
    assertThat(rendered).contains("$matched.a.@rid");
  }

  /** {@code where("a", P.eq("b"))} compares two {@code $matched} aliases by {@code @rid}. */
  @Test
  public void scopedLabelEq_comparesTwoMatchedAliases() {
    var admin = graph.traversal().V().as("a").as("b").where("a", P.eq("b")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAtWherePredicate(admin);

    var outcome = WherePredicateStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    var rendered = renderBoundaryFilter(ctx);
    assertThat(rendered).contains("$matched.a.@rid");
    assertThat(rendered).contains("$matched.b.@rid");
  }

  /** {@code where(P).by(...)} carries a modulator child and declines. */
  @Test
  public void modulateByChild_declines() {
    var admin = graph.traversal().V().as("a").where(P.eq("a")).by("name").asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAtWherePredicate(admin);

    var outcome = WherePredicateStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).isEmpty();
  }

  /** Blank label references decline — not a valid {@code $matched} accessor. */
  @Test
  public void blankLabelReference_declines() {
    var admin = graph.traversal().V().where(P.eq("")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAtWherePredicate(admin);

    var outcome = WherePredicateStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
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

  private static StepStreamCursor cursorAtWherePredicate(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    while (cursor.peek() != null) {
      if (cursor.peek() instanceof WherePredicateStep) {
        return cursor;
      }
      cursor.take();
    }
    throw new AssertionError("WherePredicateStep not found in traversal");
  }

  private static String renderBoundaryFilter(WalkerContext ctx) {
    var clause = ctx.aliasFilters.get(BOUNDARY_ALIAS);
    assertThat(clause).isNotNull();
    var sb = new StringBuilder();
    clause.getBaseExpression().toGenericStatement(sb);
    return sb.toString();
  }
}
