package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.InlineFilterStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

public class RuntimeRidStartEligibilityTest {

  private static final RecordId A = new RecordId(25, 7);
  private static final RecordId B = new RecordId(26, 8);

  // All three spellings allow out/in/both with zero or one structural hop label.
  @Test
  public void eligibleSpellingsAndDirectionsKeepExactRidOwner() {
    for (var traversal : List.of(__.V(A).out(), __.V(A).in("a"), __.V(A).both("a"),
        __.V().hasId(A).out("a"), __.V().hasId(P.eq(A)).in(),
        __.V().hasId(P.eq(A)).both())) {
      var admin = postStrategy(traversal);
      var result = RuntimeRidStartEligibility.evaluate(admin, true);
      assertThat(result).isNotNull();
      assertThat(result.rid()).isEqualTo(A);
      assertThat(result.startStep()).isSameAs(admin.getStartStep());
      if (((GraphStep<?, ?>) admin.getStartStep()).getIds().length == 1) {
        assertThat(result.idContainer()).isNull();
      } else {
        assertThat(result.idContainer()).isSameAs(idContainer(admin));
      }
    }
  }

  // InlineFilter merges filters in either order. The exact ID container still owns RID slot zero.
  @Test
  public void mergedClassAndIdContainersAndAttachedLabelsRemainEligible() {
    for (var traversal : List.of(__.V(A).hasLabel("Person").out("a"),
        __.V().hasId(A).hasLabel("Person").out("a"),
        __.V().hasLabel("Person").hasId(A).out("a"),
        __.V(A).as("s").hasLabel("Person").as("c").out("a").as("t"))) {
      var admin = postStrategy(traversal);
      assertThat(admin.getSteps()).hasSize(3);
      var result = RuntimeRidStartEligibility.evaluate(admin, true);
      assertThat(result).isNotNull();
      assertThat(result.rid()).isEqualTo(A);
      assertThat(result.startStep()).isSameAs(admin.getStartStep());
      if (((GraphStep<?, ?>) admin.getStartStep()).getIds().length == 1) {
        assertThat(result.idContainer()).isNull();
      } else {
        assertThat(((HasStep<?>) admin.getSteps().get(1)).getHasContainers()).hasSize(2);
        assertThat(result.idContainer()).isSameAs(idContainer(admin));
      }
    }
  }

  // A post-strategy traversal with separate ID and class filters qualifies in either order.
  @Test
  public void separateClassAndIdStepsKeepFourStepShapeAndExactRidOwner() {
    for (boolean classFirst : List.of(false, true)) {
      var admin = __.V().out().asAdmin();
      var id = new HasContainer(T.id.getAccessor(), P.eq(A));
      var label = new HasContainer(T.label.getAccessor(), P.eq("Person"));
      // The DSL merges adjacent filters during construction. Insert distinct steps directly
      // and apply an empty strategy set to model strategies that leave these filters separate.
      admin.addStep(1, new HasStep<>(admin, classFirst ? label : id));
      admin.addStep(2, new HasStep<>(admin, classFirst ? id : label));
      admin.setStrategies(new DefaultTraversalStrategies());
      admin.applyStrategies();
      assertThat(admin.isLocked()).isTrue();
      assertThat(admin.getSteps()).hasSize(4);
      assertThat(admin.getSteps().get(1)).isExactlyInstanceOf(HasStep.class);
      assertThat(admin.getSteps().get(2)).isExactlyInstanceOf(HasStep.class);
      assertThat(((HasStep<?>) admin.getSteps().get(1)).getHasContainers())
          .containsExactly(classFirst ? label : id);
      assertThat(((HasStep<?>) admin.getSteps().get(2)).getHasContainers())
          .containsExactly(classFirst ? id : label);
      var result = RuntimeRidStartEligibility.evaluate(admin, true);
      assertThat(result).isNotNull();
      assertThat(result.rid()).isEqualTo(A);
      assertThat(result.startStep()).isSameAs(admin.getStartStep());
      assertThat(result.idContainer()).isSameAs(id);
    }
  }

