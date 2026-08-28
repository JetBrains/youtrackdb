package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.javatuples.Pair;
import org.junit.After;
import org.junit.Test;

/**
 * {@link YTDBOrderNullsStrategy} applies {@link GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT}
 * to native Gremlin {@code order()} when the effective default is {@link
 * OrderByNullsDefault#NULLS_LARGEST}. {@link OrderByNullsDefault#NULLS_SMALLEST} is a no-op
 * because TinkerPop's comparator already matches it.
 */
public class YTDBOrderNullsStrategyTest extends GraphBaseTest {

  private static final Field COMPARATORS_FIELD;

  static {
    try {
      COMPARATORS_FIELD = OrderGlobalStep.class.getDeclaredField("comparators");
      COMPARATORS_FIELD.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** Runs after the translator so wrapping targets only native-decline {@code order()} steps. */
  @Test
  public void applyPrior_waitsForGremlinToMatchStrategy() {
    assertThat(YTDBOrderNullsStrategy.instance().applyPrior())
        .containsExactly(GremlinToMatchStrategy.class);
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
   * order().by(age)}. {@code ProductiveByStrategy} keeps vertices without {@code age} in the sort
   * stream so null placement is observable.
   */
  @Test
  public void storageNullsLargestAscPutsNullAgeVerticesLast() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.addVertex(T.label, "Person", "name", "Nemo");
    graph.tx().commit();

    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    tx.getDatabaseSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT,
            OrderByNullsDefault.NULLS_LARGEST);

    var names =
        graph
            .traversal()
            .withStrategies(ProductiveByStrategy.instance())
            .V()
            .order()
            .by("age")
            .values("name")
            .toList();

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

    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    tx.getDatabaseSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT,
            OrderByNullsDefault.NULLS_LARGEST);

    var names =
        graph
            .traversal()
            .withStrategies(ProductiveByStrategy.instance())
            .V()
            .order()
            .by("age", Order.desc)
            .values("name")
            .toList();

    assertThat(names.subList(0, 2)).containsExactlyInAnyOrder("Nobody", "Nemo");
    assertThat(names.subList(2, 4)).containsExactly("Alice", "Bob");
  }

  /**
   * With the default {@code NULLS_SMALLEST}, {@link YTDBOrderNullsStrategy#apply} returns before
   * touching {@code OrderGlobalStep} comparators.
   */
  @Test
  public void applyIsNoOpWhenNullsSmallestDefault() throws Exception {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();

    var admin = graph.traversal().V().order().by("age").asAdmin();
    var comparatorBefore = firstOrderComparator(admin);

    YTDBOrderNullsStrategy.instance().apply(admin);

    assertThat(firstOrderComparator(admin)).isSameAs(comparatorBefore);
  }

  /**
   * Direct {@code apply} on a native {@code order()} traversal wraps comparators when storage uses
   * {@code NULLS_LARGEST}. End-to-end tests often translate {@code order().by(...)} to MATCH, so
   * this pins the strategy body itself.
   */
  @Test
  public void applyWrapsNativeOrderGlobalStepWhenNullsLargest() throws Exception {
    graph.addVertex(T.label, "Person", "age", 1);
    graph.tx().commit();

    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    tx.getDatabaseSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT,
            OrderByNullsDefault.NULLS_LARGEST);

    var admin = graph.traversal().V().order().by("age").by("name", Order.desc).asAdmin();
    YTDBOrderNullsStrategy.instance().apply(admin);

    var ascWrapped = firstOrderComparator(admin);
    assertThat(ascWrapped).isNotSameAs(Order.asc);
    assertThat(ascWrapped.compare(null, 1)).isPositive();
    assertThat(ascWrapped.compare(null, null)).isZero();
    assertThat(ascWrapped.compare(1, 2)).isNegative();

    var orderStep =
        TraversalHelper.getStepsOfAssignableClassRecursively(OrderGlobalStep.class, admin)
            .getFirst();
    @SuppressWarnings("unchecked")
    var comparators = (List<Pair<?, Comparator>>) COMPARATORS_FIELD.get(orderStep);
    var descWrapped = comparators.get(1).getValue1();
    assertThat(descWrapped.compare(null, "a")).isNegative();
    assertThat(descWrapped).isNotSameAs(Order.shuffle);
  }

  @SuppressWarnings("unchecked")
  private static Comparator<Object> firstOrderComparator(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> admin)
      throws Exception {
    var orderStep =
        TraversalHelper.getStepsOfAssignableClassRecursively(OrderGlobalStep.class, admin)
            .getFirst();
    var comparators = (List<Pair<?, Comparator>>) COMPARATORS_FIELD.get(orderStep);
    return comparators.getFirst().getValue1();
  }
}
