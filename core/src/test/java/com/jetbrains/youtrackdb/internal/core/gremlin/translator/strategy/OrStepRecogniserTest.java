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
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link OrStepRecogniser}. Each test drives the recogniser through a {@link
 * StepStreamCursor} over a strategised traversal with a registry-bearing {@link WalkerContext}. OR
 * requires all children to be pure-filter; edge-bearing children decline the whole step.
 */
public class OrStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /**
   * {@code or(has(age,30), has(age,40))} composes the two captured filters with OR and commits once
   * on the boundary alias.
   */
  @Test
  public void pureFilterChildren_orComposesOnceOnBoundary() {
    var admin =
        graph
            .traversal()
            .V()
            .or(__.has("age", P.eq(30)), __.has("age", P.eq(40)))
            .asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(cursor.peek()).isInstanceOf(OrStep.class);
    var outcome = OrStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    var rendered = renderBoundaryFilter(ctx);
    assertThat(rendered).contains("age").contains("OR");
  }

  /**
   * {@code or(out(a), has(age))} declines: the first child is edge-bearing and OR cannot absorb pattern
   * fragments in Phase 1.
   */
  @Test
  public void edgeBearingChild_declinesWholeOrStep() {
    var admin =
        graph.traversal().V().or(__.out("a"), __.has("age", P.eq(30))).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = OrStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).doesNotContainKey(BOUNDARY_ALIAS);
  }

  /**
   * Under polymorphic mode {@code hasLabel(L)} is re-type-only (no {@code @class} in the child's
   * WHERE). OR must fold each child's boundary re-type into that child's OR operand as {@code
   * classEquals}, otherwise {@code or(hasLabel(Person).has(age,30), hasLabel(Company).has(age,40))}
   * would keep only the age predicates and lose label discrimination.
   */
  @Test
  public void polymorphicHasLabelPlusHas_foldsClassEqualsIntoEachOrOperand() {
    session.createVertexClass("Person");
    session.createVertexClass("Company");

    var admin =
        graph
            .traversal()
            .V()
            .or(
                __.hasLabel("Person").has("age", P.eq(30)),
                __.hasLabel("Company").has("age", P.eq(40)))
            .asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = OrStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    var rendered = renderBoundaryFilter(ctx);
    // toGenericStatement collapses string class names to '?', so assert shape not literals —
    // end-to-end Person/Company discrimination is pinned by
    // PredicateTraversalEquivalenceTest.polymorphicOrHasLabelPlusHas_matchesNative.
    assertThat(rendered)
        .contains("@class = ")
        .contains("age")
        .contains("OR")
        .containsIgnoringCase(" AND ");
    assertThat(countOccurrences(rendered, "@class = ")).isEqualTo(2);
  }

  private static int countOccurrences(String haystack, String needle) {
    var count = 0;
    for (var i = 0; (i = haystack.indexOf(needle, i)) >= 0; i += needle.length()) {
      count++;
    }
    return count;
  }

  /** Without a pinned boundary the recogniser declines rather than inventing an origin alias. */
  @Test
  public void nullBoundary_declines() {
    var admin =
        graph.traversal().V().or(__.has("age", P.eq(30)), __.has("age", P.eq(40))).asAdmin();
    var ctx = new WalkerContext(true, false, null, productionRegistry());
    var cursor = cursorAfterStart(admin);

    assertThat(OrStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
  }

  /**
   * Feeding the recogniser a non-{@code OrStep} head declines and leaves the outer context untouched.
   */
  @Test
  public void nonOrStepHead_declines() {
    var admin = graph.traversal().V().has("age", P.eq(30)).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(OrStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).doesNotContainKey(BOUNDARY_ALIAS);
  }

  private static Map<Class<?>, StepRecogniser> productionRegistry() {
    return Map.of(
        GraphStep.class, StartStepRecogniser.INSTANCE,
        VertexStep.class, VertexStepRecogniser.INSTANCE,
        VertexStepPlaceholder.class, VertexStepRecogniser.INSTANCE,
        HasStep.class, HasStepRecogniser.INSTANCE,
        TraversalFilterStep.class, TraversalFilterStepRecogniser.INSTANCE,
        AndStep.class, AndStepRecogniser.INSTANCE,
        OrStep.class, OrStepRecogniser.INSTANCE);
  }

  private WalkerContext contextWithRegistry(boolean polymorphic, Schema schema) {
    var ctx = new WalkerContext(polymorphic, false, schema, productionRegistry());
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  private static StepStreamCursor cursorAfterStart(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take();
    return cursor;
  }

  private static String renderBoundaryFilter(WalkerContext ctx) {
    var clause = ctx.aliasFilters.get(BOUNDARY_ALIAS);
    assertThat(clause).isNotNull();
    var sb = new StringBuilder();
    clause.getBaseExpression().toGenericStatement(sb);
    return sb.toString();
  }
}
