package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBOrderRidTieBreakStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/**
 * Run explicitly with {@code ./mvnw -pl core -Dtest=SingleNodeIndexedOrderReproducer test}.
 * The Gremlin and bare MATCH assertions fail until single-node MATCH can stream the indexed order.
 */
public class SingleNodeIndexedOrderReproducer extends GraphBaseTest {
  private static final int ITEMS = 10_000;

  @Test
  public void sortedMatchUsesTimestampIndex() {
    seedItems();
    var plan = translatedPlan(graph.traversal());
    assertThat(plan).contains(".@rid DESC");
    assertIndexed(plan);
  }

  @Test
  public void sortedMatchWithoutRidTieBreakStillNeedsTimestampIndex() {
    seedItems();
    var plan = translatedPlan(
        graph.traversal().withoutStrategies(YTDBOrderRidTieBreakStrategy.class));
    assertThat(plan).doesNotContain(".@rid");
    assertIndexed(plan);
  }

  @Test
  public void sqlOrderByUsesTimestampIndex() {
    seedItems();
    var query = "SELECT FROM IndexedItem ORDER BY timestamp DESC";
    try (var result = session.query("EXPLAIN " + query)) {
      String plan = result.next().getProperty("executionPlanAsString");
      assertThat(plan)
          .contains("FETCH FROM INDEX VALUES DESC IndexedItem_timestamp")
          .doesNotContain("FETCH FROM CLASS IndexedItem")
          .doesNotContain("+ ORDER BY");
    }
    try (var result = session.query(query)) {
      assertThat(result.next().<Long>getProperty("timestamp")).isEqualTo(ITEMS / 2L - 1);
      assertThat(result.next().<Long>getProperty("timestamp")).isEqualTo(ITEMS / 2L - 1);
      int count = 2;
      while (result.hasNext()) {
        result.next();
        count++;
      }
      assertThat(count).isEqualTo(ITEMS);
    }
  }

  @Test
  public void bareMatchOrderByUsesTimestampIndex() {
    seedItems();
    var query = "MATCH {class: IndexedItem, as: item} RETURN item"
        + " ORDER BY item.timestamp DESC";
    try (var result = session.query(query)) {
      assertThat(result.next().getVertex("item").<Long>getProperty("timestamp"))
          .isEqualTo(ITEMS / 2L - 1);
      assertThat(result.next().getVertex("item").<Long>getProperty("timestamp"))
          .isEqualTo(ITEMS / 2L - 1);
      int count = 2;
      while (result.hasNext()) {
        result.next();
        count++;
      }
      assertThat(count).isEqualTo(ITEMS);
    }
    try (var result = session.query("EXPLAIN " + query)) {
      String plan = result.next().getProperty("executionPlanAsString");
      assertIndexed(plan);
    }
  }

  private void seedItems() {
    session.execute("CREATE CLASS IndexedItem EXTENDS V").close();
    session.execute("CREATE PROPERTY IndexedItem.timestamp LONG").close();
    session.execute("CREATE INDEX IndexedItem_timestamp ON IndexedItem (timestamp) NOTUNIQUE")
        .close();
    for (var i = 0; i < ITEMS; i++) {
      graph.addVertex(T.label, "IndexedItem", "timestamp", i / 2L);
    }
    graph.tx().commit();
  }

  private static String translatedPlan(GraphTraversalSource source) {
    var traversal = source.V().hasLabel("IndexedItem")
        .order().by("timestamp", Order.desc).asAdmin();
    traversal.applyStrategies();
    assertThat(traversal.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    var plan = ((YTDBMatchPlanStep<?, ?>) traversal.getStartStep())
        .getPlan().prettyPrint(0, 2);
    int count = 0;
    while (traversal.hasNext()) {
      traversal.next();
      count++;
    }
    assertThat(count).isEqualTo(ITEMS);
    return plan;
  }

  private static void assertIndexed(String plan) {
    assertThat(plan).as("MATCH should stream IndexedItem_timestamp instead of scanning:\n%s", plan)
        .contains("INDEX")
        .doesNotContain("FETCH FROM CLASS IndexedItem");
  }
}
