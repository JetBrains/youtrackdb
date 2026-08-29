package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ColumnTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TokenTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.FilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexStartStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeOtherVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ElementMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ElementStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FlatMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupCountStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.IdStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.IndexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.LabelStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MathStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MaxGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MeanGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MergeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MinGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PathStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyValueStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SumGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TreeStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.UnfoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.InjectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Column;
import org.apache.tinkerpop.gremlin.structure.T;
import org.javatuples.Pair;

/**
 * Appends a stream-typed secondary sort key to every global and local {@code order()} step before
 * {@link GremlinToMatchStrategy} runs. Every {@code OrderGlobalStep} / {@code OrderLocalStep} that
 * lacks an explicit tie-break gains one, so tie groups are totally ordered whether the translator
 * accepts the shape or declines to native Gremlin.
 *
 * <ul>
 *   <li>Global order over graph elements → {@code by(T.id, asc)} (MATCH {@code @rid}).
 *   <li>Global order over map entries after {@code group*().unfold()} → {@code by(keys, asc)}.
 *   <li>Global order over anything else → {@code by(identity, asc)} (TinkerPop orderability).
 *   <li>Local {@code order(local)}: folded elements → {@code T.id} (replacing bare identity);
 *       map from {@code group*}/{@code project}/{@code *Map} → {@code keys} or
 *       {@code select(keys).id()} when the group key is an element; otherwise identity.
 * </ul>
 *
 * <p>Skips when the last comparator is {@code Order.shuffle}, {@code T.id}, {@code Column.keys}
 * (or {@code select(keys).id()}), identity where that is a valid total order, or property
 * {@code "id"}. Property {@code id} is assumed unique in domain data — appending {@code T.id}
 * would not change order when that holds and would only add comparator work. Duplicate property
 * {@code id} values remain an intentional stability exception (alongside shuffle).
 *
 * <p>Recogniser contract: {@code IdentityTraversal} → {@code @rid} only on element boundaries.
 * Future entry/map translation must map {@code Column.keys} to the GROUP BY key — never
 * {@code @rid}.
 */
