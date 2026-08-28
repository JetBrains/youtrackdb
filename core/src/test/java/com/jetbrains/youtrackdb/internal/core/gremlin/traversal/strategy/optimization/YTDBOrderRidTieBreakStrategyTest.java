package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
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

  private static final Field COMPARATORS_FIELD;

  static {
    try {
      COMPARATORS_FIELD = OrderGlobalStep.class.getDeclaredField("comparators");
      COMPARATORS_FIELD.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** Property sort steps gain a trailing {@code T.id ASC} modulator. */
  @Test
  public void apply_appendsIdComparatorAfterPropertySort() throws IllegalAccessException {
    var admin = graph.traversal().V().order().by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var orderStep = admin.getSteps().stream()
        .filter(OrderGlobalStep.class::isInstance)
        .map(OrderGlobalStep.class::cast)
        .findFirst()
        .orElseThrow();
    var comparators = comparators(orderStep);
    assertThat(comparators).hasSize(2);
    assertThat(comparators.get(1).getValue0()).isInstanceOf(TokenTraversal.class);
    assertThat(((TokenTraversal) comparators.get(1).getValue0()).getToken()).isEqualTo(T.id);
    assertThat(comparators.get(1).getValue1()).isEqualTo(Order.asc);
  }

  /** Bare {@code order()} with no comparators yet must stay untouched. */
  @Test
  public void apply_leavesBareOrderUntouched() throws IllegalAccessException {
    var admin = graph.traversal().V().order().asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var orderStep = admin.getSteps().stream()
        .filter(OrderGlobalStep.class::isInstance)
        .map(OrderGlobalStep.class::cast)
        .findFirst()
        .orElseThrow();
    assertThat(comparators(orderStep)).isEmpty();
  }

  /** {@code order().by(T.id)} must not gain a duplicate id modulator. */
  @Test
  public void apply_leavesExplicitIdSortUntouched() throws IllegalAccessException {
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
  public void apply_leavesExplicitIdPropertySortUntouched() throws IllegalAccessException {
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
  private static List<Pair<Admin, Comparator>> comparators(OrderGlobalStep<?, ?> step)
      throws IllegalAccessException {
    return (List<Pair<Admin, Comparator>>) COMPARATORS_FIELD.get(step);
  }
}
