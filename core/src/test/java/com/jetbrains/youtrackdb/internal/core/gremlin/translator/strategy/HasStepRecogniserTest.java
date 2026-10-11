package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Direction;
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
   * Multi-label {@code hasLabel} on the traversal root re-types the boundary to the least common
   * vertex ancestor of the labels (here {@code Person}, since {@code Employee} extends it) and adds
   * an exact {@code @class IN} leaf filter under non-polymorphic mode.
   */
  @Test
  public void hasLabelMultiLabelNonPolymorphic_onRoot_retypesToLcaAndClassIn() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    var admin = graph.traversal().V().hasLabel("Person", "Employee").asAdmin();
    var ctx = contextWithStartBoundary(false, session.getSchema());
    ctx.setAtTraversalStart(true);
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .as("non-polymorphic multi-label re-types to the LCA of the named classes")
        .containsEntry(BOUNDARY_ALIAS, "Person");
    assertThat(renderBoundaryFilter(ctx))
        .as("non-polymorphic multi-label keeps an exact @class IN leaf filter")
        .contains("@class IN");
  }

  /**
   * Polymorphic multi-label {@code hasLabel} on the root re-types to the LCA and contributes
   * {@code @class IN} over the polymorphic subclass closure (no leaf-exact {@code =}).
   */
  @Test
  public void hasLabelMultiLabelPolymorphic_onRoot_retypesToLcaAndClassIn() {
    var person = session.createVertexClass("Person");
    var employee = session.getSchema().createClass("Employee", person);
    session.getSchema().createClass("Manager", employee);
    var admin = graph.traversal().V().hasLabel("Person", "Employee").asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    ctx.setAtTraversalStart(true);
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .as("polymorphic multi-label re-types to the LCA of the named classes")
        .containsEntry(BOUNDARY_ALIAS, "Person");
    assertThat(renderBoundaryFilter(ctx)).contains("@class IN");
  }

  /**
   * After a hop the fold is closed; multi-label {@code hasLabel} contributes {@code @class IN} as a
   * filter on already-fetched neighbours.
   */
  @Test
  public void hasLabelMultiLabel_afterHop_contributesClassInFilter() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    session.createEdgeClass("knows");
    var admin = graph.traversal().V().out("knows").hasLabel("Person", "Employee").asAdmin();
    var ctx = contextWithStartBoundary(false, session.getSchema());
    ctx.setAtTraversalStart(false);
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take();
    cursor.take();

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).contains("@class IN");
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
   * {@code hasLabel("Missing")} on a class that does not exist declines to native. The class is
   * resolved at execution time by native, so it may be created later in the same open transaction
   * (DDL-in-tx) and native would then see the new rows; a translated plan compiled now against a
   * schema without the class bakes an empty/stale source and would diverge (translator-ON != OFF).
   * A class that never exists yields empty on native too, so declining preserves on==off either way.
   */
  @Test
  public void hasLabelNonExistentClass_declines() {
    var admin = graph.traversal().V().hasLabel("Missing").asAdmin();
    var ctx = contextWithStartBoundary(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a hasLabel on a non-existent class must decline")
        .isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /** A rejected widening preserves the earlier class, filters and label bindings in both modes. */
  @Test
  public void wideningClass_declinesWithoutChangingExistingContributions() {
    var user = session.createVertexClass("User");
    session.getSchema().createClass("Employee", user);
    for (var polymorphic : Set.of(true, false)) {
      var ctx = contextWithStartBoundary(polymorphic, session.getSchema());
      var first = graph.traversal().V().hasLabel("Employee").has("name", "e").asAdmin();
      assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(first), ctx))
          .isEqualTo(Outcome.ACCEPTED);
      var filter = renderBoundaryFilter(ctx);
      var widening = graph.traversal().V().hasLabel("User").as("rejected").asAdmin();
      assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(widening), ctx))
          .isEqualTo(Outcome.DECLINE);
      assertThat(ctx.boundaryClassName()).isEqualTo("Employee");
      assertThat(renderBoundaryFilter(ctx)).isEqualTo(filter);
      assertThat(ctx.userLabelToAlias).doesNotContainKey("rejected");
    }
  }

  /** Nested narrowing marks every enclosing capture as unsafe without changing the committed scan. */
  @Test
  public void nestedCapturedNarrowing_marksEnclosingBooleanFoldAsUnsafe() {
    var ctx = contextWithStartBoundary(true, null);
    var arm = new SubTraversalPredicateAdapter(ctx, Map.of());
    arm.addNode(BOUNDARY_ALIAS, "Employee");
    var middle = new SubTraversalPredicateAdapter(arm, Map.of());
    var child = new SubTraversalPredicateAdapter(middle, Map.of());

    child.addNode(BOUNDARY_ALIAS, "Manager");

    assertThat(child.changedCapturedClass()).isTrue();
    ConnectiveStepSupport.commitPureFilterChild(middle, child, BOUNDARY_ALIAS);
    ConnectiveStepSupport.commitPureFilterChild(arm, middle, BOUNDARY_ALIAS);
    assertThat(arm.changedCapturedClass()).isTrue();
    assertThat(ctx.boundaryClassName()).isEqualTo("V");
  }

  /** Committed classes and equal captured repeats do not represent a changed uncommitted class. */
  @Test
  public void committedClassAndEqualCapturedRepeats_doNotMarkClassChange() {
    var ctx = contextWithStartBoundary(true, null);
    ctx.addNode(BOUNDARY_ALIAS, "Employee");
    var arm = new SubTraversalPredicateAdapter(ctx, Map.of());
    arm.addNode(BOUNDARY_ALIAS, "Manager");
    var child = new SubTraversalPredicateAdapter(arm, Map.of());
    child.addNode(BOUNDARY_ALIAS, "Manager");

    assertThat(arm.changedCapturedClass()).isFalse();
    assertThat(child.changedCapturedClass()).isFalse();
  }

  /** A valid vertex label on an edge alias declines before changing classes or binding labels. */
  @Test
  public void vertexLabelOnEdgeAlias_declinesWithoutContributing() {
    for (var polymorphic : Set.of(true, false)) {
      var ctx = contextWithStartBoundary(polymorphic, session.getSchema());
      ctx.markEdgeAlias(BOUNDARY_ALIAS);
      var admin = graph.traversal().V().hasLabel("V").as("rejected").asAdmin();

      assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(admin), ctx))
          .isEqualTo(Outcome.DECLINE);
      assertContributedNothing(ctx);
      assertThat(ctx.userLabelToAlias).doesNotContainKey("rejected");
    }
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
  // Deferred ordered hop — stash neighbour has / flush into MATCH.
  // ---------------------------------------------------------------------------

  /**
   * With a pending ordered hop, {@code has("name", …)} stashes containers on the hop instead of
   * writing MATCH filters immediately — a following slice will apply them via
   * {@code HasContainer.test}.
   */
  @Test
  public void pendingOrderedHop_propertyHas_stashesContainers() {
    var admin = graph.traversal().V().has("name", P.eq("Alice")).asAdmin();
    var ctx = contextWithPendingOrderedHop(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = HasStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    var pending = ctx.pendingOrderedHop();
    assertThat(pending).isNotNull();
    assertThat(pending.hasContainers()).hasSize(1);
    assertThat(pending.hasContainers().getFirst().getKey()).isEqualTo("name");
    assertThat(ctx.aliasFilters)
        .as("deferred has must not write MATCH filters until flush")
        .doesNotContainKey(PENDING_TARGET_ALIAS);
  }

  /**
   * Deferred {@code hasLabel("Person")} is stashed the same way, and a later flush re-types the
   * neighbour alias in MATCH.
   */
  @Test
  public void pendingOrderedHop_hasLabel_stashesThenFlushWritesAliasFilter() {
    session.createVertexClass("Person");
    var admin = graph.traversal().V().hasLabel("Person").asAdmin();
    var ctx = contextWithPendingOrderedHop(false, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(ctx.pendingOrderedHop()).isNull();
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .containsEntry(PENDING_TARGET_ALIAS, "Person");
    assertThat(renderAliasFilter(ctx, PENDING_TARGET_ALIAS)).contains("@class IN [?]");
  }

  /**
   * Flush of a deferred property {@code has} contributes a MATCH WHERE on the synthetic neighbour
   * alias — the path used by {@code order().hop().has().values(...)} without a slice.
   */
  @Test
  public void pendingOrderedHop_propertyHas_flushContributesFilterOnTarget() {
    var admin = graph.traversal().V().has("name", P.eq("Alice")).asAdmin();
    var ctx = contextWithPendingOrderedHop(true, null);
    var cursor = cursorAfterStart(admin);

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(renderAliasFilter(ctx, PENDING_TARGET_ALIAS)).contains("name = ");
  }

  /**
   * Flush of deferred {@code hasId} marks the plan RID-bearing and writes {@code @rid IN} on the
   * neighbour alias.
   */
  @Test
  public void pendingOrderedHop_hasId_flushContributesRidIn() {
    var alice = graph.addVertex(T.label, "Person");
    graph.tx().commit();
    var admin = graph.traversal().V().hasId(alice.id()).asAdmin();
    var ctx = contextWithPendingOrderedHop(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(ctx.ridBearing()).isTrue();
    assertThat(renderAliasFilter(ctx, PENDING_TARGET_ALIAS))
        .containsIgnoringCase("@rid")
        .contains(" IN ");
  }

  /**
   * Multi-label deferred {@code hasLabel} under non-polymorphic mode flushes to {@code @class IN}
   * on the neighbour alias.
   */
  @Test
  public void pendingOrderedHop_multiHasLabel_nonPolymorphic_flushClassIn() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    var admin = graph.traversal().V().hasLabel("Person", "Employee").asAdmin();
    var ctx = contextWithPendingOrderedHop(false, session.getSchema());
    ctx.setAtTraversalStart(false);
    var cursor = cursorAfterStart(admin);

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(renderAliasFilter(ctx, PENDING_TARGET_ALIAS)).contains("@class IN");
  }

  /** Traversal-bearing {@code has(key, traversal)} declines while a hop is deferred. */
  @Test
  public void pendingOrderedHop_traversalHas_declines() {
    var admin = graph.traversal().V().has("name", __.out("knows")).asAdmin();
    var ctx = contextWithPendingOrderedHop(true, null);
    var cursor = cursorAfterStart(admin);

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.pendingOrderedHop().hasContainers()).isEmpty();
  }

  /**
   * {@code P.and} / {@code P.or} / {@code P.not} that embed a sub-traversal also decline — the
   * connective walk in {@code embedsTraversal} must see through wrappers.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void pendingOrderedHop_connectiveEmbeddingTraversal_declines() {
    HasStep<?> hasStep = mock(HasStep.class);
    P traversalPredicate =
        ((HasStep<?>) graph.traversal().V().has("name", __.out("knows")).asAdmin().getSteps()
            .get(1))
            .getHasContainers()
            .getFirst()
            .getPredicate();
    var andWithTrav = new org.apache.tinkerpop.gremlin.process.traversal.util.AndP(
        List.of(P.eq("a"), traversalPredicate));
    var orWithTrav = new org.apache.tinkerpop.gremlin.process.traversal.util.OrP(
        List.of(P.eq("a"), traversalPredicate));
    var notWithTrav = new org.apache.tinkerpop.gremlin.process.traversal.NotP(traversalPredicate);
    when(hasStep.getHasContainers())
        .thenReturn(
            List.of(new HasContainer("name", andWithTrav)),
            List.of(new HasContainer("name", orWithTrav)),
            List.of(new HasContainer("name", notWithTrav)));
    when(hasStep.getLabels()).thenReturn(Collections.emptySet());
    var ctx = contextWithPendingOrderedHop(true, null);

    for (var i = 0; i < 3; i++) {
      assertThat(HasStepRecogniser.collectDeferredHasContainers(hasStep, ctx))
          .as("connective %s embedding a traversal must decline", i)
          .isNull();
    }
  }

  /** A blank property key declines deferred collection. */
  @Test
  public void collectDeferredHasContainers_blankKey_returnsNull() {
    @SuppressWarnings("unchecked")
    HasStep<?> hasStep = mock(HasStep.class);
    when(hasStep.getHasContainers()).thenReturn(List.of(new HasContainer("  ", P.eq("x"))));
    var ctx = contextWithPendingOrderedHop(true, null);

    assertThat(HasStepRecogniser.collectDeferredHasContainers(hasStep, ctx)).isNull();
  }

  /** Empty container list is degenerate and declines deferred collection. */
  @Test
  public void collectDeferredHasContainers_empty_returnsNull() {
    @SuppressWarnings("unchecked")
    HasStep<?> hasStep = mock(HasStep.class);
    when(hasStep.getHasContainers()).thenReturn(List.of());
    var ctx = contextWithPendingOrderedHop(true, null);

    assertThat(HasStepRecogniser.collectDeferredHasContainers(hasStep, ctx)).isNull();
  }

  /** Reserved {@code @} keys (other than {@code ~label}/{@code ~id}) decline deferred collection. */
  @Test
  public void collectDeferredHasContainers_reservedAtKey_returnsNull() {
    @SuppressWarnings("unchecked")
    HasStep<?> hasStep = mock(HasStep.class);
    when(hasStep.getHasContainers()).thenReturn(List.of(new HasContainer("@class", P.eq("X"))));
    var ctx = contextWithPendingOrderedHop(true, null);

    assertThat(HasStepRecogniser.collectDeferredHasContainers(hasStep, ctx)).isNull();
  }

  /** Deferred missing label remains a predicate on V, never an invalid MATCH class source. */
  @Test
  public void pendingOrderedHop_hasLabelMissingClass_filtersWithoutRetyping() {
    var admin = graph.traversal().V().hasLabel("Missing").asAdmin();
    var ctx = contextWithPendingOrderedHop(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(renderAliasFilter(ctx, PENDING_TARGET_ALIAS)).contains("@class IN [?]");
    assertThat(ctx.patternBuilder.build().aliasClasses()).containsEntry(PENDING_TARGET_ALIAS, "V");
  }

  /** Separate label containers in one deferred HasStep combine by native OR. */
  @Test
  public void pendingOrderedHop_conflictingHasLabels_combineByOr() {
    session.createVertexClass("Person");
    session.createVertexClass("Software");
    @SuppressWarnings("unchecked")
    HasStep<?> hasStep = mock(HasStep.class);
    when(hasStep.getHasContainers())
        .thenReturn(
            List.of(
                new HasContainer(T.label.getAccessor(), P.eq("Person")),
                new HasContainer(T.label.getAccessor(), P.eq("Software"))));
    when(hasStep.getLabels()).thenReturn(Collections.emptySet());
    var ctx = contextWithPendingOrderedHop(true, session.getSchema());

    assertThat(HasStepRecogniser.collectDeferredHasContainers(hasStep, ctx)).hasSize(2);
  }

  /**
   * Multi-label deferred {@code hasLabel} under polymorphic mode stays eligible on both routes;
   * flush includes subclasses in the MATCH class gate.
   */
  @Test
  public void pendingOrderedHop_multiHasLabelPolymorphic_acceptsAndFlushesClosure() {
    var person = session.createVertexClass("Person");
    var employee = session.getSchema().createClass("Employee", person);
    session.getSchema().createClass("Manager", employee);
    var admin = graph.traversal().V().hasLabel("Person", "Employee").asAdmin();
    var ctx = contextWithPendingOrderedHop(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.pendingOrderedHop().hasContainers()).hasSize(1);
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(renderAliasFilter(ctx, PENDING_TARGET_ALIAS))
        .contains("@class IN [?, ?, ?]");
  }

  /**
   * A user label already bound to another alias on the deferred {@code has} declines without
   * stashing containers.
   */
  @Test
  public void pendingOrderedHop_userLabelAlreadyBound_declines() {
    var priorlyLabelled = graph.traversal().V().as("a").asAdmin().getStartStep();
    var admin = graph.traversal().V().has("name", "Alice").as("a").asAdmin();
    var ctx = contextWithPendingOrderedHop(true, null);
    assertThat(ctx.bindStepLabels(priorlyLabelled, BOUNDARY_ALIAS)).isTrue();
    var cursor = cursorAfterStart(admin);

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.pendingOrderedHop().hasContainers()).isEmpty();
  }

  /**
   * Flush fails when a stashed container cannot become a MATCH filter (unconvertible {@code hasId}),
   * which is the walker's decline channel after a non-slice follower.
   */
  @Test
  public void pendingOrderedHop_unconvertibleHasId_flushFails() {
    @SuppressWarnings("unchecked")
    HasStep<?> hasStep = mock(HasStep.class);
    when(hasStep.getHasContainers())
        .thenReturn(List.of(new HasContainer(T.id.getAccessor(), P.eq("not-a-rid"))));
    when(hasStep.getLabels()).thenReturn(Collections.emptySet());
    var ctx = contextWithPendingOrderedHop(true, session.getSchema());
    // Stash bypasses collectDeferred's hasId convertibility check — collect only validates shape.
    var containers = HasStepRecogniser.collectDeferredHasContainers(hasStep, ctx);
    assertThat(containers).isNotNull();
    var contribution = HasStepRecogniser.prepareDeferred(
        ctx, containers, WalkerContext.VERTEX_ROOT_CLASS, 0);
    assertThat(contribution).isNotNull();
    ctx.setPendingOrderedHop(ctx.pendingOrderedHop().appendHasStep(contribution, true));

    assertThat(ctx.flushPendingOrderedHop()).isFalse();
  }

  /** A bare deferred hop has no has contributions and flushes without a predicate. */
  @Test
  public void pendingOrderedHop_bareFlush_hasNoContributions() {
    var ctx = contextWithPendingOrderedHop(true, null);
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(ctx.aliasFilters).doesNotContainKey(PENDING_TARGET_ALIAS);
  }

  /** A deferred unlabeled singleton comparison uses the class established by an earlier HasStep. */
  @Test
  public void pendingOrderedHop_laterUnlabeledPredicateUsesEffectiveClassWithoutEarlyBinding() {
    var person = session.createVertexClass("StepOnePerson");
    person.createProperty("name", PropertyType.STRING);
    var ctx = contextWithPendingOrderedHop(true, session.getSchema());
    ctx.setAtTraversalStart(false);
    var label = graph.traversal().V().hasLabel("StepOnePerson").asAdmin();
    var singleton = graph.traversal().V().has("name", P.eq(List.of("Alice"))).asAdmin();

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(label), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.pendingOrderedHop().effectiveTargetClass()).isEqualTo("StepOnePerson");
    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(singleton), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.inputParameters).as("the op route cannot allocate MATCH parameters").isEmpty();
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(renderAliasFilter(ctx, PENDING_TARGET_ALIAS))
        .containsIgnoringCase("is defined")
        .containsIgnoringCase("is not defined")
        .contains("@class IN");
    assertThat(ctx.inputParameters).as("the declared singleton emits no SQL binding").isEmpty();
  }

  /** Separate deferred label groups keep their AND filters and narrow to the more specific class. */
  @Test
  public void pendingOrderedHop_multipleHasStepsRetypeToNarrowerClass() {
    var parent = session.createVertexClass("StepOneParent");
    session.getSchema().createClass("StepOneChild", parent);
    var ctx = contextWithPendingOrderedHop(true, session.getSchema());
    var first = graph.traversal().V().hasLabel("StepOneParent").asAdmin();
    var second = graph.traversal().V().hasLabel("StepOneChild").asAdmin();

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(first), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(second), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.pendingOrderedHop().contributions()).hasSize(2);
    assertThat(ctx.pendingOrderedHop().effectiveTargetClass()).isEqualTo("StepOneChild");
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .containsEntry(PENDING_TARGET_ALIAS, "StepOneChild");
    assertThat(renderAliasFilter(ctx, PENDING_TARGET_ALIAS).split("@class IN", -1))
        .as("both separate label predicates survive the flush").hasSize(3);
  }

  /** A known sibling label group re-types to its LCA, while a missing alternative keeps V. */
  @Test
  public void pendingOrderedHop_labelLcaAndMissingAlternativeKeepSafeTargetClass() {
    var parent = session.createVertexClass("StepOneTree");
    session.getSchema().createClass("StepOneLeft", parent);
    session.getSchema().createClass("StepOneRight", parent);
    var ctx = contextWithPendingOrderedHop(true, session.getSchema());
    var siblings = graph.traversal().V().hasLabel("StepOneLeft", "StepOneRight").asAdmin();

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(siblings), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.pendingOrderedHop().effectiveTargetClass()).isEqualTo("StepOneTree");
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(ctx.patternBuilder.build().aliasClasses())
        .containsEntry(PENDING_TARGET_ALIAS, "StepOneTree");

    var missing = contextWithPendingOrderedHop(true, session.getSchema());
    var mixed = graph.traversal().V().hasLabel("StepOneLeft", "StepOneAbsent").asAdmin();
    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(mixed), missing))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(missing.pendingOrderedHop().effectiveTargetClass()).isEqualTo("V");
    assertThat(missing.flushPendingOrderedHop()).isTrue();
    assertThat(missing.patternBuilder.build().aliasClasses())
        .containsEntry(PENDING_TARGET_ALIAS, "V");
    assertThat(renderAliasFilter(missing, PENDING_TARGET_ALIAS)).contains("@class IN");
  }

  /** Two deferred property predicates bind once each, in recognition order, only on MATCH flush. */
  @Test
  public void pendingOrderedHop_flushBindsEachPreparedPredicateOnce() {
    var ctx = contextWithPendingOrderedHop(true, null);
    var first = graph.traversal().V().has("name", "Alice").asAdmin();
    var second = graph.traversal().V().has("age", P.gt(25)).asAdmin();
    ctx.setAtTraversalStart(false);

    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(first), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(second), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.inputParameters).isEmpty();
    assertThat(ctx.flushPendingOrderedHop()).isTrue();
    assertThat(ctx.inputParameters).containsExactly(
        org.assertj.core.api.Assertions.entry(0, "Alice"),
        org.assertj.core.api.Assertions.entry(1, 25));
  }

  /**
   * SQL-derived slots omit a declared singleton collection. The ordered filter retains its
   * original collection predicate while rebuilding safe nested and regex operands.
   */
  @Test
  public void deferredNativeOperands_rebuildSafePredicatesAndKeepCollectionsInOrder() {
    var containers = List.of(
        new HasContainer("name", TextP.regex("^al").or(TextP.notRegex("ice$"))),
        new HasContainer("tags", P.eq(new java.util.ArrayList<>(List.of("red")))),
        new HasContainer("age", P.gt(10).and(P.not(P.lt(20)))));
    var original = NativeHasOperands.capture(containers);
    var rebuilt = original.rebuild();
    var another = original.rebuild();

    assertThat(rebuilt).extracting(HasContainer::getKey)
        .containsExactly("name", "tags", "age");
    for (int index = 0; index < containers.size(); index++) {
      if (index == 1) {
        assertThat(rebuilt.get(index).getPredicate())
            .isSameAs(containers.get(index).getPredicate());
      } else {
        assertThat(rebuilt.get(index).getPredicate())
            .isNotSameAs(containers.get(index).getPredicate());
      }
      assertThat(rebuilt.get(index).getPredicate().toString())
          .isEqualTo(containers.get(index).getPredicate().toString());
      if (index != 1) {
        assertThat(another.get(index).getPredicate())
            .isNotSameAs(rebuilt.get(index).getPredicate());
      }
    }
    assertThat(((P<String>) rebuilt.getFirst().getPredicate()).test("alice")).isTrue();
    assertThat(((P<String>) rebuilt.getFirst().getPredicate()).test("bob")).isTrue();
    assertThat(rebuilt.get(1).getPredicate().getValue()).isEqualTo(List.of("red"));
  }

  /** Unsafe native predicate objects keep their test method and operand collection type. */
  @Test
  public void nativeOperands_keepUnsupportedPredicateAndCollectionIdentity() {
    var custom = new P<Object>(org.apache.tinkerpop.gremlin.process.traversal.Compare.eq,
        "absent") {
      @Override
      public boolean test(Object candidate) {
        return true;
      }
    };
    var deque = new java.util.ArrayDeque<>(List.of("red", "blue"));
    var dequePredicate = P.eq(deque);
    assertThat(NativeHasOperands.cacheable(custom)).isFalse();
    assertThat(NativeHasOperands.cacheable(dequePredicate)).isFalse();
    var captured = NativeHasOperands.capture(List.of(new HasContainer("name", custom),
        new HasContainer("tags", dequePredicate)));
    assertThat(captured.rebuild().getFirst().getPredicate()).isSameAs(custom);
    assertThat(captured.rebuild().get(1).getPredicate()).isSameAs(dequePredicate);
    assertThat(captured.rebuild().get(1).getPredicate().getValue())
        .isEqualTo(dequePredicate.getValue());
  }

  /**
   * Position and slot role must agree even when a pre-hop prefix, two deferred filters separated
   * by a barrier, and a later MATCH filter each use different binding layouts.
   */
  @Test
  public void extractionAndWalk_matchBindingsAndRolesAcrossDeferredFlush() {
    var person = session.createVertexClass("BindingPerson");
    person.createProperty("name", PropertyType.STRING);
    person.createProperty("age", PropertyType.INTEGER);
    session.createEdgeClass("bindingEdge");
    var traversal = graph.traversal().V().hasLabel("BindingPerson")
        .has("name", TextP.startingWith("al"))
        .order().by("name").out("bindingEdge")
        .has("name", TextP.startingWith(""))
        .barrier(3)
        .has("age", P.within(21, 22))
        .out("bindingEdge")
        .has("name", P.not(TextP.regex("^bad")))
        .asAdmin();
    assertExtractedBindingsAndRoles(traversal);
    var bindings = GremlinStepWalker.extractShape(traversal, session).bindings();
    assertThat(bindings.get(0)).isEqualTo("al");
    assertThat(bindings.get(1)).isEqualTo("am");
    assertThat(bindings.get(2)).isEqualTo("");
    assertThat(bindings.get(3)).isEqualTo(21);
    assertThat(bindings.get(4)).isEqualTo(22);
    assertThat(bindings.get(5)).isEqualTo("^bad");
  }

  /**
   * Type gates use the effective boundary even without a same-step label. Each edge filter uses
   * the edge class rather than the adjacent vertex's class. Null and maximum-code-point prefixes
   * have no derived upper slot.
   */
  @Test
  public void extractionAndWalk_useKnownMultiLabelAndEdgeGates() {
    var parent = session.createVertexClass("BindingParent");
    parent.createProperty("name", PropertyType.STRING);
    var child = session.getSchema().createClass("BindingChild", parent);
    child.createProperty("age", PropertyType.INTEGER);
    var edge = session.createEdgeClass("bindingEdgeGate");
    edge.createProperty("name", PropertyType.STRING);
    assertExtractedBindingsAndRoles(graph.traversal().V()
        .hasLabel("BindingParent", "BindingChild")
        .has("name", TextP.startingWith("ab"))
        .outE("bindingEdgeGate")
        .has("name", TextP.startingWith("\uDBFF\uDFFF"))
        .inV().has("age", P.eq(null)).asAdmin());
    assertExtractedBindingsAndRoles(graph.traversal().V()
        .hasLabel("BindingParent").barrier(2)
        .has("name", P.without("ab", "cd"))
        .has("age", P.gt(3).and(P.lt(8)).or(P.neq(4)))
        .asAdmin());
  }

  /**
   * Deferred contributions retain original operands in HasStep order, even if a schema-known
   * singleton emits no SQL parameter. Its collection predicate stays on the fresh native path.
   */
  @Test
  public void pendingOrderedHop_capturesNativeOperandsWithoutSqlSlots() {
    var person = session.createVertexClass("OperandPerson");
    person.createProperty("name", PropertyType.STRING);
    var ctx = contextWithPendingOrderedHop(true, session.getSchema());
    ctx.setAtTraversalStart(false);
    var first = graph.traversal().V().hasLabel("OperandPerson").asAdmin();
    var second = graph.traversal().V()
        .has("name", P.eq(new java.util.ArrayList<>(List.of("alice")))).asAdmin();
    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(first), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(HasStepRecogniser.INSTANCE.recognize(cursorAfterStart(second), ctx))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.pendingOrderedHop().contributions()).hasSize(2);
    var contribution = ctx.pendingOrderedHop().contributions().get(1);
    assertThat(contribution.deferredBindings()).isEmpty();
    assertThat(contribution.nativeOperands().rebuild().getFirst().getPredicate().getValue())
        .isEqualTo(List.of("alice"));
    assertThat(contribution.nativeOperands().rebuild().getFirst().getPredicate())
        .isSameAs(contribution.nativeContainers().getFirst().getPredicate());
    assertThat(ctx.pendingOrderedHop().effectiveTargetClass()).isEqualTo("OperandPerson");
  }

  /** Source-slice filters also inherit a prior neighbour label's declared-property gate. */
  @Test
  public void extractionAndWalk_alignSourceSliceOpGateWithoutLocalLabel() {
    var person = session.createVertexClass("SourceSlicePerson");
    person.createProperty("name", PropertyType.STRING);
    session.createEdgeClass("sourceSliceEdge");
    var traversal = graph.traversal().V().order().by("name").limit(2)
        .out("sourceSliceEdge")
        .hasLabel("SourceSlicePerson").barrier(3)
        .has("name", TextP.startingWith("ab"))
        .asAdmin();
    assertExtractedBindingsAndRoles(traversal);
    var extraction = GremlinStepWalker.extractShape(traversal, session);
    assertThat(extraction.hasContributions()).extracting(c -> c.context().destination())
        .containsExactly(HasBindingContext.Destination.ORDERED_FILTER,
            HasBindingContext.Destination.ORDERED_FILTER);
    assertThat(extraction.bindings()).isEmpty();
    assertThat(extraction.nativeOperands().get(1).rebuild().getFirst().getPredicate().getValue())
        .isEqualTo("ab");
    assertThat(extraction.hasContributions().get(1).slots())
        .extracting(HasBindingContext.Slot::role)
        .containsExactly(GremlinPredicateAdapter.SlotRole.PREFIX,
            GremlinPredicateAdapter.SlotRole.DERIVED_UPPER_BOUND);
  }

  /** Child filters inherit a typed boundary, and a later single label can re-type it. */
  @Test
  public void extractionAndWalk_alignChildGateAndSingleLabelRetyping() {
    var first = session.createVertexClass("BindingChildFirst");
    first.createProperty("name", PropertyType.STRING);
    session.getSchema().createClass("BindingChildSecond", first);
    assertExtractedBindingsAndRoles(graph.traversal().V().hasLabel("BindingChildFirst")
        .where(org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__
            .has("name", TextP.startingWith("al")))
        .asAdmin());
    var retyped = graph.traversal().V().hasLabel("BindingChildFirst")
        .barrier(2).hasLabel("BindingChildSecond").where(
            org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__
                .has("name", TextP.startingWith("al")))
        .asAdmin();
    var extraction = GremlinStepWalker.extractShape(retyped, session);
    assertThat(extraction.hasContributions().getLast().context().gateClasses())
        .containsExactly("BindingChildSecond");
    var context = new WalkerContext(true, false, session.getSchema());
    assertThat(HasStepRecogniser.narrowedClass(context, List.of("BindingChildSecond"),
        "BindingChildFirst", true, false)).isEqualTo("BindingChildSecond");
    assertExtractedBindingsAndRoles(retyped);
  }

  /** One HasStep's label wins over the captured gate after a hop, without folding its prefix. */
  @Test
  public void capturedHop_sameStepLabelOverridesEnclosingGate() {
    var parent = session.createVertexClass("CapturedGateParent");
    session.getSchema().createClass("CapturedGateChild", parent)
        .createProperty("name", PropertyType.STRING);
    var traversal = graph.traversal().V().hasLabel("CapturedGateParent")
        .not(__.out("capturedGateEdge")).asAdmin();
    var notStep = (org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep<?>) traversal
        .getSteps().getLast();
    var child = notStep.getLocalChildren().getFirst();
    child.addStep(new HasStep<>(child,
        new HasContainer(T.label.getAccessor(), P.eq("CapturedGateChild")),
        new HasContainer("name", TextP.startingWith("al"))));

    assertExtractedBindingsAndRoles(traversal);
    var last = GremlinStepWalker.extractShape(traversal, session).hasContributions().getLast();
    assertThat(last.context().gateClasses()).containsExactly("CapturedGateChild");
    assertThat(last.context().folded()).isFalse();
    assertThat(last.slots()).extracting(HasBindingContext.Slot::role)
        .containsExactly(GremlinPredicateAdapter.SlotRole.PREFIX,
            GremlinPredicateAdapter.SlotRole.DERIVED_UPPER_BOUND);
  }

  /** MATCH re-types incompatible singles and root LCAs; deferred labels only narrow an alias. */
  @Test
  public void narrowedClass_distinguishesMatchFromDeferredRoute() {
    var parent = session.createVertexClass("RouteParent");
    var sub = session.getSchema().createClass("RouteSub", parent);
    session.getSchema().createClass("RouteA", sub);
    session.getSchema().createClass("RouteB", sub);
    var ctx = new WalkerContext(true, false, session.getSchema());
    assertThat(HasStepRecogniser.narrowedClass(ctx, List.of("RouteA", "RouteB"),
        "V", false, false)).isEqualTo("RouteSub");
    assertThat(HasStepRecogniser.narrowedClass(ctx, List.of("RouteA", "RouteB"),
        "RouteParent", false, false)).isNull();
    assertThat(HasStepRecogniser.narrowedClass(ctx, List.of("RouteB"),
        "RouteA", true, false)).isEqualTo("RouteB");
    assertThat(HasStepRecogniser.narrowedClass(ctx, List.of("RouteA", "RouteB"),
        "V", false, true)).isEqualTo("RouteSub");
    assertThat(HasStepRecogniser.narrowedClass(ctx, List.of("RouteA", "RouteB"),
        "RouteA", false, true)).isNull();
    assertThat(HasStepRecogniser.narrowedClass(ctx, List.of("RouteB"),
        "RouteA", false, true)).isNull();
  }

  /** Only one nonblank string eq label qualifies, even when property containers surround it. */
  @Test
  public void singleEqualityLabel_excludesMembershipAndMultipleContainers() {
    var label = new HasContainer(T.label.getAccessor(), P.eq("ReadPerson"));
    assertThat(HasStepRecogniser.singleEqualityLabel(List.of(
        new HasContainer("age", P.eq(7)), label, new HasContainer("name", P.eq("al")))))
        .isEqualTo("ReadPerson");
    for (var predicate : List.of(P.within("ReadPerson"), P.within("ReadPerson", "Other"),
        P.neq("ReadPerson"), P.eq(" "), P.eq(7))) {
      assertThat(HasStepRecogniser.singleEqualityLabel(List.of(
          new HasContainer(T.label.getAccessor(), predicate)))).isNull();
    }
    assertThat(HasStepRecogniser.singleEqualityLabel(List.of(label, label))).isNull();
    assertThat(HasStepRecogniser.singleEqualityLabel(List.of(
        new HasContainer("age", P.eq(7))))).isNull();
    assertThat(HasStepRecogniser.singleEqualityLabel(List.of())).isNull();
  }

  /**
   * Real extraction of ordinary equality and scalar predicates performs no class lookup. Each
   * excluded spelling still resolves the named class through the narrowing fallback.
   */
  @Test
  public void ordinaryEqualityExtraction_skipsSchemaReadsButFallbackStillResolvesClass() {
    var person = session.createVertexClass("ReadPerson");
    var schema = mock(Schema.class);
    when(schema.getClass("ReadPerson")).thenReturn(person);
    var extractionSession = mock(DatabaseSessionEmbedded.class);
    when(extractionSession.getSchema()).thenReturn(schema);
    var ordinary = graph.traversal().V().hasLabel("ReadPerson").has("age", 7)
        .barrier(2).has("age", 8).asAdmin();
    var extraction = GremlinStepWalker.extractShape(ordinary, extractionSession);
    verify(schema, never()).getClass(anyString());
    assertThat(extraction.complete()).isTrue();
    assertThat(extraction.bindings()).containsEntry(0, 7).containsEntry(1, 8);
    assertThat(extraction.hasContributions()).hasSize(2).allSatisfy(c -> {
      assertThat(c.context().destination()).isEqualTo(HasBindingContext.Destination.MATCH_VERTEX);
      assertThat(c.context().gateClasses()).containsExactly("ReadPerson");
    });
    assertThat(extraction.hasContributions().getFirst().context().folded()).isTrue();
    assertThat(extraction.hasContributions().getLast().context().folded()).isFalse();

    Traversal.Admin<?, ?> duplicate = graph.traversal().V().hasLabel("ReadPerson").asAdmin();
    ((HasStep<?>) duplicate.getEndStep()).addHasContainer(
        new HasContainer(T.label.getAccessor(), P.eq("ReadPerson")));
    List<Traversal.Admin<?, ?>> fallbacks = List.of(
        graph.traversal().V().has(T.label, P.within("ReadPerson")).asAdmin(),
        graph.traversal().V().hasLabel("ReadPerson", "Other").asAdmin(), duplicate,
        graph.traversal().V().barrier(2).hasLabel("ReadPerson").asAdmin(),
        graph.traversal().E().hasLabel("ReadPerson").asAdmin(),
        graph.traversal().V().out("readEdge").hasLabel("ReadPerson").asAdmin(),
        graph.traversal().V().order().by("age").out("readEdge")
            .hasLabel("ReadPerson").limit(2).asAdmin(),
        graph.traversal().V().where(__.hasLabel("ReadPerson")).asAdmin());
    for (var fallback : fallbacks) {
      clearInvocations(schema);
      GremlinStepWalker.extractShape(fallback, extractionSession);
      verify(schema, atLeastOnce()).getClass("ReadPerson");
    }
  }

  /** A provisional label carries the declared STRING gate across a barrier without losing slots. */
  @Test
  public void ordinaryEqualityExtraction_preservesLaterPrefixGateAndBindings() {
    session.createVertexClass("ReadPerson").createProperty("name", PropertyType.STRING);
    var traversal = graph.traversal().V().hasLabel("ReadPerson").has("age", 7)
        .barrier(2).has("name", TextP.startingWith("al")).asAdmin();
    assertExtractedBindingsAndRoles(traversal);
    var extraction = GremlinStepWalker.extractShape(traversal, session);
    var later = extraction.hasContributions().getLast();
    assertThat(later.context()).isEqualTo(new HasBindingContext(
        HasBindingContext.Destination.MATCH_VERTEX, List.of("ReadPerson"), false));
    assertThat(later.slots()).extracting(HasBindingContext.Slot::role)
        .containsExactly(GremlinPredicateAdapter.SlotRole.PREFIX,
            GremlinPredicateAdapter.SlotRole.DERIVED_UPPER_BOUND);
    assertThat(extraction.bindings()).containsEntry(0, 7).containsEntry(1, "al")
        .containsEntry(2, "am");
  }

  private void assertExtractedBindingsAndRoles(Traversal.Admin<?, ?> traversal) {
    var extraction = GremlinStepWalker.extractShape(traversal, session);
    var translated = GremlinStepWalker.production().walk(traversal);
    assertThat(translated).as("this shape stays translated").isNotNull();
    assertThat(extraction.hasContributions()).as("destination, gate, fold and role by HasStep")
        .isEqualTo(translated.hasContributions());
    // Ordered filters keep native operands instead of committing their provisional MATCH slots.
    // Step 3 binds those op operands at splice. On a MATCH-only route, every slot is committed.
    if (extraction.hasContributions().stream().noneMatch(
        c -> c.context().destination() == HasBindingContext.Destination.ORDERED_FILTER)) {
      assertThat(extraction.bindings()).hasSameSizeAs(translated.inputParameters());
    }
    for (int slot = 0; slot < translated.inputParameters().size(); slot++) {
      assertThat(extraction.bindings().get(slot)).as("binding at slot %s", slot)
          .isEqualTo(translated.inputParameters().get(slot));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  private static final String PENDING_TARGET_ALIAS = "$g2m_anon_0";

  /**
   * Context with a deferred ordered hop already parked (as after {@code order().out()} before a
   * following {@code has} / slice).
   */
  private WalkerContext contextWithPendingOrderedHop(boolean polymorphic, Schema schema) {
    var ctx = contextWithStartBoundary(polymorphic, schema);
    ctx.setOrderBy(
        MatchProjectionBuilder.orderBy(
            List.of(
                ProjectionExpressionFactories.orderByProperty(BOUNDARY_ALIAS, "name", true))));
    ctx.recordOrderByCapture(BOUNDARY_ALIAS, false);
    ctx.setPendingOrderedHop(
        new PendingOrderedHop(
            Direction.OUT, new String[] {"knows"}, BOUNDARY_ALIAS, PENDING_TARGET_ALIAS,
            List.of()));
    ctx.pinBoundary(PENDING_TARGET_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    return ctx;
  }

  private static String renderAliasFilter(WalkerContext ctx, String alias) {
    SQLWhereClause clause = ctx.aliasFilters.get(alias);
    assertThat(clause).as("a filter was contributed on " + alias).isNotNull();
    var sb = new StringBuilder();
    clause.getBaseExpression().toGenericStatement(sb);
    return sb.toString();
  }

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