  // A context-free contextual RID qualifies in both spellings without copying its metadata.
  @Test
  public void contextFreeContextualRidsProducePlainImmutableSnapshotsForBothSpellings() {
    for (boolean startIds : List.of(true, false)) {
      var contextual = new ContextualRecordId("#25:7");
      assertThat(contextual.getContext()).isNull();
      var admin = postStrategy(
          startIds ? __.V(contextual).out() : __.V().hasId(contextual).out());
      var result = RuntimeRidStartEligibility.evaluate(admin, true);
      assertThat(result).isNotNull();
      assertThat(result.rid()).isExactlyInstanceOf(RecordId.class).isEqualTo(A);
      assertThat(result.startStep()).isSameAs(admin.getStartStep());
      if (startIds) {
        assertThat(result.idContainer()).isNull();
      } else {
        assertThat(result.idContainer()).isSameAs(idContainer(admin));
      }
    }
  }

  // A filter child hoisted by the real InlineFilter strategy is judged by its resulting root shape.
  @Test
  public void hoistedIdFilterUsesPostStrategyProvenance() {
    var admin = postStrategy(__.V().filter(__.hasId(A)).out("a"));
    assertThat(admin.getSteps()).hasSize(3);
    assertThat(admin.getSteps().get(1)).isInstanceOf(HasStep.class);
    assertThat(RuntimeRidStartEligibility.evaluate(admin, true).idContainer())
        .isSameAs(idContainer(admin));
  }

  // Only one equality RID qualifies. Single-value within and collection-valued eq stay literal.
  @Test
  public void multipleIdsWithinAndCollectionEqualityAreIneligible() {
    reject(__.V(A, B).out(), __.V(A, A).out(), __.V().hasId(A, B).out(),
        __.V().hasId(P.within(A)).out(), __.V().has(T.id, List.of(A)).out());
  }

  // Persistence applies to both spellings and to immutable or changeable temporary identities.
  @Test
  public void temporaryAndUndefinedRidsAreIneligible() {
    for (var rid : List.of(new RecordId(25, -2), new RecordId(-1, 7),
        new ChangeableRecordId(25, -2), new ChangeableRecordId())) {
      reject(__.V(rid).out(), __.V().hasId(rid).out());
    }
  }

  // Two start constraints or an ID on the reached vertex must not be mistaken for one start RID.
  @Test
  public void duplicateAndAfterHopRidConstraintsAreIneligible() {
    reject(__.V(A).hasId(A).out(), __.V().hasId(A).hasId(B).out(),
        __.V(A).out().hasId(B), __.V().out().hasId(A));
  }

  // Multiple labels, repeated class constraints and non-equality or invalid labels widen the grammar.
  @Test
  public void multipleDuplicateAndInvalidClassConstraintsAreIneligible() {
    reject(__.V(A).hasLabel("Person", "Other").out(),
        __.V(A).hasLabel("Person").hasLabel("Person").out(),
        __.V(A).hasLabel("Person").hasLabel("Other").out(),
        __.V(A).has(T.label, P.within("Person")).out(),
        __.V(A).has(T.label, " ").out(), __.V(A).has(T.label, 3).out());
  }

  // Extra steps are rejected even when the ordinary translator skips or recognises them.
  @Test
  public void barriersNotUnionAndSecondHopAreIneligible() {
    reject(__.V(A).barrier().out(), __.V(A).out().barrier(),
        __.V(A).not(__.out("b")).out(), __.V(A).union(__.out(), __.in()),
        __.V(A).out().out(), __.V(A).identity().as("extra").out());
  }

  // Bare lookups, projections, target/start property filters, edge hops and multi-label hops stay out.
  @Test
  public void missingHopAndOtherFiltersOrOutputsAreIneligible() {
    reject(__.V(A), __.V().hasId(A), __.V().out(), __.V(A).values("name"),
        __.V(A).has("name", "a").out(), __.V(A).out().has("name", "a"),
        __.V(A).outE(), __.V(A).out("a", "b"), __.E(A).outV());
  }

