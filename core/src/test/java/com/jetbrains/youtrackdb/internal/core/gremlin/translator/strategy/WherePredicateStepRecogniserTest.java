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
 * {@code $matched.<alias>} accessors, resolving the user's Gremlin label through the walker's
 * label-to-alias map first. End-to-end parity against native lives in
 * {@code PredicateTraversalEquivalenceTest}.
 */
public class WherePredicateStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  /** Mirrors {@link GremlinStepWalker}'s production transparency set. The {@code where(...)} scope
   *  steps are absent from both: they carry the child's scope binding, so skipping them would
   *  translate a weaker filter than the user wrote. Keeping the two sets equal is what makes a
   *  decline observed here mean the same thing as a decline in production. */
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /** {@code where(P.eq("a"))} maps to {@code @rid = $matched.a.@rid} on the boundary alias. */
  @Test
  public void labelEq_comparesBoundaryRidToMatchedAlias() {
    var admin = graph.traversal().V().as("a").where(P.eq("a")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    bindLabelsToBoundary(admin, ctx);
    var cursor = cursorAtWherePredicate(admin);

    var outcome = WherePredicateStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    var rendered = renderBoundaryFilter(ctx);
    assertThat(rendered).containsIgnoringCase("@rid");
    assertThat(rendered).contains("$matched." + BOUNDARY_ALIAS + ".@rid");
  }

  /** {@code where("a", P.eq("b"))} compares two {@code $matched} aliases by {@code @rid}. */
  @Test
  public void scopedLabelEq_comparesTwoMatchedAliases() {
    var admin = graph.traversal().V().as("a").as("b").where("a", P.eq("b")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    bindLabelsToBoundary(admin, ctx);
    var cursor = cursorAtWherePredicate(admin);

    var outcome = WherePredicateStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    var rendered = renderBoundaryFilter(ctx);
    // Both labels sit on the same node here, so both accessors resolve to the boundary alias.
    assertThat(rendered).contains("$matched." + BOUNDARY_ALIAS + ".@rid");
    assertThat(rendered.split(java.util.regex.Pattern.quote("$matched"), -1)).hasSize(3);
  }

  /**
   * A label the walker never bound to a pattern node declines. {@code $matched} rows are keyed on
   * pattern aliases, so an accessor built from a raw Gremlin label reads nothing at execution time
   * and the comparison either keeps every candidate or drops every candidate — an over- or
   * under-large multiset with no error either way. Declining hands the shape to native Gremlin,
   * which is the only exit that cannot be silently wrong.
   */
  @Test
  public void unboundLabelReference_declines() {
    var admin = graph.traversal().V().as("a").where(P.eq("a")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    // Deliberately skip bindLabelsToBoundary: "a" resolves to no pattern alias.
    var cursor = cursorAtWherePredicate(admin);

    var outcome = WherePredicateStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).isEmpty();
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

  /**
   * Binds every {@code as(...)} label carried by the steps ahead of the {@code where} onto the
   * boundary alias, standing in for the walker pass that normally does it. These tests drive the
   * recogniser in isolation, so without this the labels resolve to nothing and the recogniser
   * declines.
   */
  private static void bindLabelsToBoundary(Traversal.Admin<?, ?> admin, WalkerContext ctx) {
    for (var step : admin.getSteps()) {
      if (step instanceof WherePredicateStep) {
        break;
      }
      assertThat(ctx.bindStepLabels(step, BOUNDARY_ALIAS)).isTrue();
    }
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