public final class YTDBOrderRidTieBreakStrategy
    extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
    implements ProviderOptimizationStrategy {

  private static final YTDBOrderRidTieBreakStrategy INSTANCE = new YTDBOrderRidTieBreakStrategy();

  private YTDBOrderRidTieBreakStrategy() {
  }

  public static YTDBOrderRidTieBreakStrategy instance() {
    return INSTANCE;
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPost() {
    return Set.of(GremlinToMatchStrategy.class);
  }

  @Override
  public void apply(Admin<?, ?> traversal) {
    if (YTDBStrategyUtil.resolveYtdbSession(traversal) == null) {
      return;
    }
    TraversalHelper.getStepsOfAssignableClassRecursively(OrderGlobalStep.class, traversal)
        .forEach(YTDBOrderRidTieBreakStrategy::appendGlobalTieBreak);
    TraversalHelper.getStepsOfAssignableClassRecursively(OrderLocalStep.class, traversal)
        .forEach(YTDBOrderRidTieBreakStrategy::appendLocalTieBreak);
  }

  private static void appendGlobalTieBreak(OrderGlobalStep<?, ?> step) {
    var comparators = step.getComparators();
    if (isShuffle(comparators) || hasExplicitTieBreak(comparators)) {
      return;
    }
    // Admin wiring is required by OrderGlobalStep.modulateBy (same as order().by(...)).
    switch (classifyFrom(step.getPreviousStep())) {
      case ELEMENT -> step.modulateBy(new TokenTraversal(T.id).asAdmin(), Order.asc);
      case MAP_ENTRY -> step.modulateBy(new ColumnTraversal(Column.keys).asAdmin(), Order.asc);
      case OTHER -> step.modulateBy(new IdentityTraversal<>().asAdmin(), Order.asc);
    }
  }

  /**
   * Local order sorts collection or map members. {@code OrderLocalStep} casts each modulator
   * projection to {@code Comparable}, so bare identity on vertices or map entries must be
   * replaced — not appended after.
   */
  private static void appendLocalTieBreak(OrderLocalStep<?, ?> step) {
    var comparators = step.getComparators();
    if (isShuffle(comparators)) {
      return;
    }
    switch (classifyLocalMembers(step)) {
      case ELEMENT -> ensureLocalElementTieBreak(step, comparators);
      case MAP_ENTRY -> ensureLocalMapEntryTieBreak(step, comparators);
      case OTHER -> {
        if (!hasExplicitTieBreak(comparators)) {
          step.modulateBy(new IdentityTraversal<>().asAdmin(), Order.asc);
        }
      }
    }
  }

  private static void ensureLocalElementTieBreak(
      OrderLocalStep<?, ?> step,
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (endsWithTokenId(comparators) || endsWithPropertyId(comparators)) {
      return;
    }
    if (endsWithIdentity(comparators)) {
      replaceOrSetLocalModulator(step, new TokenTraversal(T.id).asAdmin());
      return;
    }
    step.modulateBy(new TokenTraversal(T.id).asAdmin(), Order.asc);
  }

  private static void ensureLocalMapEntryTieBreak(
      OrderLocalStep<?, ?> step,
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (selectsEntryKeyTieBreak(comparators.getLast().getValue0())
        || endsWithPropertyId(comparators)) {
      return;
    }
    var keyModulator = entryKeyTieBreakModulator(step);
    if (endsWithIdentity(comparators)) {
      replaceOrSetLocalModulator(step, keyModulator);
      return;
    }
    step.modulateBy(keyModulator, Order.asc);
  }

  /**
   * Bare {@code order(local)} often has an empty comparator field and a synthetic identity from
   * {@code getComparators()}; {@code modulateBy} then installs the real modulator. When identity
   * is already stored, {@code replaceLocalChild} swaps it in place (append would still ClassCast).
   */
  private static void replaceOrSetLocalModulator(
      OrderLocalStep<?, ?> step, Admin<?, ?> modulator) {
    if (step.getLocalChildren().isEmpty()) {
      step.modulateBy(modulator, Order.asc);
      return;
    }
    var last = step.getComparators().getLast().getValue0();
    step.replaceLocalChild(last, modulator);
  }

  /**
   * Members of a local order: folded pre-fold stream, or map entries when ordering a
   * {@code group*}/{@code project}/{@code *Map} result in place.
   */
  private static StreamKind classifyLocalMembers(OrderLocalStep<?, ?> step) {
    Step<?, ?> current = step.getPreviousStep();
    while (!(current instanceof EmptyStep) && isTransparent(current)) {
      current = current.getPreviousStep();
    }
    if (current instanceof FoldStep) {
      return classifyFrom(current.getPreviousStep());
    }
    if (current instanceof GroupStep
        || current instanceof GroupCountStep
        || current instanceof ProjectStep
        || current instanceof PropertyMapStep
        || current instanceof ElementMapStep) {
      return StreamKind.MAP_ENTRY;
    }
    return StreamKind.OTHER;
  }

  /** Key modulator for local map order: element group keys need {@code id()}, not raw keys. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Admin<?, ?> entryKeyTieBreakModulator(OrderLocalStep<?, ?> orderStep) {
    Step<?, ?> current = orderStep.getPreviousStep();
    while (!(current instanceof EmptyStep) && isTransparent(current)) {
      current = current.getPreviousStep();
    }
    if (groupKeyIsElement(current)) {
      return (Admin) __.select(Column.keys).id().asAdmin();
    }
    return new ColumnTraversal(Column.keys).asAdmin();
  }

  private static boolean groupKeyIsElement(Step<?, ?> groupOrMapStep) {
    if (groupOrMapStep instanceof GroupStep<?, ?, ?> group) {
      return keyTraversalProjectsElements(group.getKeyTraversal(), group.getPreviousStep());
    }
    if (groupOrMapStep instanceof GroupCountStep<?, ?> groupCount) {
      var children = groupCount.getLocalChildren();
      var key = children.isEmpty() ? null : children.getFirst();
      return keyTraversalProjectsElements(key, groupCount.getPreviousStep());
    }
    // project / *Map keys are labels / property names — Comparable strings.
    return false;
  }

  /**
   * Default {@code by()} is identity over the group input: element stream → element keys; label /
   * scalar stream → comparable keys. Explicit property/token key traversals are never elements.
   */
  private static boolean keyTraversalProjectsElements(
      Admin<?, ?> keyTraversal, Step<?, ?> groupPrevious) {
    if (keyTraversal == null || keyTraversal instanceof IdentityTraversal) {
      return classifyFrom(groupPrevious) == StreamKind.ELEMENT;
    }
    if (keyTraversal.getSteps().isEmpty()) {
      return false;
    }
    var end = keyTraversal.getEndStep();
    if (end instanceof IdentityStep) {
      return classifyFrom(groupPrevious) == StreamKind.ELEMENT;
    }
    return emitsElements(end);
  }

  private static boolean isShuffle(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (comparators == null || comparators.isEmpty()) {
      return false;
    }
    return Order.shuffle.equals(comparators.getLast().getValue1());
  }

  private static boolean hasExplicitTieBreak(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (comparators == null || comparators.isEmpty()) {
      return true;
    }
    var lastModulator = comparators.getLast().getValue0();
    if (lastModulator instanceof TokenTraversal token && T.id.equals(token.getToken())) {
      return true;
    }
    if (lastModulator instanceof IdentityTraversal) {
      return true;
    }
    if (selectsEntryKeyTieBreak(lastModulator)) {
      return true;
    }
    // Property "id" is treated as a domain-unique surrogate — see class Javadoc.
    return endsWithPropertyId(comparators);
  }

  private static boolean endsWithTokenId(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (comparators == null || comparators.isEmpty()) {
      return false;
    }
    var last = comparators.getLast().getValue0();
    return last instanceof TokenTraversal token && T.id.equals(token.getToken());
  }

  private static boolean endsWithIdentity(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (comparators == null || comparators.isEmpty()) {
      return false;
    }
    return comparators.getLast().getValue0() instanceof IdentityTraversal;
  }

  private static boolean endsWithPropertyId(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (comparators == null || comparators.isEmpty()) {
      return false;
    }
    return ByModulatorTranslator.keyModulatorPropertyKey(comparators.getLast().getValue0())
        .filter("id"::equals)
        .isPresent();
  }

  /**
   * {@code by(Column.keys)}, {@code by(__.select(Column.keys))}, or
   * {@code by(__.select(Column.keys).id())} already ties on the entry key.
   */
  private static boolean selectsEntryKeyTieBreak(Admin<?, ?> modulator) {
    if (modulator instanceof ColumnTraversal column && Column.keys.equals(column.getColumn())) {
      return true;
    }
    for (var step : modulator.getSteps()) {
      if (step instanceof TraversalParent parent) {
        for (var child : parent.getLocalChildren()) {
          if (selectsEntryKeyTieBreak(child)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private enum StreamKind {
    ELEMENT, MAP_ENTRY, OTHER
  }

  /**
   * Walks upstream until a step that clearly emits elements, map entries, or something else.
   * Filter / barrier / identity steps are transparent. {@code fold().unfold()} continues past the
   * fold so vertex folds recover the element stream. {@code select}/{@code selectOne} are not
   * assumed to be elements — identity orderability covers element payloads safely.
   */
  private static StreamKind classifyFrom(Step<?, ?> start) {
    Step<?, ?> current = start;
    while (!(current instanceof EmptyStep)) {
      if (isTransparent(current)) {
        current = current.getPreviousStep();
        continue;
      }
      if (current instanceof UnfoldStep) {
        var beforeUnfold = current.getPreviousStep();
        if (beforeUnfold instanceof GroupStep || beforeUnfold instanceof GroupCountStep) {
          return StreamKind.MAP_ENTRY;
        }
        if (beforeUnfold instanceof FoldStep) {
          // fold().unfold() restores the pre-fold stream — keep walking.
          current = beforeUnfold.getPreviousStep();
          continue;
        }
        // index().unfold() and other unfolds → pairs / unknown payloads.
        return StreamKind.OTHER;
      }
      if (current instanceof SelectStep || current instanceof SelectOneStep) {
        return StreamKind.OTHER;
      }
      if (current instanceof MapStep || current instanceof FlatMapStep) {
        return localChildEmitsElements(current) ? StreamKind.ELEMENT : StreamKind.OTHER;
      }
      if (emitsElements(current)) {
        return StreamKind.ELEMENT;
      }
      if (emitsNonElement(current)) {
        return StreamKind.OTHER;
      }
      return StreamKind.OTHER;
    }
    return StreamKind.OTHER;
  }

  private static boolean localChildEmitsElements(Step<?, ?> step) {
    if (!(step instanceof TraversalParent parent)) {
      return false;
    }
    var children = parent.getLocalChildren();
    if (children.isEmpty()) {
      return false;
    }
    var end = children.getFirst().getEndStep();
    if (emitsNonElement(end)) {
      return false;
    }
    return emitsElements(end) || end instanceof FilterStep || end instanceof IdentityStep;
  }

  private static boolean isTransparent(Step<?, ?> step) {
    return step instanceof FilterStep
        || step instanceof IdentityStep
        || step instanceof NoOpBarrierStep
        || step instanceof DedupGlobalStep
        || step instanceof RangeGlobalStep
        || step instanceof OrderGlobalStep
        || step instanceof OrderLocalStep;
  }

  private static boolean emitsElements(Step<?, ?> step) {
    return step instanceof GraphStep
        || step instanceof VertexStep
        || step instanceof EdgeVertexStep
        || step instanceof EdgeOtherVertexStep
        || step instanceof AddVertexStep
        || step instanceof AddVertexStartStep
        || step instanceof AddEdgeStep
        || step instanceof MergeVertexStep
        || step instanceof ElementStep;
  }

  private static boolean emitsNonElement(Step<?, ?> step) {
    return step instanceof FoldStep
        || step instanceof GroupStep
        || step instanceof GroupCountStep
        || step instanceof ProjectStep
        || step instanceof PropertyMapStep
        || step instanceof ElementMapStep
        || step instanceof PropertyValueStep
        || step instanceof CountGlobalStep
        || step instanceof SumGlobalStep
        || step instanceof MeanGlobalStep
        || step instanceof MaxGlobalStep
        || step instanceof MinGlobalStep
        || step instanceof TreeStep
        || step instanceof PathStep
        || step instanceof IndexStep
        || step instanceof MathStep
        || step instanceof IdStep
        || step instanceof LabelStep
        || step instanceof InjectStep;
  }
}
