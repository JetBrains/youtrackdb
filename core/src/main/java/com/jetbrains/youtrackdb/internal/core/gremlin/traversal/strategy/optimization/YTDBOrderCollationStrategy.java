package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.CollatedSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter.YTDBHasLabelStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.parser.OrderByCollationResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.ComparatorHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.FilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeOtherVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.UnfoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.javatuples.Pair;

/**
 * Makes a native {@code order().by(propertyKey)} follow the collation the property declares, by
 * replacing the plain property modulator with a {@link CollatedSortKeyTraversal}.
 *
 * <h2>The declaration is the authority, and the default declaration changes nothing</h2>
 *
 * A property that declares no collation carries the default one, which is plain case-sensitive
 * comparison and is exactly what TinkerPop orderability already does with two strings. Such a
 * property therefore gains no modulator at all: this strategy is inert for it, and that inertness is
 * what keeps every unrelated Gremlin result unchanged. A property declared case-insensitive is a
 * product extension, and a Gremlin sort has to honour it as much as an {@code ORDER BY} does.
 *
 * <h2>It runs after the translator</h2>
 *
 * {@link #applyPrior()} names {@link GremlinToMatchStrategy}, so a shape the translator accepts has
 * already lost its {@code order()} step into the boundary step by the time this runs, and its
 * ordering is decided by the engine comparison, which reads the same declaration off the plan. A
 * declined shape keeps the step and gains the modulator here. The two arms therefore follow one
 * declaration by two routes, which is what the equivalence suites pin.
 *
 * <h2>What it does not touch</h2>
 *
 * <ul>
 *   <li>The record identifier tie-break key that {@code YTDBOrderRidTieBreakStrategy} appends: it
 *       compares identifiers, never text, so no collation applies to it.
 *   <li>A modulator that is already a {@link CollatedSortKeyTraversal}. That type is the idempotence
 *       marker: only a {@link ValueTraversal} is replaced, so a second application finds the
 *       replacement and changes nothing.
 *   <li>A modulator that is a full traversal rather than a plain property projection, such as
 *       {@code by(__.values("name"))}. The declaration governs one property of one record, and such
 *       a traversal may not be reading one.
 * </ul>
 *
 * <h2>Which classes the declaration is read from</h2>
 *
 * The same classes the MATCH planner reads it from, so the two arms cannot answer two collations for
 * one query. A {@code hasLabel} upstream of the sort names them: the translator re-types the pattern
 * node to that label, and this strategy resolves over that label's hierarchy. With no label the
 * translator leaves the node on its root class, so the resolution takes the vertex or the edge
 * hierarchy accordingly. When two classes of the resolved set declare different collations for one
 * property name, the default collation applies and nothing is replaced — the same fallback the engine
 * comparison makes for a polymorphic target.
 */
