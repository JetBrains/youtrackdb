package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ConstantTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.GValueConstantTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.GValue;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ByModulatorOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.InlineFilterStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.util.function.ConstantSupplier;
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

  // Bare lookups, projections without a hop, target-filtered unlabeled hops and edge output stay out.
  @Test
  public void missingHopAndOtherFiltersOrOutputsAreIneligible() {
    reject(__.V(A), __.V().hasId(A), __.V().out(), __.V(A).values("name"),
        __.V(A).has("name", P.gt("a")).out(), __.V(A).out().has("name", "a"),
        __.V(A).outE(), __.V(A).outE("a").values("name"), __.E(A).outV());
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

  // Each new suffix and all labeled directions qualify, including written edge-count forms.
  @Test
  public void labeledStructuralFinalsAndMultiLabelHopsQualify() {
    for (var direction : org.apache.tinkerpop.gremlin.structure.Direction.values()) {
      for (var labels : List.of(new String[] {"a"}, new String[] {"a", "b"})) {
        for (int arm = 0; arm < 5; arm++) {
          var hop = __.V(A).to(direction, labels);
          var query = switch (arm) {
            case 0 -> hop;
            case 1 -> hop.values("name");
            case 2 -> hop.limit(2);
            case 3 -> hop.range(1, 3);
            default -> hop.order().by("name");
          };
          var admin = query.asAdmin();
          admin.setStrategies(new DefaultTraversalStrategies().addStrategies(
              ByModulatorOptimizationStrategy.instance()));
          admin.applyStrategies();
          assertThat(RuntimeRidStartEligibility.evaluate(admin, true))
              .as("%s labels %s final %s", direction, labels.length, arm).isNotNull();
        }
      }
    }
    for (var traversal : List.of(__.V(A).out("a", "b"), __.V(A).in("a", "b"),
        __.V().hasId(A).both("a", "b"), __.V(A).out("a").count(),
        __.V(A).outE("a").count(), __.V(A).inE("a", "b").count(),
        __.V(A).bothE("a").count(), __.V(A).out("a").values("name"),
        __.V(A).out("a").limit(0), __.V(A).out("a").range(1, 1),
        __.V(A).out("a").range(1, 3))) {
      assertThat(RuntimeRidStartEligibility.evaluate(postStrategy(traversal), true))
          .as(traversal.toString()).isNotNull();
    }
  }

  // A final operation is a single labeled-hop suffix, never an arbitrary terminal chain.
  @Test
  public void excludedTerminalPayloadsAndChainsStayLiteral() {
    reject(__.V(A).out().count(), __.V(A).out().values("name"),
        __.V(A).out().limit(1), __.V(A).out().order().by("name"),
        __.V(A).out("a").properties("name"), __.V(A).out("a").values(),
        __.V(A).out("a").values("name", "age"), __.V(A).out("a").skip(1),
        __.V(A).out("a").range(0, Long.MAX_VALUE),
        __.V(A).out("a").values("name").limit(1), __.V(A).out("a").limit(1).count(),
        __.V(A).out("a").order().by("name").limit(1),
        __.V(A).out("a").order().by("name").range(1, -1),
        __.V(A).out("a").order().by("name").skip(1),
        __.V(A).out("a").order().by("name").values("name"),
        __.V(A).outE("a").has("weight", 1).count());
  }

  // Boxed null bounds and malformed finite bounds decline without unboxing or normalization.
  @Test
  public void nullAndInvalidRangeBoundsDeclineWithoutThrowing() {
    for (Long[] bounds : new Long[][] {{null, 1L}, {0L, null}, {-1L, 1L}, {2L, 1L}}) {
      var traversal = __.V(A).out("a").asAdmin();
      var range = mock(RangeGlobalStep.class);
      when(range.getLowRange()).thenReturn(bounds[0]);
      when(range.getHighRange()).thenReturn(bounds[1]);
      traversal.addStep(range);
      assertThat(RuntimeRidStartEligibility.evaluate(traversal, true)).isNull();
    }
  }

  // Explicit ProductiveBy installs a missing-key wrapper that must remain eligible.
  @Test
  public void propertyOrderAcceptsProductiveBypassAndRejectsUnsupportedKeys() {
    for (var direction : List.of(Order.asc, Order.desc)) {
      var traversal = __.V(A).out("a").order().by("name", direction).asAdmin();
      traversal.setStrategies(new DefaultTraversalStrategies().addStrategies(
          ByModulatorOptimizationStrategy.instance(), ProductiveByStrategy.instance()));
      traversal.applyStrategies();
      var order = (OrderGlobalStep<?, ?>) traversal.getSteps().getLast();
      var key = (ValueTraversal<?, ?>) order.getComparators().getFirst().getValue0();
      assertThat(key.getBypassTraversal()).isNotNull();
      assertThat(RuntimeRidStartEligibility.evaluate(traversal, true)).isNotNull();
    }
    for (var traversal : List.of(__.V(A).out("a").order(),
        __.V(A).out("a").order().by(T.id), __.V(A).out("a").order().by(T.label),
        __.V(A).out("a").order().by("name", Order.shuffle),
        __.V(A).out("a").order().by("name").by("age"),
        __.V(A).out("a").order().by(__.out().count()),
        __.V(A).out("a").order().by("name", java.util.Comparator.<String>reverseOrder()))) {
      var admin = traversal.asAdmin();
      admin.setStrategies(new DefaultTraversalStrategies().addStrategies(
          ByModulatorOptimizationStrategy.instance()));
      admin.applyStrategies();
      assertThat(RuntimeRidStartEligibility.evaluate(admin, true)).isNull();
    }
  }

  // Only ProductiveBy's exact two-child wrapper with a plain ConstantTraversal(null) qualifies.
  @Test
  public void productiveWrapperRequiresMatchingPlainPropertyAndNullFallback() {
    var property = new ValueTraversal<>("name");
    var customFallback = new ConstantTraversal<>(null);
    customFallback.setBypassTraversal(__.<Object>constant(7).asAdmin());
    // This lambda exposes ConstantStep(null) but returns 7. Step-based admission is unsound.
    var disguisedFallback = new GValueConstantTraversal<Integer, Integer>(GValue.of(7));
    disguisedFallback.setBypassTraversal(__.<Integer>constant(null).asAdmin());
    assertThat(disguisedFallback.next()).isEqualTo(7);
    var productiveBypass = __.start().asAdmin();
    productiveBypass.addStep(
        new org.apache.tinkerpop.gremlin.process.traversal.step.map.CoalesceStep(
            productiveBypass, property, new ConstantTraversal<>(null)));
    var productiveQuery = __.V(A).out("a").order()
        .by(new ValueTraversal("name", productiveBypass)).asAdmin();
    productiveQuery.setStrategies(new DefaultTraversalStrategies().addStrategies(
        ProductiveByStrategy.instance()));
    assertThat(RuntimeRidStartEligibility.evaluate(productiveQuery, true)).isNotNull();
    for (var children : List.of(
        new Traversal.Admin[] {property},
        new Traversal.Admin[] {property, __.constant(null).asAdmin(), __.constant(null).asAdmin()},
        new Traversal.Admin[] {new ValueTraversal<>("other"), __.constant(null).asAdmin()},
        new Traversal.Admin[] {new ValueTraversal("name", __.constant(7).asAdmin()),
            __.constant(null).asAdmin()},
        new Traversal.Admin[] {property, new ConstantTraversal<>(7)},
        new Traversal.Admin[] {property, customFallback},
        new Traversal.Admin[] {property, disguisedFallback},
        new Traversal.Admin[] {property, __.constant(null).asAdmin()},
        new Traversal.Admin[] {disguisedFallback, new ConstantTraversal<>(null)},
        new Traversal.Admin[] {property, __.identity().asAdmin()},
        new Traversal.Admin[] {property, __.constant(7).asAdmin()})) {
      var bypass = __.start().asAdmin();
      bypass.addStep(new org.apache.tinkerpop.gremlin.process.traversal.step.map.CoalesceStep(
          bypass, children));
      var query = __.V(A).out("a").order().by(new ValueTraversal("name", bypass)).asAdmin();
      query.setStrategies(new DefaultTraversalStrategies().addStrategies(
          ProductiveByStrategy.instance()));
      assertThat(RuntimeRidStartEligibility.evaluate(query, true)).as(query.toString()).isNull();
    }
  }

  // A ValueTraversal bypass runs instead of the property lookup. Arbitrary children must decline.
  @Test
  public void customPropertyBypassesStayLiteral() {
    for (var bypass : List.of(__.constant(7).asAdmin(),
        __.coalesce(__.constant(7), __.constant(null)).asAdmin(),
        __.coalesce(__.values("other"), __.constant(null)).asAdmin(),
        __.coalesce(__.values("name"), __.constant(7)).asAdmin(),
        __.coalesce(__.values("name"), __.constant(null)).identity().asAdmin())) {
      var query = __.V(A).out("a").order().by(new ValueTraversal("name", bypass)).asAdmin();
      query.setStrategies(new DefaultTraversalStrategies().addStrategies(
          ProductiveByStrategy.instance()));
      assertThat(RuntimeRidStartEligibility.evaluate(query, true)).as(query.toString()).isNull();
    }
  }

  // A RID key in the middle does not make a three-comparator order part of the grammar.
  @Test
  public void threeComparatorOrderStaysLiteralAfterStrategies() {
    var query = __.V(A).out("a").order().by("name").by(T.id).by("age").asAdmin();
    query.setStrategies(new DefaultTraversalStrategies().addStrategies(
        ByModulatorOptimizationStrategy.instance()));
    query.applyStrategies();
    var order = (OrderGlobalStep<?, ?>) query.getSteps().getLast();
    order.replaceLocalChild(order.getComparators().get(1).getValue0(),
        new com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal());
    assertThat(order.getComparators()).hasSize(3);
    assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNull();
  }

  // Every inherited RID-key bypass must decline, including productive and empty bypasses.
  @Test
  public void ridTieBreakRequiresPlainLambda() {
    for (var bypass : List.of(__.identity().asAdmin(), __.inject(1).asAdmin(),
        __.limit(0).asAdmin())) {
      var rid = new RecordIdSortKeyTraversal();
      var query = __.V(A).out("a").order().by(new ValueTraversal<>("name"))
          .by(rid, Order.asc).asAdmin();
      assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNotNull();
      rid.setBypassTraversal(bypass);
      assertThat(RuntimeRidStartEligibility.evaluate(query, true)).as(bypass.toString()).isNull();
    }
  }

  // Count translation assumes the exact default seed supplier and sumLong reducer.
  @Test
  public void countRequiresDefaultSeedAndReducer() {
    for (int mutation = 0; mutation < 3; mutation++) {
      var query = __.V(A).out("a").count().asAdmin();
      var count = (CountGlobalStep<?>) query.getEndStep();
      assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNotNull();
      switch (mutation) {
        case 0 -> count.setSeedSupplier(new ConstantSupplier<>(5L));
        case 1 -> count.setSeedSupplier(() -> 0L);
        default -> count.setReducingBiOperator((left, right) -> 42L);
      }
      assertThat(RuntimeRidStartEligibility.evaluate(query, true)).as("count %s", mutation)
          .isNull();
    }
  }

  // Wrapper presence must follow the root configuration, including declared productive keys.
  @Test
  public void propertyWrapperMustMatchProductiveByConfiguration() {
    for (var strategy : List.of(ProductiveByStrategy.instance(),
        ProductiveByStrategy.build().productiveKeys("name").create())) {
      for (var key : List.of("name", "age")) {
        var query = __.V(A).out("a").order().by(key).asAdmin();
        query.setStrategies(new DefaultTraversalStrategies().addStrategies(
            ByModulatorOptimizationStrategy.instance(), strategy));
        query.applyStrategies();
        var value = (ValueTraversal) ((OrderGlobalStep<?, ?>) query.getSteps().getLast())
            .getComparators().getFirst().getValue0();
        boolean wrapped = strategy.getProductiveKeys().isEmpty()
            || !strategy.getProductiveKeys().contains(key);
        assertThat(value.getBypassTraversal() != null).isEqualTo(wrapped);
        assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNotNull();
        if (wrapped) {
          value.setBypassTraversal(null);
        } else {
          var wrapper = __.start().asAdmin();
          wrapper.addStep(new org.apache.tinkerpop.gremlin.process.traversal.step.map.CoalesceStep(
              wrapper, new ValueTraversal<>(key), new ConstantTraversal<>(null)));
          value.setBypassTraversal(wrapper);
        }
        assertThat(RuntimeRidStartEligibility.evaluate(query, true))
            .as("key %s productive keys %s", key, strategy.getProductiveKeys()).isNull();
      }
    }
    var query = __.V(A).out("a").order().by("name").asAdmin();
    query.setStrategies(new DefaultTraversalStrategies().addStrategies(
        ByModulatorOptimizationStrategy.instance(), ProductiveByStrategy.instance()));
    query.applyStrategies();
    assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNotNull();
    query.getStrategies().removeStrategies(ProductiveByStrategy.class);
    assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNull();
  }

  // Native filtering state and the translator's strategy-based interpretation must agree.
  @Test
  public void orderFilteringMustMatchStandardSemanticsStrategy() {
    var query = __.V(A).out("a").order().by(new ValueTraversal<>("name")).asAdmin();
    query.setStrategies(new DefaultTraversalStrategies());
    var order = (OrderGlobalStep<?, ?>) query.getSteps().getLast();
    assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNotNull();
    query.getStrategies().addStrategies(StandardOrderSemanticsStrategy.instance());
    assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNull();
    order.enableFilteringUnproductiveTraversers();
    assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNotNull();
    query.getStrategies().removeStrategies(StandardOrderSemanticsStrategy.class);
    assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNull();
  }

  // All exact immutable scalar classes and resolved variables qualify at their admitted phases.
  @Test
  public void scalarFiltersAndResolvedVariablesQualify() {
    for (Object value : List.of("a", true, (byte) 1, (short) 2, 3, 4L, 5F, 6D,
        new java.math.BigDecimal("7"))) {
      for (Object operand : List.of(value, GValue.of("operand", value))) {
        for (P<?> predicate : List.of(P.eq(operand), P.neq(operand))) {
          assertThat(RuntimeRidStartEligibility.evaluate(
              __.V(A).has("key", predicate).out().asAdmin(), true)).isNotNull();
        }
        for (P<?> predicate : List.of(P.eq(operand), P.neq(operand), P.lt(operand),
            P.lte(operand), P.gt(operand), P.gte(operand), P.within(List.of(operand)),
            P.without(List.of(operand)))) {
          assertThat(RuntimeRidStartEligibility.evaluate(
              __.V(A).out("a").has("key", predicate).asAdmin(), true)).isNotNull();
        }
      }
    }
    var variables = P.within(List.of(GValue.of("x", 1), "literal", GValue.of("x", 2),
        GValue.of("y", true)));
    assertThat(variables.getValue()).isEqualTo(List.of("literal", 2, true));
    for (P<?> membership : List.of(variables, P.within(List.of(1, 1, "a")),
        P.without(new java.util.LinkedHashSet<>(List.of(1, "a"))))) {
      assertThat(RuntimeRidStartEligibility.evaluate(
          __.V(A).out("a").has("key", membership).asAdmin(), true)).isNotNull();
    }
  }

  // The phases accept conjunctions and exactly one filtered edge followed by its IN endpoint.
  @Test
  public void predicatePhasesAndEdgeFinalCombinationsQualify() {
    for (int arm = 0; arm < 6; arm++) {
      var hop = __.V().has("before", "a").hasLabel("Person").hasId(A)
          .has("after", P.neq(false)).outE("a").has("edge", 1).inV()
          .hasLabel("Person").has("key", P.within(1, "a", 1)).has("key", P.neq("b"));
      var query = switch (arm) {
        case 0 -> hop;
        case 1 -> hop.count();
        case 2 -> hop.values("name");
        case 3 -> hop.limit(1);
        case 4 -> hop.range(1, 2);
        default -> hop.order().by(new ValueTraversal<>("name"));
      };
      assertThat(RuntimeRidStartEligibility.evaluate(query.asAdmin(), true)).isNotNull();
    }
    assertThat(RuntimeRidStartEligibility.evaluate(
        __.V(A).has("key", 1).has("key", P.neq(2)).out().asAdmin(), true)).isNotNull();
  }

  // Separate post-strategy HasSteps obey the same phase and duplicate-label rules as merged ones.
  @Test
  public void separatePredicateStepsPreservePhaseRules() {
    var query = __.V(A).out("a").asAdmin();
    query.addStep(1, new HasStep<>(query, new HasContainer("start", P.neq(1))));
    query.addStep(new HasStep<>(query, new HasContainer("target", P.gt(2))));
    query.addStep(new HasStep<>(query, new HasContainer(T.label.getAccessor(), P.eq("Person"))));
    query.addStep(new HasStep<>(query, new HasContainer("target", P.lt(3))));
    assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNotNull();
    query.addStep(new HasStep<>(query, new HasContainer(T.label.getAccessor(), P.eq("Person"))));
    assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNull();
    var empty = __.V(A).out("a").asAdmin();
    empty.addStep(new HasStep<>(empty));
    assertThat(RuntimeRidStartEligibility.evaluate(empty, true)).isNull();
  }

  // Unsupported values stay literal even when nested inside nonempty membership operands.
  @Test
  public void unsupportedScalarAndMembershipValuesStayLiteral() {
    for (Object value : java.util.Arrays.asList(null, new java.util.Date(),
        java.math.BigInteger.ONE, new java.util.concurrent.atomic.AtomicInteger(1),
        new Object(), List.of(1), java.util.Map.of("a", 1), A,
        new java.math.BigDecimal("1") {
        })) {
      reject(__.V(A).has("key", P.eq(value)).out("a"),
          __.V(A).out("a").has("key", P.eq(value)),
          __.V(A).out("a").has("key", P.within(java.util.Arrays.asList(1, value))));
    }
    reject(__.V(A).has("key", P.gt(1)).out("a"),
        __.V(A).has("key", P.within(1)).out("a"),
        __.V(A).out("a").has("key", P.within(List.of())),
        __.V(A).out("a").has("key", P.without(List.of())),
        __.V(A).out("a").has("key", P.between(1, 3)),
        __.V(A).out("a").has("key", P.eq(1).or(P.eq(2))),
        __.V(A).out("a").has("key", org.apache.tinkerpop.gremlin.process.traversal.TextP
            .startingWith("a")));
  }

  // Exact nested classes apply to ID and label as well as ordinary start, edge and target leaves.
  @Test
  public void predicateAndContainerSubclassesNeverQualify() {
    for (String key : List.of(T.id.getAccessor(), T.label.getAccessor(), "key")) {
      Object value = key.equals(T.id.getAccessor()) ? A
          : key.equals(T.label.getAccessor()) ? "Person" : 1;
      for (boolean predicateSubclass : List.of(false, true)) {
        P<Object> predicate = predicateSubclass
            ? new P<Object>(org.apache.tinkerpop.gremlin.process.traversal.Compare.eq, value) {
            }
            : P.eq(value);
        HasContainer container = predicateSubclass ? new HasContainer(key, predicate)
            : new HasContainer(key, predicate) {
            };
        var query = (key.equals(T.id.getAccessor()) ? __.V() : __.V(A)).out("a").asAdmin();
        query.addStep(1, new HasStep<>(query, container));
        assertThat(RuntimeRidStartEligibility.evaluate(query, true)).as(key).isNull();
        if (!key.equals(T.id.getAccessor())) {
          var target = __.V(A).out("a").asAdmin();
          target.addStep(new HasStep<>(target, container));
          assertThat(RuntimeRidStartEligibility.evaluate(target, true)).as(key).isNull();
        }
      }
    }
    var edge = __.V(A).outE("a").inV().asAdmin();
    edge.addStep(2, new HasStep<>(edge, new HasContainer("key", P.eq(1)) {
    }));
    assertThat(RuntimeRidStartEligibility.evaluate(edge, true)).isNull();
  }

  // Each structural exclusion has a nearby admitted control in the phase and edge tests above.
  @Test
  public void predicateStructureExclusionsStayLiteral() {
    reject(__.V(A).out("a").hasLabel("Person").hasLabel("Person"),
        __.V(A).out("a").hasLabel("Person", "Other"),
        __.V(A).out("a").hasId(B), __.V(A).out("a").hasId(B.toString()),
        __.V(A).out().has("key", 1),
        __.V(A).out().hasLabel("Person"), __.V(A).has("key", 1).out().count(),
        __.V(A).inE("a").has("key", 1).outV(),
        __.V(A).inE("a").has("key", 1).inV(),
        __.V(A).bothE("a").has("key", 1).otherV(),
        __.V(A).outE("a", "b").has("key", 1).inV(),
        __.V(A).outE().has("key", 1).inV(),
        __.V(A).outE("a").has("key", P.neq(1)).inV(),
        __.V(A).outE("a").has("key", 1).has("other", 2).inV(),
        __.V(A).outE("a").has("key", 1).outV(),
        __.V(A).outE("a").has("key", 1).inV().out("b"),
        __.V(A).outE("a").has("key", 1).inV().values("name").limit(1));
    for (String key : List.of(" ", "~hidden", "@rid", "$private")) {
      assertThat(RuntimeRidStartEligibility.evaluate(
          __.V(A).out("a").has(key, 1).asAdmin(), true)).as(key).isNull();
    }
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
