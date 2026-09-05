package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.UnfoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Pins composition-related branches the equivalence suite leaves cold: schema gates, group-entry
 * unfold identity, row-dedup shaping / cardinality capture, polymorphic closure edge cases, and
 * post-concat order construction.
 */
public class CompositionCoverageGapTest extends GraphBaseTest {

  private static final String BOUNDARY = "$g2m_v0";

  /**
   * {@code has(key, eq([null]))} after singleton unwrap rewrites to {@code IS NULL}, same as a bare
   * {@code eq(null)}.
   */
  @Test
  public void eqSingletonCollectionOfNull_rewritesToIsNull() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("age", P.eq(Collections.singletonList(null))));

    assertThat(expr).isNotNull();
    assertThat(expr.toString()).containsIgnoringCase("is null");
  }

  /**
   * {@code has(key, neq([null]))} after singleton unwrap rewrites to {@code NOT (… IS NULL)}.
   */
  @Test
  public void neqSingletonCollectionOfNull_rewritesToNotIsNull() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("age", P.neq(Collections.singletonList(null))));

    assertThat(expr).isNotNull();
    assertThat(expr.toString()).containsIgnoringCase("is null");
  }

  /**
   * Multi-label edge {@code has} with a null class array must not claim declared types (strict
   * type guard stays on).
   */
  @Test
  public void schemaGate_nullClassNames_declaresNothing() {
    var ctx = new WalkerContext(true, false, session.getSchema());
    var gate = GremlinPredicateAdapter.schemaGate(ctx, (String[]) null);

    assertThat(gate.isDeclaredString("name")).isFalse();
    assertThat(gate.declaredTypeIn("age", List.of("INTEGER"))).isFalse();
  }

  /**
   * A second {@code unfold()} after {@code groupCount().unfold()} is already emitting entries —
   * identity accept, no extra list-shaping stage.
   */
  @Test
  public void secondUnfold_afterGroupEntryEmit_acceptsAsIdentity() {
    var admin = graph.traversal().V().unfold().asAdmin();
    var ctx = seededContext();
    ctx.setGroupBy(new SQLGroupBy(-1));
    ctx.enableGroupEntryEmit();
    assertThat(ctx.emitGroupEntries()).isTrue();
    var opsBefore = ctx.listShapingOps().size();

    var outcome = UnfoldStepRecogniser.INSTANCE.recognize(
        cursorAt(admin, UnfoldStep.class), ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.listShapingOps()).hasSize(opsBefore);
  }

  /**
   * {@code setRowDedupAlias} flips cardinality capture so a following {@code select().by} can choose
   * post-plan presence instead of pattern {@code IS DEFINED}.
   */
  @Test
  public void rowDedupAlias_capturesCardinalityAndRoundTripsShaping() {
    var ctx = seededContext();
    assertThat(ctx.cardinalityClauseCaptured()).isFalse();

    ctx.setRowDedupAlias("$g2m_a");

    assertThat(ctx.rowDedupAlias()).isEqualTo("$g2m_a");
    assertThat(ctx.cardinalityClauseCaptured()).isTrue();
    assertThat(ctx.shaping().rowDedupAlias()).isEqualTo("$g2m_a");
    assertThat(ResultShaping.NONE.withRowDedupAlias("$g2m_a").rowDedupAlias())
        .isEqualTo("$g2m_a");
  }

  /**
   * Polymorphic closure skips blank roots, keeps missing class names as-is, and expands subclasses.
   */
  @Test
  public void expandPolymorphicClassClosure_skipsBlanksAndExpandsSubclasses() {
    session.getSchema().createClass("Person", session.getSchema().getClass("V"));
    session.getSchema().createClass("Employee", session.getSchema().getClass("Person"));
    var ctx = new WalkerContext(true, false, session.getSchema());

    assertThat(ctx.expandPolymorphicClassClosure(List.of()))
        .isEmpty();
    assertThat(ctx.expandPolymorphicClassClosure(List.of("Person", "", "Ghost")))
        .contains("Person", "Employee", "Ghost")
        .doesNotContain("");
  }

  /**
   * Schema-declared keys on a typed boundary are enumerated for keyed maps; the {@code V} root and a
   * missing class yield none.
   */
  @Test
  public void boundaryDeclaredPropertyKeys_listsSchemaKeysOnTypedBoundary() {
    session.getSchema().createClass("Person", session.getSchema().getClass("V"));
    session.getSchema().getClass("Person").createProperty("name", PropertyType.STRING);
    session.getSchema().getClass("Person").createProperty("age", PropertyType.INTEGER);
    var typed = new WalkerContext(true, false, session.getSchema());
    typed.addNode(BOUNDARY, "Person");
    typed.pinBoundary(BOUNDARY, BoundaryOutputType.ELEMENT, Vertex.class);

    assertThat(typed.boundaryDeclaredPropertyKeys()).containsExactly("age", "name");

    var root = new WalkerContext(true, false, session.getSchema());
    root.addNode(BOUNDARY, WalkerContext.VERTEX_ROOT_CLASS);
    root.pinBoundary(BOUNDARY, BoundaryOutputType.ELEMENT, Vertex.class);
    assertThat(root.boundaryDeclaredPropertyKeys()).isEmpty();

    var noSchema = new WalkerContext(true, false);
    noSchema.addNode(BOUNDARY, "Person");
    noSchema.pinBoundary(BOUNDARY, BoundaryOutputType.ELEMENT, Vertex.class);
    assertThat(noSchema.boundaryDeclaredPropertyKeys()).isEmpty();
  }

  /**
   * Sub-walks forward schema/edge-alias reads and swallow row-dedup / group-entry writes.
   */
  @Test
  public void subTraversal_delegatesSchemaReadsAndSwallowsCardinalityWrites() {
    var parent = mock(RecognitionContext.class);
    when(parent.expandPolymorphicClassClosure(List.of("Person")))
        .thenReturn(List.of("Person", "Employee"));
    when(parent.boundaryDeclaredPropertyKeys()).thenReturn(List.of("name"));
    when(parent.isEdgeAlias("$g2m_e0")).thenReturn(true);
    when(parent.rowDedupAlias()).thenReturn("$g2m_a");
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());

    assertThat(adapter.expandPolymorphicClassClosure(List.of("Person")))
        .containsExactly("Person", "Employee");
    assertThat(adapter.boundaryDeclaredPropertyKeys()).containsExactly("name");
    assertThat(adapter.isEdgeAlias("$g2m_e0")).isTrue();
    assertThat(adapter.rowDedupAlias()).isEqualTo("$g2m_a");

    adapter.markEdgeAlias("$g2m_e1");
    verify(parent).markEdgeAlias("$g2m_e1");

    adapter.setRowDedupAlias("$g2m_b");
    adapter.enableGroupEntryEmit();
    verify(parent, org.mockito.Mockito.never())
        .setRowDedupAlias(org.mockito.ArgumentMatchers.any());
    verify(parent, org.mockito.Mockito.never()).enableGroupEntryEmit();
  }

  /** Post-union {@code order()} with no sort keys cannot be constructed. */
  @Test
  public void postConcatOrder_rejectsEmptySortKeys() {
    assertThatThrownBy(() -> new PostConcatOp.Order(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one sort key");
  }

  private static WalkerContext seededContext() {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(BOUNDARY, "V");
    ctx.pinBoundary(BOUNDARY, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY);
    return ctx;
  }

  private static StepStreamCursor cursorAt(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> admin,
      Class<?> stepType) {
    var cursor = new StepStreamCursor(admin.getSteps(), java.util.Set.of());
    while (cursor.peek() != null) {
      if (stepType.isInstance(cursor.peek())) {
        return cursor;
      }
      cursor.take();
    }
    throw new AssertionError("Step not found: " + stepType.getSimpleName());
  }
}
