package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.CollatedSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.parser.OrderByCollationResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.ComparatorHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
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
 * A strategy cannot know which class the ordered stream holds, so the declaration is read across the
 * vertex and edge hierarchies, which are the only classes a Gremlin element sort can see. When two
 * of those classes declare different collations for one property name, the default collation applies
 * and nothing is replaced — the same fallback the engine comparison makes for a polymorphic target.
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
    var localSteps =
        TraversalHelper.getStepsOfAssignableClassRecursively(OrderLocalStep.class, traversal);
    // The hierarchy walk below is wasted work on a traversal that sorts by anything other than a
    // plain property, which is the common case, so the sort keys are inspected first.
    if (!carriesPlainPropertyModulator(globalSteps) && !carriesPlainPropertyModulator(localSteps)) {
      return;
    }
    var graphClasses = graphClasses(session);
    if (graphClasses.isEmpty()) {
      return;
    }
    for (var step : globalSteps) {
      collateGlobal((OrderGlobalStep<?, ?>) step, graphClasses);
    }
    for (var step : localSteps) {
      collateLocal((OrderLocalStep<?, ?>) step, graphClasses);
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

  /** The {@link #collateGlobal} sibling for a local {@code order(local)} step. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void collateLocal(OrderLocalStep<?, ?> step, List<SchemaClass> graphClasses) {
    var modulators = OrderStepModulators.modulatorsOf(step.getComparators());
    if (substituteCollatedModulators(modulators, graphClasses)) {
      OrderStepModulators.replaceLocalModulators((OrderLocalStep) step, modulators);
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
   * The vertex and edge hierarchies, which are every class a Gremlin element sort can see. A graph
   * without them yields an empty list, and then nothing is replaced.
   */
  private static List<SchemaClass> graphClasses(DatabaseSessionEmbedded session) {
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    if (schema == null) {
      return List.of();
    }
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
