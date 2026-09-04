package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import java.util.Comparator;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.javatuples.Pair;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * {@link YTDBOrderNullsStrategy} applies {@link GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT}
 * to native Gremlin {@code order()} when the effective default is {@link
 * OrderByNullsDefault#NULLS_LARGEST}. {@link OrderByNullsDefault#NULLS_SMALLEST} is a no-op
 * because TinkerPop's comparator already matches it.
 *
 * <p>Marked {@code @Category(SequentialTest)} because it mutates the process-wide
 * {@code QUERY_ORDER_BY_NULLS_DEFAULT} global. The default surefire execution runs four test
 * classes in parallel in one virtual machine, so the mutation would leak between classes.
 */
@Category(SequentialTest.class)
public class YTDBOrderNullsStrategyTest extends GraphBaseTest {

  /**
   * Runs after the translator and after productive-order rewrite so wrapping sees missing keys as
   * nulls on the native path.
   */
  @Test
  public void applyPrior_waitsForTranslatorAndProductiveOrder() {
    assertThat(YTDBOrderNullsStrategy.instance().applyPrior())
        .containsExactlyInAnyOrder(
            GremlinToMatchStrategy.class, YTDBProductiveOrderByStrategy.class);
  }

  @After
  public void restoreDefault() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.resetToDefault();
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    tx.getDatabaseSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT, null);
  }

  /**
   * Storage-local {@code NULLS_LARGEST} puts null sort keys last for ascending native {@code
   * order().by(age)}. Productive rewrite keeps vertices without {@code age} in the sort stream.
   */
  @Test
  public void storageNullsLargestAscPutsNullAgeVerticesLast() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.addVertex(T.label, "Person", "name", "Nemo");
    graph.tx().commit();

    setStorageNullsLargest();

    var names = graph.traversal().V().order().by("age").values("name").toList();

    assertThat(names.subList(0, 2)).containsExactly("Bob", "Alice");
    assertThat(names.subList(2, 4)).containsExactlyInAnyOrder("Nobody", "Nemo");
  }

  /**
   * Storage-local {@code NULLS_LARGEST} puts null sort keys first for descending native {@code
   * order().by(age, desc)}.
   */
  @Test
  public void storageNullsLargestDescPutsNullAgeVerticesFirst() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.addVertex(T.label, "Person", "name", "Nemo");
    graph.tx().commit();

    setStorageNullsLargest();

    var names = graph.traversal().V().order().by("age", Order.desc).values("name").toList();

    assertThat(names.subList(0, 2)).containsExactlyInAnyOrder("Nobody", "Nemo");
    assertThat(names.subList(2, 4)).containsExactly("Alice", "Bob");
  }

  /**
   * With the default {@code NULLS_SMALLEST}, {@link YTDBOrderNullsStrategy#apply} returns before
   * rebuilding any order step.
   */
  @Test
  public void applyIsNoOpWhenNullsSmallestDefault() {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();

    var admin = graph.traversal().V().order().by("age").asAdmin();
    var orderBefore =
        TraversalHelper.getStepsOfAssignableClass(OrderGlobalStep.class, admin).getFirst();

    YTDBOrderNullsStrategy.instance().apply(admin);

    var orderAfter =
        TraversalHelper.getStepsOfAssignableClass(OrderGlobalStep.class, admin).getFirst();
    assertThat(orderAfter).isSameAs(orderBefore);
    assertThat(comparatorAt(orderAfter, 0)).isSameAs(Order.asc);
  }

  /**
   * Direct {@code apply} on a native {@code order()} traversal rebuilds framework comparators when
   * storage uses {@code NULLS_LARGEST}. End-to-end runs often translate {@code order().by(...)} to
   * MATCH, so this pins the strategy body itself.
   */
  @Test
  public void applyWrapsNativeOrderGlobalStepWhenNullsLargest() {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    setStorageNullsLargest();

    var admin = graph.traversal().V().order().by("age").by("name", Order.desc).asAdmin();
    YTDBOrderNullsStrategy.instance().apply(admin);

    var orderStep =
        TraversalHelper.getStepsOfAssignableClass(OrderGlobalStep.class, admin).getFirst();
    var ascWrapped = comparatorAt(orderStep, 0);
    assertThat(ascWrapped).isNotSameAs(Order.asc);
    assertThat(ascWrapped.compare(null, 1)).isPositive();
    assertThat(ascWrapped.compare(null, null)).isZero();
    assertThat(ascWrapped.compare(1, 2)).isNegative();

    var descWrapped = comparatorAt(orderStep, 1);
    assertThat(descWrapped).isNotSameAs(Order.desc);
    assertThat(descWrapped.compare(null, "a")).isNegative();
  }

  /**
   * Bare {@code order()} synthesizes identity+asc. Under {@code NULLS_LARGEST} that asc comparator
   * is wrapped the same way as an explicit {@code by(..., asc)}.
   */
  @Test
  public void applyWrapsBareOrderUnderNullsLargest() {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    setStorageNullsLargest();

    var admin = graph.traversal().V().order().asAdmin();
    YTDBOrderNullsStrategy.instance().apply(admin);

    var orderStep =
        TraversalHelper.getStepsOfAssignableClass(OrderGlobalStep.class, admin).getFirst();
    var wrapped = comparatorAt(orderStep, 0);
    assertThat(wrapped).isNotSameAs(Order.asc);
    assertThat(wrapped.compare(null, 1)).isPositive();
  }

  /**
   * {@code order(Scope.local)} uses {@link OrderLocalStep}. Under {@code NULLS_LARGEST} its
   * framework comparators are rebuilt the same way as the global step.
   */
  @Test
  public void applyWrapsOrderLocalStepUnderNullsLargest() {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    setStorageNullsLargest();

    var admin = graph.traversal().V().fold().order(Scope.local).by("age").asAdmin();
    YTDBOrderNullsStrategy.instance().apply(admin);

    var orderStep =
        TraversalHelper.getStepsOfAssignableClass(OrderLocalStep.class, admin).getFirst();
    var wrapped = comparatorAt(orderStep, 0);
    assertThat(wrapped).isNotSameAs(Order.asc);
    assertThat(wrapped.compare(null, 1)).isPositive();
  }

  /**
   * The strategy does not walk nested traversals. Applying it to the parent leaves a child {@code
   * order()} untouched; the framework visits that child on its own pass.
   */
  @Test
  public void applyDoesNotRecurseIntoNestedOrder() {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    setStorageNullsLargest();

    var admin = graph.traversal().V().map(__.order().by("age")).asAdmin();
    YTDBOrderNullsStrategy.instance().apply(admin);

    var nestedOrder =
        TraversalHelper.getStepsOfAssignableClassRecursively(OrderGlobalStep.class, admin)
            .getFirst();
    assertThat(comparatorAt(nestedOrder, 0)).isSameAs(Order.asc);
  }

  /**
   * {@link Order#shuffle} is left alone. A step that mixes shuffle with asc wraps only the asc
   * comparator.
   */
  @Test
  public void applySkipsShuffleComparator() {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    setStorageNullsLargest();

    var admin = graph.traversal().V().order().by(Order.shuffle).by("age").asAdmin();
    YTDBOrderNullsStrategy.instance().apply(admin);

    var orderStep =
        TraversalHelper.getStepsOfAssignableClass(OrderGlobalStep.class, admin).getFirst();
    assertThat(comparatorAt(orderStep, 0)).isSameAs(Order.shuffle);
    assertThat(comparatorAt(orderStep, 1)).isNotSameAs(Order.asc);
  }

  /**
   * A second {@code apply} does not wrap an already-wrapped comparator again. Only framework {@code
   * asc}/{@code desc} constants are replaced.
   */
  @Test
  public void applyIsIdempotentUnderNullsLargest() {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    setStorageNullsLargest();

    var admin = graph.traversal().V().order().by("age").asAdmin();
    YTDBOrderNullsStrategy.instance().apply(admin);
    var firstWrap =
        comparatorAt(
            TraversalHelper.getStepsOfAssignableClass(OrderGlobalStep.class, admin).getFirst(), 0);

    YTDBOrderNullsStrategy.instance().apply(admin);
    var second =
        comparatorAt(
            TraversalHelper.getStepsOfAssignableClass(OrderGlobalStep.class, admin).getFirst(), 0);
    assertThat(second).isSameAs(firstWrap);
  }

  /** Caller-supplied comparators keep their own null handling and are not replaced. */
  @Test
  public void applyLeavesCallerComparatorAlone() {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    setStorageNullsLargest();

    Comparator<Integer> caller = Integer::compareTo;
    var admin = graph.traversal().V().order().by("age", caller).asAdmin();
    YTDBOrderNullsStrategy.instance().apply(admin);

    var orderStep =
        TraversalHelper.getStepsOfAssignableClass(OrderGlobalStep.class, admin).getFirst();
    assertThat(comparatorAt(orderStep, 0)).isSameAs(caller);
  }

  private void setStorageNullsLargest() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    tx.getDatabaseSession()
        .getConfiguration()
        .setValue(
            GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT, OrderByNullsDefault.NULLS_LARGEST);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Comparator comparatorAt(OrderGlobalStep<?, ?> step, int index) {
    List<Pair> pairs = (List) step.getComparators();
    return (Comparator) pairs.get(index).getValue1();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Comparator comparatorAt(OrderLocalStep<?, ?> step, int index) {
    List<Pair> pairs = (List) step.getComparators();
    return (Comparator) pairs.get(index).getValue1();
  }
}
