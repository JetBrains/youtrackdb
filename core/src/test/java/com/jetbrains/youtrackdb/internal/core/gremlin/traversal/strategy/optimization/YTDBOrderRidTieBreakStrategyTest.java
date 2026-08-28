package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.Comparator;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TokenTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.javatuples.Pair;
import org.junit.Test;

/**
 * {@link YTDBOrderRidTieBreakStrategy} appends {@code by(T.id, asc)} to native {@code order()}
 * steps so Gremlin tie groups sort by RID like YQL execution.
 */
public class YTDBOrderRidTieBreakStrategyTest extends GraphBaseTest {

  /** Property sort steps gain a trailing {@code T.id ASC} modulator. */
  @Test
  public void apply_appendsIdComparatorAfterPropertySort() {
    var admin = graph.traversal().V().order().by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var orderStep = admin.getSteps().stream()
        .filter(OrderGlobalStep.class::isInstance)
        .map(OrderGlobalStep.class::cast)
        .findFirst()
        .orElseThrow();
    assertThat(comparators(orderStep)).hasSize(2);
    var idComparator = comparators(orderStep).get(1);
    assertThat(idComparator.getValue0()).isInstanceOf(TokenTraversal.class);
    assertThat(((TokenTraversal) idComparator.getValue0()).getToken()).isEqualTo(T.id);
    assertThat(idComparator.getValue1()).isEqualTo(Order.asc);
  }

  /** Bare {@code order()} keeps its default identity comparator and gains no {@code T.id}. */
  @Test
  public void apply_leavesBareOrderUntouched() {
    var admin = graph.traversal().V().order().asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var orderStep = admin.getSteps().stream()
        .filter(OrderGlobalStep.class::isInstance)
        .map(OrderGlobalStep.class::cast)
        .findFirst()
        .orElseThrow();
    assertThat(comparators(orderStep)).hasSize(1);
  }

  /** {@code order().by(T.id)} must not gain a duplicate id modulator. */
  @Test
  public void apply_leavesExplicitIdSortUntouched() {
    var admin = graph.traversal().V().order().by(T.id).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var orderStep = admin.getSteps().stream()
        .filter(OrderGlobalStep.class::isInstance)
        .map(OrderGlobalStep.class::cast)
        .findFirst()
        .orElseThrow();
    assertThat(comparators(orderStep)).hasSize(1);
  }

  /** LDBC-style {@code by("id", asc)} must not gain a duplicate {@code T.id} modulator. */
  @Test
  public void apply_leavesExplicitIdPropertySortUntouched() {
    var admin = graph.traversal().V().order().by("creationDate", Order.desc).by("id", Order.asc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var orderStep = admin.getSteps().stream()
        .filter(OrderGlobalStep.class::isInstance)
        .map(OrderGlobalStep.class::cast)
        .findFirst()
        .orElseThrow();
    assertThat(comparators(orderStep)).hasSize(2);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static
      List<Pair<org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin, Comparator>>
      comparators(OrderGlobalStep step) {
    return step.getComparators();
  }
}