  // Real map and predicate children are not root traversals. A synthesized root also needs rootWalk.
  @Test
  public void childTraversalsAndSyntheticNonRootWalksAreIneligible() {
    for (var outer : List.of(__.V().map(__.V(A).out()),
        __.V().not(__.V(A).out()), __.V().union(__.V(A).out()))) {
      var parent = (TraversalParent) outer.asAdmin().getEndStep();
      var children = parent.getLocalChildren().isEmpty()
          ? parent.getGlobalChildren() : parent.getLocalChildren();
      var child = children.getFirst();
      assertThat(child.isRoot()).isFalse();
      assertThat(RuntimeRidStartEligibility.evaluate(child, true)).isNull();
    }
    var synthetic = postStrategy(__.V(A).out());
    assertThat(synthetic.isRoot()).isTrue();
    assertThat(RuntimeRidStartEligibility.evaluate(synthetic, false)).isNull();
    assertThat(RuntimeRidStartEligibility.evaluate(synthetic, true)).isNotNull();
  }

  // Resolution stays aligned with the literal recogniser, including strings and Identifiable handles.
  @Test
  public void recognisedIdEncodingsWorkAndUnconvertibleIdsStayOut() {
    var handle = mock(Identifiable.class);
    when(handle.getIdentity()).thenReturn(A);
    for (var id : List.of(A.toString(), handle)) {
      assertThat(RuntimeRidStartEligibility.evaluate(__.V(id).out().asAdmin(), true).rid())
          .isEqualTo(A);
    }
    for (var id : List.of(3, "", " ", "not-a-rid", new Object())) {
      reject(__.V(id).out(), __.V().hasId(id).out());
    }
    reject(__.V((Object) null).out());
  }

  // Neither a mutable RID nor its owning array/container may change the already resolved snapshot.
  @Test
  public void mutableIdentityAndTraversalInputsCannotChangeResolvedRid() {
    for (boolean startIds : List.of(true, false)) {
      var live = new ChangeableRecordId(A);
      var admin = (startIds ? __.V(live).out() : __.V().hasId(live).out()).asAdmin();
      var result = RuntimeRidStartEligibility.evaluate(admin, true);
      assertThat(result.rid()).isExactlyInstanceOf(RecordId.class).isNotSameAs(live);
      live.setCollectionAndPosition(B.collectionId(), B.collectionPosition());
      if (startIds) {
        ((GraphStep<?, ?>) admin.getStartStep()).getIds()[0] = B;
      } else {
        var has = (HasStep<?>) admin.getSteps().get(1);
        has.removeHasContainer(idContainer(admin));
        has.addHasContainer(new HasContainer(T.id.getAccessor(), P.eq(B)));
      }
      assertThat(result.rid()).isEqualTo(A);
    }
  }

  // The helper resolves identity once and checks persistence on copy(), not on a live handle.
  @Test
  public void persistenceDecisionUsesTheSingleSnapshot() {
    var live = mock(ChangeableRecordId.class);
    when(live.getIdentity()).thenReturn(live);
    when(live.copy()).thenReturn(A);
    when(live.isPersistent()).thenReturn(false);
    var result = RuntimeRidStartEligibility.evaluate(__.V(live).out().asAdmin(), true);
    assertThat(result).isNotNull();
    assertThat(result.rid()).isEqualTo(A);
    verify(live, times(1)).getIdentity();
    verify(live, times(1)).copy();
    when(live.copy()).thenReturn(new RecordId(25, -2));
    when(live.isPersistent()).thenReturn(true);
    assertThat(RuntimeRidStartEligibility.evaluate(__.V(live).out().asAdmin(), true)).isNull();
  }

  private static Traversal.Admin<?, ?> postStrategy(GraphTraversal<?, ?> traversal) {
    var admin = traversal.asAdmin();
    admin.setStrategies(
        new DefaultTraversalStrategies().addStrategies(InlineFilterStrategy.instance()));
    admin.applyStrategies();
    return admin;
  }

  private static HasContainer idContainer(Traversal.Admin<?, ?> traversal) {
    return traversal.getSteps().stream().filter(HasStep.class::isInstance)
        .map(step -> (HasStep<?>) step).flatMap(step -> step.getHasContainers().stream())
        .filter(container -> T.id.getAccessor().equals(container.getKey())).findFirst()
        .orElseThrow();
  }

  private static void reject(GraphTraversal<?, ?>... traversals) {
    for (var traversal : traversals) {
      var admin = postStrategy(traversal);
      assertThat(RuntimeRidStartEligibility.evaluate(admin, true)).as(admin.toString()).isNull();
    }
  }
}