public final class YTDBOrderCollationStrategy
    extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
    implements ProviderOptimizationStrategy {

  private static final YTDBOrderCollationStrategy INSTANCE = new YTDBOrderCollationStrategy();

  private YTDBOrderCollationStrategy() {
  }

  public static YTDBOrderCollationStrategy instance() {
    return INSTANCE;
  }

  /**
   * The translator must run first: on an accepted shape there is no {@code order()} step left to
   * modulate, and the engine comparison applies the declaration instead.
   */
  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return Set.of(GremlinToMatchStrategy.class);
  }

  @Override
  public void apply(Admin<?, ?> traversal) {
    var session = YTDBStrategyUtil.resolveYtdbSession(traversal);
    if (session == null) {
      return;
    }
    var globalSteps =
        TraversalHelper.getStepsOfAssignableClassRecursively(OrderGlobalStep.class, traversal);
    // The hierarchy walk below is wasted work on a traversal that sorts by anything other than a
    // plain property, which is the common case, so the sort keys are inspected first.
    // Scope.local is out of scope for this strategy (not translated; leave TinkerPop semantics).
    if (!carriesPlainPropertyModulator(globalSteps)) {
      return;
    }
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    if (schema == null) {
      return;
    }
    for (var step : globalSteps) {
      collateGlobal((OrderGlobalStep<?, ?>) step, orderedClasses(schema, step));
    }
  }

  /** Replaces every plain property slot of {@code step} whose property declares a collation. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void collateGlobal(OrderGlobalStep<?, ?> step, List<SchemaClass> graphClasses) {
    var modulators = OrderStepModulators.modulatorsOf(step.getComparators());
    if (substituteCollatedModulators(modulators, graphClasses)) {
      OrderStepModulators.replaceGlobalModulators((OrderGlobalStep) step, modulators);
    }
  }

  /** Whether any of {@code steps} sorts by a plain property, which is all this strategy rewrites. */
  private static boolean carriesPlainPropertyModulator(List<?> steps) {
    for (var step : steps) {
      for (var slot : comparatorsOf(step)) {
        if (slot.getValue0() instanceof ValueTraversal) {
          return true;
        }
      }
    }
    return false;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparatorsOf(
      Object step) {
    return ((ComparatorHolder) step).getComparators();
  }

  /**
   * Substitutes a collated key modulator for every plain property modulator whose property carries a
   * declared collation. Returns {@code true} when at least one slot changed, which is what decides
   * whether the step is rebuilt at all.
   */
  @SuppressWarnings("rawtypes")
  private static boolean substituteCollatedModulators(
      List<Admin> modulators, List<SchemaClass> graphClasses) {
    var substituted = false;
    for (var index = 0; index < modulators.size(); index++) {
      var collated = collatedModulator(modulators.get(index), graphClasses);
      if (collated != null) {
        modulators.set(index, collated);
        substituted = true;
      }
    }
    return substituted;
  }

  /**
   * The collated replacement for {@code modulator}, or {@code null} when the modulator is not a
   * plain property projection or its property carries no declared collation.
   */
  @Nullable @SuppressWarnings({"rawtypes", "unchecked"})
  private static Admin collatedModulator(Admin modulator, List<SchemaClass> graphClasses) {
    if (!(modulator instanceof ValueTraversal<?, ?> value)) {
      return null;
    }
    var propertyKey = value.getPropertyKey();
    if (propertyKey == null || propertyKey.isEmpty()) {
      return null;
    }
    var collate = OrderByCollationResolver.declaredCollation(graphClasses, propertyKey);
    if (collate == null) {
      return null;
    }
    var replacement = new CollatedSortKeyTraversal<>(propertyKey, collate);
    var bypass = value.getBypassTraversal();
    if (bypass != null) {
      // ProductiveByStrategy installed a bypass that turns an absent property into a null row.
      // Carried over rather than dropped, so the row policy of the traversal is unchanged.
      replacement.setBypassTraversal((Admin) bypass);
    }
    return replacement;
  }

  /**
   * The classes the stream feeding {@code orderStep} is constrained to. A {@code hasLabel} between
   * the sort and the step that produced the elements names them; with none, the root hierarchy of
   * that producing step does, which is the class the translator leaves such a node on.
   */
  private static List<SchemaClass> orderedClasses(Schema schema, Step<?, ?> orderStep) {
    var labels = new LinkedHashSet<String>();
    var producer = walkToProducer(orderStep.getPreviousStep(), labels);
    if (producer == null) {
      // A label predicate this method cannot enumerate — neither an equality nor a membership one.
      // The widest set is the safe answer: it can only fall back to the default collation.
      return graphClasses(schema);
    }
    if (labels.size() == 1) {
      List<SchemaClass> classes = new ArrayList<>();
      addHierarchy(schema, labels.iterator().next(), classes);
      return classes;
    }
    // Multi-label hasLabel is translated as a class membership filter on the generic source class.
    // Keep the native path on that same source hierarchy instead of pretending it has one class.
    return rootHierarchy(schema, producer);
  }

  /**
   * Walks upstream from {@code start} to the step that produces the ordered stream, collecting the
   * label constraints met on the way. Returns that step, or {@code null} when a label predicate could
   * not be enumerated. Filters, barriers, slices, folds and nested sorts are walked through, because
   * none of them changes the class of what flows past.
   */
  @Nullable private static Step<?, ?> walkToProducer(Step<?, ?> start, Collection<String> labels) {
    var current = start;
    while (!(current instanceof EmptyStep)) {
      if (current instanceof YTDBHasLabelStep<?> hasLabel) {
        if (!addPredicateLabels(hasLabel.getPredicates(), labels)) {
          return null;
        }
      } else if (current instanceof HasContainerHolder<?, ?> holder) {
        if (!addContainerLabels(holder.getHasContainers(), labels)) {
          return null;
        }
        // A graph step both carries folded labels and produces the stream, so the walk ends on it.
        if (current instanceof GraphStep) {
          return current;
        }
      } else if (!isClassPreserving(current)) {
        return current;
      }
      current = current.getPreviousStep();
    }
    return EmptyStep.instance();
  }

  /** Whether {@code step} passes its input on without changing which class the rows belong to. */
  private static boolean isClassPreserving(Step<?, ?> step) {
    return step instanceof FilterStep
        || step instanceof IdentityStep
        || step instanceof NoOpBarrierStep
        || step instanceof OrderGlobalStep
        || step instanceof OrderLocalStep
        || step instanceof FoldStep
        || step instanceof UnfoldStep;
  }

  /** The label names of {@code predicates}, or {@code false} when one of them is not enumerable. */
  private static boolean addPredicateLabels(
      Collection<? extends P<?>> predicates, Collection<String> labels) {
    for (var predicate : predicates) {
      if (!addLabels(predicate.getBiPredicate(), predicate.getValue(), labels)) {
        return false;
      }
    }
    return true;
  }

  /** The same for the {@code ~label} containers of a has-container holder. */
  private static boolean addContainerLabels(
      Collection<HasContainer> containers, Collection<String> labels) {
    for (var container : containers) {
      if (T.label.getAccessor().equals(container.getKey())
          && !addLabels(container.getBiPredicate(), container.getValue(), labels)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reads {@code value} as one label name or as a set of them, for the two predicates {@code
   * hasLabel} builds: an equality for one argument and a membership for several. Any other predicate
   * answers {@code false}, because a negation or a text match does not enumerate a class set.
   */
  private static boolean addLabels(
      BiPredicate<?, ?> biPredicate, @Nullable Object value, Collection<String> labels) {
    if (Compare.eq.equals(biPredicate) && value instanceof String label) {
      labels.add(label);
      return true;
    }
    if (Contains.within.equals(biPredicate) && value instanceof Collection<?> values) {
      for (var candidate : values) {
        if (!(candidate instanceof String label)) {
          return false;
        }
        labels.add(label);
      }
      return true;
    }
    return false;
  }

  /**
   * The hierarchy an unlabelled stream holds: the vertex root for a vertex stream, the edge root for
   * an edge stream, and both when the producing step proves neither.
   */
  private static List<SchemaClass> rootHierarchy(Schema schema, Step<?, ?> producer) {
    List<SchemaClass> classes = new ArrayList<>();
    if (producer instanceof GraphStep<?, ?> graphStep) {
      addHierarchy(schema, rootName(graphStep.returnsVertex()), classes);
      return classes;
    }
    if (producer instanceof VertexStep<?> vertexStep) {
      addHierarchy(schema, rootName(vertexStep.returnsVertex()), classes);
      return classes;
    }
    if (producer instanceof EdgeVertexStep || producer instanceof EdgeOtherVertexStep) {
      addHierarchy(schema, SchemaClass.VERTEX_CLASS_NAME, classes);
      return classes;
    }
    return graphClasses(schema);
  }

  private static String rootName(boolean vertex) {
    return vertex ? SchemaClass.VERTEX_CLASS_NAME : SchemaClass.EDGE_CLASS_NAME;
  }

  /**
   * The vertex and edge hierarchies together — the answer for a stream whose class no step proves. A
   * graph without them yields an empty list, and then nothing is replaced.
   */
  private static List<SchemaClass> graphClasses(Schema schema) {
    List<SchemaClass> classes = new ArrayList<>();
    addHierarchy(schema, SchemaClass.VERTEX_CLASS_NAME, classes);
    addHierarchy(schema, SchemaClass.EDGE_CLASS_NAME, classes);
    return classes;
  }

  private static void addHierarchy(Schema schema, String rootName, Collection<SchemaClass> sink) {
    var root = schema.getClass(rootName);
    if (root == null) {
      return;
    }
    sink.add(root);
    sink.addAll(root.getAllSubclasses());
  }
}
