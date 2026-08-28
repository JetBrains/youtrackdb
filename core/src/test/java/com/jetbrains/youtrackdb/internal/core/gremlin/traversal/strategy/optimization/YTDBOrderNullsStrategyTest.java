package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.After;
import org.junit.Test;

/**
 * {@link YTDBOrderNullsStrategy} applies {@link GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT}
 * to native Gremlin {@code order()} when the effective default is {@link
 * OrderByNullsDefault#NULLS_LARGEST}. {@link OrderByNullsDefault#NULLS_SMALLEST} is a no-op
 * because TinkerPop's comparator already matches it.
 */
public class YTDBOrderNullsStrategyTest extends GraphBaseTest {

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
}
