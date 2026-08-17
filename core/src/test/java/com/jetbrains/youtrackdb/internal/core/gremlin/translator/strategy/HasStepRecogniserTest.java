package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link HasStepRecogniser}, the recogniser that claims the single {@link
 * org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep} that {@code has(...)} / {@code
 * hasLabel(...)} / {@code hasId(...)} all produce at translator time. Each test drives the recogniser
 * directly with a {@link StepStreamCursor} over the raw (un-strategised) DSL step list and a
 * hand-built {@link WalkerContext} pre-seeded as the start step leaves it, so each container-key
 * branch and decline path is pinned in isolation. End-to-end multiset equivalence lives in {@link
 * PredicateTraversalEquivalenceTest}.
 */
public class HasStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  // ---------------------------------------------------------------------------
  // Property has() → adapter filter on the boundary alias.
  // ---------------------------------------------------------------------------

  /**
   * {@code has("name", P.eq("Alice"))} is claimed: the recogniser translates the container through the
   * predicate adapter and contributes a single {@code name = 'Alice'} filter on the boundary alias,
   * leaving the boundary node's class unchanged (a property has() does not re-type). Consumes one step.
   */
  @Test
  public void propertyHas_contributesFilterOnBoundary() {
    var admin = graph.traversal().V().has("name", P.eq("Alice")).asAdmin();
    var ctx = contextWithStartBoundary(true, null);
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a property has() is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before).as("consumes one HasStep").isEqualTo(1);
    assertThat(renderBoundaryFilter(ctx)).contains("name = ");
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .as("a property has() does not re-type the boundary node")
        .containsEntry(BOUNDARY_ALIAS, "V");
  }

  // ---------------------------------------------------------------------------
  // hasLabel — re-typing under both polymorphism modes.
  // ---------------------------------------------------------------------------

  /**
   * Polymorphic {@code hasLabel("Person")} re-types the boundary node's class to {@code Person} with
   * NO extra {@code @class} filter: a polymorphic {@code SELECT FROM Person} scan already matches
   * subclasses, mirroring native hierarchy-aware {@code hasLabel}.
   */
  @Test
  public void hasLabelPolymorphic_reTypesBoundaryClass_noClassFilter() {
    session.createVertexClass("Person");
    var admin = graph.traversal().V().hasLabel("Person").asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a single-label hasLabel is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .as("polymorphic hasLabel re-types the boundary node to the labelled class")
        .containsEntry(BOUNDARY_ALIAS, "Person");
    assertThat(ctx.aliasFilters)
        .as("polymorphic hasLabel adds no @class filter — the re-typed scan matches subclasses")
        .doesNotContainKey(BOUNDARY_ALIAS);
  }

  /**
   * Non-polymorphic {@code hasLabel("Person")} re-types the boundary node to {@code Person} AND adds
   * an exact {@code @class = 'Person'} filter, so the polymorphic {@code SELECT FROM Person} scan is
   * filtered to the leaf class — mirroring native leaf-exact {@code hasLabel}.
   */
  @Test
  public void hasLabelNonPolymorphic_reTypesAndAddsClassEqualsFilter() {
    session.createVertexClass("Person");
    var admin = graph.traversal().V().hasLabel("Person").asAdmin();
    var ctx = contextWithStartBoundary(false, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.build().aliasClasses()).containsEntry(BOUNDARY_ALIAS, "Person");
    assertThat(renderBoundaryFilter(ctx))
        .as("non-polymorphic hasLabel adds an exact @class = 'Person' leaf filter")
        .contains("@class = ");
  }

  /**
   * Non-polymorphic multi-label {@code hasLabel("Person", "Employee")} translates to {@code @class IN
   * [Person, Employee]} without re-typing to a single class. Stays on the generic {@code V} root.
   * Leaf-exact {@code @class IN} mirrors native non-polymorphic {@code hasLabel} exactly.
   */
  @Test
  public void hasLabelMultiLabelNonPolymorphic_contributesClassInFilter() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    var admin = graph.traversal().V().hasLabel("Person", "Employee").asAdmin();
    var ctx = contextWithStartBoundary(false, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .as("multi-label hasLabel does not re-type the boundary node")
        .containsEntry(BOUNDARY_ALIAS, "V");
    assertThat(renderBoundaryFilter(ctx))
        .as("multi-label hasLabel contributes @class IN [...]")
        .contains("@class IN");
  }

  /**
   * Polymorphic multi-label {@code hasLabel("Person", "Employee")} declines: {@code @class IN [...]}
   * is leaf-exact and drops polymorphic subclasses of the listed classes, under-matching native
   * hierarchy-aware {@code hasLabel}. No cheap subclass expansion exists here, so it falls back to
   * native. The recogniser contributes nothing.
   */
  @Test
  public void hasLabelMultiLabelPolymorphic_declines() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    var admin = graph.traversal().V().hasLabel("Person", "Employee").asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("polymorphic multi-label hasLabel must decline (subclass undercount)")
        .isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * Two conflicting {@code ~label} containers ({@code hasLabel("Person").hasLabel("Employee")}, which
   * fold into one HasStep) decline: one MATCH node has one class. The recogniser contributes nothing.
   */
  @Test
  public void hasLabelConflictingLabels_declines() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    var admin = graph.traversal().V().hasLabel("Person").hasLabel("Employee").asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("two conflicting ~label containers must decline")
        .isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * {@code hasLabel("Missing")} on a class that does not exist stays on the generic {@code V} root
   * and filters {@code @class = Missing}, yielding an empty plan like native rather than {@code
   * SELECT FROM Missing}.
   */
  @Test
  public void hasLabelNonExistentClass_contributesClassEqualsOnV() {
    var admin = graph.traversal().V().hasLabel("Missing").asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .containsEntry(BOUNDARY_ALIAS, "V");
    assertThat(renderBoundaryFilter(ctx)).contains("@class = ");
  }

  // ---------------------------------------------------------------------------
  // hasId — @rid IN, set-membership duplicate handling.
  // ---------------------------------------------------------------------------

  /** {@code hasId(id)} contributes an {@code @rid IN [...]} filter on the boundary alias. */
  @Test
  public void hasIdSingle_contributesRidIn() {
    var alice = graph.addVertex(T.label, "Person");
    graph.tx().commit();
    var admin = graph.traversal().V().hasId(alice.id()).asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("hasId(id) is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).containsIgnoringCase("@rid").contains(" IN ");
  }

  /**
   * {@code hasId(id, id)} with a repeated id does NOT decline — {@code hasId} is set membership, so a
   * duplicate maps to the same {@code @rid IN [id]} filter (unlike {@code g.V(id, id)} seek
   * semantics, which the start step declines). This pins that the branch calls {@code toRecordIds}
   * without the start step's duplicate decline.
   */
  @Test
  public void hasIdDuplicate_doesNotDecline() {
    var alice = graph.addVertex(T.label, "Person");
    graph.tx().commit();
    var admin = graph.traversal().V().hasId(alice.id(), alice.id()).asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a duplicate hasId is set membership, not a decline")
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).containsIgnoringCase("@rid").contains(" IN ");
  }

  /**
   * {@code hasId("not-a-rid")} declines: the string is not a convertible RID, so id normalisation
   * returns a decline and the recogniser contributes nothing.
   */
  @Test
  public void hasIdUnconvertible_declines() {
    var admin = graph.traversal().V().hasId("not-a-rid").asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("an unconvertible hasId must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  // ---------------------------------------------------------------------------
  // Mixed containers in one step + the no-mutation-on-decline invariant.
  // ---------------------------------------------------------------------------

  /**
   * {@code hasLabel("Person").has("name", "Alice")} folds into one HasStep and both contributions
   * land: the boundary node re-types to {@code Person} (non-polymorphic adds {@code @class}), and the
   * {@code name = 'Alice'} filter AND-composes with it. This pins the translate-all-then-contribute
   * shape across a mixed step.
   */
  @Test
  public void hasLabelAndProperty_sameStep_reTypesAndAndComposesFilter() {
    session.createVertexClass("Person");
    var admin = graph.traversal().V().hasLabel("Person").has("name", "Alice").asAdmin();
    var ctx = contextWithStartBoundary(false, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.build().aliasClasses()).containsEntry(BOUNDARY_ALIAS, "Person");
    assertThat(renderBoundaryFilter(ctx))
        .as("the @class narrowing and the property filter AND-compose")
        .contains("@class = ").contains("name = ").containsIgnoringCase(" AND ");
  }

  /**
   * A reserved-key container ({@code has("@class", "X")}) declines the whole step with zero context
   * mutation: the predicate adapter declines the reserved {@code @}-namespace key, and because
   * the recogniser translates every container before contributing, nothing was written first.
   */
  @Test
  public void reservedKeyContainer_declinesWithNoMutation() {
    var admin = graph.traversal().V().has("@class", "X").asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a reserved-key container must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  // ---------------------------------------------------------------------------
  // User as(...) labels parked on the filter step.
  // ---------------------------------------------------------------------------

  /**
   * A {@code has(...)} carrying a user {@code as("a")} binds the label to the boundary alias, so a
   * later {@code select("a")} / {@code dedup("a")} can resolve it. TinkerPop's
   * {@code FilterRankingStrategy} relocates a label off the step the user wrote it on and onto the
   * following filter, so a label parked here is routine rather than exotic; before the bind it was
   * dropped and every consumer of it declined.
   */
  @Test
  public void userLabelOnHas_bindsToBoundaryAlias() {
    var admin = graph.traversal().V().has("name", "Alice").as("a").asAdmin();
    var ctx = contextWithStartBoundary(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a labelled has() is still accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.resolveUserLabel("a"))
        .as("the label resolves to the alias the filter was contributed on")
        .isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.patternBuilder.registeredUserLabels())
        .as("and reaches the pattern, which is what a projection reads it back from")
        .containsEntry(BOUNDARY_ALIAS, Set.of("a"));
  }

  /**
   * Bind-or-decline: a user label already bound to a <em>different</em> internal alias declines the
   * whole walk rather than overwriting the earlier binding, and contributes nothing on the way out —
   * no re-type, no filter. Silently rebinding would make a later {@code select("a")} resolve to the
   * wrong node, which is a wrong answer rather than a decline.
   */
  @Test
  public void userLabelAlreadyBoundElsewhere_declinesWithoutContributing() {
    var priorlyLabelled = graph.traversal().V().as("a").asAdmin().getStartStep();
    var admin = graph.traversal().V().has("name", "Alice").as("a").asAdmin();
    var ctx = contextWithStartBoundary(true, null);
    assertThat(ctx.bindStepLabels(priorlyLabelled, "$g2m_v1"))
        .as("precondition: a is already bound to another alias")
        .isTrue();
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome)
        .as("a colliding label must decline the whole walk")
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.resolveUserLabel("a"))
        .as("the earlier binding survives the refused one")
        .isEqualTo("$g2m_v1");
    assertContributedNothing(ctx);
  }

  // ---------------------------------------------------------------------------
  // Defensive declines.
  // ---------------------------------------------------------------------------

  /** A HasStep reaching the recogniser with no pinned boundary declines — nothing to filter. */
  @Test
  public void nullBoundary_declines() {
    var admin = graph.traversal().V().has("name", "Alice").asAdmin();
    var ctx = new WalkerContext(true, false, null); // boundaryAlias stays null
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a has() with no boundary must decline").isEqualTo(Outcome.DECLINE);
    assertThat(ctx.boundaryAlias).isNull();
  }

  /** A non-HasStep head declines cleanly rather than throwing (defence in depth). */
  @Test
  public void nonHasStep_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = contextWithStartBoundary(true, null);
    var cursor = cursorAfterStart(admin); // head is the VertexStep, not a HasStep

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a non-HasStep head must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  private WalkerContext contextWithStartBoundary(boolean polymorphic, Schema schema) {
    var ctx = new WalkerContext(polymorphic, false, schema);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  private static StepStreamCursor cursorAfterStart(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take(); // consume the start GraphStep, leaving the head at the has step (or hop)
    return cursor;
  }

  /** Renders the boundary alias's contributed WHERE clause to generic SQL text. */
  private static String renderBoundaryFilter(WalkerContext ctx) {
    SQLWhereClause clause = ctx.aliasFilters.get(BOUNDARY_ALIAS);
    assertThat(clause).as("a filter was contributed on the boundary alias").isNotNull();
    var sb = new StringBuilder();
    clause.getBaseExpression().toGenericStatement(sb);
    return sb.toString();
  }

  /**
   * A declining recogniser contributes nothing: the boundary class stays {@code V} (no re-type) and
   * no filter was added on the boundary alias. A decline discards the whole walk anyway, so this pins
   * the translate-all-then-contribute shape rather than a required rollback.
   */
  private static void assertContributedNothing(WalkerContext ctx) {
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .as("no re-type on decline — the boundary node stays rooted at V")
        .containsEntry(BOUNDARY_ALIAS, "V");
    assertThat(ctx.aliasFilters)
        .as("no filter contributed on decline")
        .doesNotContainKey(BOUNDARY_ALIAS);
  }
}
