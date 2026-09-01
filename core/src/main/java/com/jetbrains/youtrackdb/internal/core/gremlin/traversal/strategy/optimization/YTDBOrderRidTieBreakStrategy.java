package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
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
 * Gives every global and local {@code order()} step a stream-typed secondary sort key, before
 * {@link GremlinToMatchStrategy} runs. Tie groups then carry one total order whether the translator
 * accepts the shape or declines it to the native Gremlin pipeline. The key is appended
 * unconditionally on an accepted shape — it is not conditioned on the primary key repeating, because
 * the strategy cannot know whether it repeats.
 *
 * <ul>
 *   <li>Order over graph elements → {@link RecordIdSortKeyTraversal}, which is the MATCH
 *       {@code @rid} order. A trailing element token or bare identity is <em>replaced</em> by it
 *       rather than followed, because both of those sort mixed identifier classes by class name.
 *   <li>Global order over map entries after {@code group*().unfold()} → {@code by(keys)} for a
 *       scalar group key, the same record identifier key for an element group key, then
 *       {@code by(identity)} because one key can repeat across different maps in the stream.
 *   <li>Global order over anything else → {@code by(identity)}, which TinkerPop orderability makes
 *       total over the payloads it can compare.
 *   <li>Local {@code order(local)}: folded elements → the record identifier key; a map from
 *       {@code group*} / {@code project} / {@code *Map} → {@code keys} or the record identifier key
 *       for an element key; an unproven member type → nothing at all, because
 *       {@code OrderLocalStep} casts every projection to {@code Comparable} and an unproven member
 *       is what makes that cast fail.
 * </ul>
 *
 * <p>Skips when the last comparator is {@code Order.shuffle}, the record identifier key,
 * {@code Column.keys} (or {@code select(keys)}), identity where that is already a valid total order,
 * or property {@code "id"}. Property {@code id} is assumed unique in domain data — appending a
 * record identifier would not change order when that holds and would only add comparator work.
 * Duplicate property {@code id} values remain an intentional stability exception, alongside shuffle.
 *
 * <p>Recogniser contract: identity → {@code @rid} only on element boundaries. Entry / map
 * translation must map {@code Column.keys} to the GROUP BY key — never to {@code @rid}.
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
    if (isShuffle(comparators)) {
      return;
    }
    switch (classifyFrom(step.getPreviousStep())) {
      case ELEMENT -> ensureGlobalElementSortKey(step, comparators);
      case MAP_ENTRY -> {
        if (!hasExplicitTieBreak(comparators)) {
          step.modulateBy(globalMapEntryTieBreakModulator(step), Order.asc);
          // One key can repeat across entries of different maps (local group, inject+unfold, …).
          step.modulateBy(new IdentityTraversal<>().asAdmin(), Order.asc);
        }
      }
      case OTHER -> {
        if (!hasExplicitTieBreak(comparators)) {
          step.modulateBy(new IdentityTraversal<>().asAdmin(), Order.asc);
        }
      }
    }
  }

  /**
   * Local order sorts collection or map members. {@code OrderLocalStep} casts each modulator
   * projection to {@code Comparable}, so a bare identity over elements or map entries must be
   * replaced rather than followed, and an unproven member type must gain no modulator at all.
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
        // Nothing: an unproven member is exactly the case where the Comparable cast throws.
      }
    }
  }

  /**
   * A trailing element token or identity is replaced, not followed. Both of them route through
   * TinkerPop orderability, which compares two sibling record identifier classes by class name and
   * then by text — so a transaction-local identifier sorts as a block ahead of a committed one, and
   * the translated arm, which compares numerically, answers a different sequence.
   */
  private static void ensureGlobalElementSortKey(
      OrderGlobalStep<?, ?> step,
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (endsWithRecordIdSortKey(comparators) || endsWithPropertyId(comparators)) {
      return;
    }
    if (endsWithTokenId(comparators) || endsWithIdentity(comparators)) {
      replaceLastGlobalModulator(step, new RecordIdSortKeyTraversal<>());
      return;
    }
    step.modulateBy(new RecordIdSortKeyTraversal<>(), Order.asc);
  }

  private static void ensureLocalElementTieBreak(
      OrderLocalStep<?, ?> step,
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (endsWithRecordIdSortKey(comparators) || endsWithPropertyId(comparators)) {
      return;
    }
    if (endsWithTokenId(comparators) || endsWithIdentity(comparators)) {
      replaceLastLocalModulator(step, new RecordIdSortKeyTraversal<>());
      return;
    }
    step.modulateBy(new RecordIdSortKeyTraversal<>(), Order.asc);
  }

  private static void ensureLocalMapEntryTieBreak(
      OrderLocalStep<?, ?> step,
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    if (selectsEntryKeyTieBreak(comparators.getLast().getValue0())
        || endsWithRecordIdSortKey(comparators)
        || endsWithPropertyId(comparators)) {
      return;
    }
    var keyModulator = entryKeyTieBreakModulator(step);
    if (endsWithIdentity(comparators)) {
      replaceLastLocalModulator(step, keyModulator);
      return;
    }
    step.modulateBy(keyModulator, Order.asc);
  }

  /**
   * Replaces the modulator of the <em>last</em> comparator slot, keeping every other slot and every
   * comparator.
   *
   * <p>{@code replaceLocalChild} cannot express that. It matches a slot by {@code equals}, and every
   * {@code IdentityTraversal} equals every other one, so it rewrites the first identity slot instead
   * of the requested one — leaving the trailing identity in place and the sort key wrong. There is no
   * positional setter on {@code ComparatorHolder}, so the step is rebuilt with the slot list the
   * strategy wants and swapped in at the same index. The index is found by reference, not by
   * {@code TraversalHelper.stepIndex}, which matches on {@code hashCode} and would find an earlier
   * equal-looking order step.
   *
   * <p>A bare {@code order()} keeps its fast path: its comparator field is empty and
   * {@code getComparators} synthesises the identity slot, so {@code modulateBy} installs the real
   * modulator with nothing to replace.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void replaceLastGlobalModulator(OrderGlobalStep step, Admin<?, ?> modulator) {
    if (step.getLocalChildren().isEmpty()) {
      step.modulateBy(modulator, Order.asc);
      return;
    }
    var comparators = (List<Pair<Admin, Comparator>>) step.getComparators();
    var replacement = new OrderGlobalStep(step.getTraversal());
    // Carried explicitly: a preceding range fold already pushed its bound onto the old step.
    replacement.setLimit(step.getLimit());
    fillComparators(comparators, modulator, replacement::addComparator);
    swapStep(step, replacement);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void replaceLastLocalModulator(OrderLocalStep step, Admin<?, ?> modulator) {
    if (step.getLocalChildren().isEmpty()) {
      step.modulateBy(modulator, Order.asc);
      return;
    }
    var comparators = (List<Pair<Admin, Comparator>>) step.getComparators();
    var replacement = new OrderLocalStep(step.getTraversal());
    fillComparators(comparators, modulator, replacement::addComparator);
    swapStep(step, replacement);
  }

  /** Copies every slot into {@code sink}, substituting {@code modulator} for the last one. */
  @SuppressWarnings("rawtypes")
  private static void fillComparators(
      List<Pair<Admin, Comparator>> comparators,
      Admin<?, ?> modulator,
      BiConsumer<Admin, Comparator> sink) {
    var last = comparators.size() - 1;
    for (var i = 0; i < comparators.size(); i++) {
      var slot = comparators.get(i);
      sink.accept(i == last ? modulator : slot.getValue0(), slot.getValue1());
    }
  }

  /** Swaps {@code replacement} in at the exact index {@code step} occupies, labels included. */
  private static void swapStep(Step<?, ?> step, Step<?, ?> replacement) {
    var traversal = step.getTraversal();
    var steps = traversal.getSteps();
    var index = 0;
    while (index < steps.size() && steps.get(index) != step) {
      index++;
    }
    TraversalHelper.copyLabels(step, replacement, false);
    traversal.removeStep(index);
    traversal.addStep(index, replacement);
  }

  /**
   * Members of a local order: the pre-fold stream when a {@code fold()} produced the collection, or
   * map entries when a {@code group*} / {@code project} / {@code *Map} result is ordered in place.
   */
  private static StreamKind classifyLocalMembers(OrderLocalStep<?, ?> step) {
    var source = upstreamSource(step.getPreviousStep());
    if (source instanceof FoldStep) {
      return classifyFrom(source.getPreviousStep());
    }
    return emitsMap(source) ? StreamKind.MAP_ENTRY : StreamKind.OTHER;
  }

  /** Global {@code group*().unfold().order()} — same key modulator policy as local map order. */
  private static Admin<?, ?> globalMapEntryTieBreakModulator(OrderGlobalStep<?, ?> orderStep) {
    return mapEntryKeyTieBreakModulator(unfoldedGroupStep(orderStep));
  }

  /** Key modulator for local map order: an element group key needs the record identifier key. */
  private static Admin<?, ?> entryKeyTieBreakModulator(OrderLocalStep<?, ?> orderStep) {
    var source = upstreamSource(orderStep.getPreviousStep());
    return emitsMap(source) ? mapEntryKeyTieBreakModulator(source) : entryKeysModulator();
  }

  /**
   * An element group key is not {@code Comparable}, and its identifier reaches the comparator as
   * more than one class, so it goes through the same record identifier key as an element stream —
   * {@link com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKey} reads
   * the entry's key itself. A scalar key stays on plain {@code by(keys)}.
   */
  private static Admin<?, ?> mapEntryKeyTieBreakModulator(Step<?, ?> groupOrMapStep) {
    if (groupOrMapStep != null && groupKeyIsElement(groupOrMapStep)) {
      return new RecordIdSortKeyTraversal<>();
    }
    return entryKeysModulator();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Admin<?, ?> entryKeysModulator() {
    return new ColumnTraversal(Column.keys).asAdmin();
  }

  private static Step<?, ?> unfoldedGroupStep(OrderGlobalStep<?, ?> orderStep) {
    var source = upstreamSource(orderStep.getPreviousStep());
    if (source instanceof UnfoldStep) {
      var beforeUnfold = upstreamSource(source.getPreviousStep());
      if (beforeUnfold instanceof GroupStep || beforeUnfold instanceof GroupCountStep) {
        return beforeUnfold;
      }
    }
    return null;
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
    return Order.shuffle.equals(comparators.getLast().getValue1());
  }

  /**
   * Whether the last comparator already carries a total order the strategy would only duplicate.
   * Also the idempotence guard: a second application of the strategy sees its own appended
   * modulator here and adds nothing.
   */
  private static boolean hasExplicitTieBreak(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    var lastModulator = comparators.getLast().getValue0();
    if (lastModulator instanceof RecordIdSortKeyTraversal) {
      return true;
    }
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

  private static boolean endsWithRecordIdSortKey(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    return comparators.getLast().getValue0() instanceof RecordIdSortKeyTraversal;
  }

  private static boolean endsWithTokenId(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    var last = comparators.getLast().getValue0();
    return last instanceof TokenTraversal token && T.id.equals(token.getToken());
  }

  private static boolean endsWithIdentity(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    return comparators.getLast().getValue0() instanceof IdentityTraversal;
  }

  private static boolean endsWithPropertyId(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
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
   * The single upstream walk every classification and every key lookup shares: the first step that
   * is not transparent, or {@link EmptyStep} when the chain holds none. Three private copies of this
   * walk had diverged, so a shape could be classified one way and have its key modulator chosen
   * another way.
   */
  private static Step<?, ?> upstreamSource(Step<?, ?> start) {
    var current = start;
    while (!(current instanceof EmptyStep) && isTransparent(current)) {
      current = current.getPreviousStep();
    }
    return current;
  }

  /**
   * Walks upstream to the step that decides what the stream holds. The element test runs before the
   * map / flat-map test, because {@code VertexStep}, {@code EdgeVertexStep} and the other graph
   * steps <em>are</em> map or flat-map steps: with the map test first, {@code out()} and
   * {@code otherV()} fell through to the unproven case and lost the record identifier key.
   * {@code fold().unfold()} continues past the fold so a vertex fold recovers its element stream.
   * {@code select} / {@code selectOne} are not assumed to be elements.
   */
  private static StreamKind classifyFrom(Step<?, ?> start) {
    var current = upstreamSource(start);
    while (!(current instanceof EmptyStep)) {
      if (current instanceof UnfoldStep) {
        var beforeUnfold = upstreamSource(current.getPreviousStep());
        if (beforeUnfold instanceof GroupStep || beforeUnfold instanceof GroupCountStep) {
          return StreamKind.MAP_ENTRY;
        }
        if (beforeUnfold instanceof FoldStep) {
          // fold().unfold() restores the pre-fold stream — keep walking.
          current = upstreamSource(beforeUnfold.getPreviousStep());
          continue;
        }
        // index().unfold() and other unfolds → pairs / unknown payloads.
        return StreamKind.OTHER;
      }
      if (current instanceof SelectStep || current instanceof SelectOneStep) {
        return StreamKind.OTHER;
      }
      if (emitsElements(current)) {
        return StreamKind.ELEMENT;
      }
      if (emitsNonElement(current)) {
        return StreamKind.OTHER;
      }
      if (current instanceof MapStep || current instanceof FlatMapStep) {
        return childrenEmitElements(current) ? StreamKind.ELEMENT : StreamKind.OTHER;
      }
      return StreamKind.OTHER;
    }
    return StreamKind.OTHER;
  }

  /**
   * A map or flat-map step re-emits elements only when <em>every</em> branch does. Reading the first
   * branch alone called {@code coalesce(out(), constant(x))} an element stream, and the constant then
   * met a modulator that only accepts elements.
   */
  private static boolean childrenEmitElements(Step<?, ?> step) {
    if (!(step instanceof TraversalParent parent)) {
      return false;
    }
    var children = parent.getLocalChildren();
    if (children.isEmpty()) {
      return false;
    }
    for (var child : children) {
      if (classifyChild(child, step.getPreviousStep()) != StreamKind.ELEMENT) {
        return false;
      }
    }
    return true;
  }

  /**
   * What one branch of a map / flat-map step emits. A branch that is only filters, only identity, or
   * a bare lambda re-emits its parent's input, so the parent's input decides — reading the branch's
   * last step class alone called {@code map(filter(...))} over a map stream an element stream.
   */
  private static StreamKind classifyChild(Admin<?, ?> child, Step<?, ?> parentInput) {
    var end = child.getEndStep();
    if (upstreamSource(end) instanceof EmptyStep) {
      return classifyFrom(parentInput);
    }
    return classifyFrom(end);
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

  /** The steps whose output is a map, so a following local order sorts entries. */
  private static boolean emitsMap(Step<?, ?> step) {
    return step instanceof GroupStep
        || step instanceof GroupCountStep
        || step instanceof ProjectStep
        || step instanceof PropertyMapStep
        || step instanceof ElementMapStep;
  }

  private static boolean emitsNonElement(Step<?, ?> step) {
    return emitsMap(step)
        || step instanceof FoldStep
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
