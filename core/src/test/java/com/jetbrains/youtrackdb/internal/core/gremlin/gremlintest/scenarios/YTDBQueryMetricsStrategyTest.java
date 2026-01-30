package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import org.apache.commons.lang3.RandomUtils;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GremlinProcessRunner.class)
public class YTDBQueryMetricsStrategyTest extends YTDBAbstractGremlinTest {

  private static long TICKER_GRANULARITY_NANOS;
  private static long TICKER_POSSIBLE_LAG_NANOS;
  private static long TICKER_POSSIBLE_LAG_MILLIS;
  private static long TICKER_GRANULARITY_MILLIS;

  @BeforeClass
  public static void beforeClass() {
    TICKER_GRANULARITY_NANOS = YouTrackDBEnginesManager.instance().getTicker().getGranularity();
    TICKER_POSSIBLE_LAG_NANOS = (long) (TICKER_GRANULARITY_NANOS * 1.5);
    TICKER_GRANULARITY_MILLIS = TICKER_GRANULARITY_NANOS / 1_000_000;
    TICKER_POSSIBLE_LAG_MILLIS = (long) (TICKER_GRANULARITY_MILLIS * 1.5);
  }

  @Before
  public void warmup() throws InterruptedException {
    g().executeInTx(s -> s.V().hasLabel("person").toList());
    Thread.sleep(100);
  }

  @Test
  @LoadGraphWith(MODERN)
  public void testQueryMonitoringLightweight() throws InterruptedException {
    final var listener = new RememberingListener();
    for (var i = 0; i < 100; i++) {
      testQuery(QueryMonitoringMode.LIGHTWEIGHT, listener);
    }

    g.tx().open();
    g.V().hasLabel("software").toList();
    g.tx().commit();

    assertThat(listener.query).isNull();
  }

  @Test
  @LoadGraphWith(MODERN)
  public void testQueryMonitoringExact() throws InterruptedException {
    final var listener = new RememberingListener();
    for (var i = 0; i < 100; i++) {
      testQuery(QueryMonitoringMode.EXACT, listener);
    }

    g.tx().open();
    g.V().hasLabel("software").toList();
    g.tx().commit();

    assertThat(listener.query).isNull();
  }

  private void testQuery(QueryMonitoringMode mode, RememberingListener listener)
      throws InterruptedException {
    ((YTDBTransaction) g().tx())
        .withQueryMonitoringMode(mode)
        .withQueryListener(listener);

    g.tx().open();
    final var q = g.V().hasLabel("person");
    final var qStr = ((Admin<?, ?>) q).getBytecode().toString();
    final var beforeMillis = System.currentTimeMillis();
    final var beforeNanos = System.nanoTime();
    q.hasNext(); // query has started
    Thread.sleep(RandomUtils.nextInt(0, 50));
    q.toList();
    final var afterMillis = System.currentTimeMillis();
    final var afterNanos = System.nanoTime();
    g.tx().commit();

    final var duration = afterNanos - beforeNanos;

    assertThat(listener.query).isEqualTo(qStr);
    assertThat(listener.querySummary).isNull(); // since the user hasn't provided a summary
    assertThat(listener.transactionTrackingId).isNotNull();

    if (mode == QueryMonitoringMode.LIGHTWEIGHT) {
      assertThat(listener.startedAtMillis)
          .isGreaterThanOrEqualTo(beforeMillis - TICKER_POSSIBLE_LAG_MILLIS)
          .isLessThanOrEqualTo(afterMillis + TICKER_POSSIBLE_LAG_MILLIS);
      assertThat(listener.executionTimeNanos)
          .isGreaterThan(duration - TICKER_POSSIBLE_LAG_NANOS)
          .isLessThanOrEqualTo(duration + TICKER_POSSIBLE_LAG_NANOS);
    } else {

      assertThat(listener.startedAtMillis)
          .isGreaterThanOrEqualTo(beforeMillis)
          .isLessThanOrEqualTo(afterMillis);
      assertThat(listener.executionTimeNanos)
          .isLessThanOrEqualTo(duration)
          .isGreaterThan(0);
    }

    listener.reset();
  }

  static class RememberingListener implements QueryMetricsListener {

    String query;
    String querySummary;
    String transactionTrackingId;
    long startedAtMillis;
    long executionTimeNanos;

    @Override
    public void queryFinished(
        QueryDetails queryDetails,
        long startedAtMillis,
        long executionTimeNanos
    ) {

      this.startedAtMillis = startedAtMillis;
      this.executionTimeNanos = executionTimeNanos;
      this.query = queryDetails.getQuery();
      this.querySummary = queryDetails.getQuerySummary();
      this.transactionTrackingId = queryDetails.getTransactionTrackingId();
    }

    public void reset() {
      query = null;
      querySummary = null;
      transactionTrackingId = null;
      startedAtMillis = 0;
      executionTimeNanos = 0;
    }
  }

}
