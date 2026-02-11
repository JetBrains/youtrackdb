package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import org.apache.commons.lang3.RandomUtils;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
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
  public void testQueryMonitoringLightweight() throws Exception {
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
  public void testQueryMonitoringExact() throws Exception {
    final var listener = new RememberingListener();
    for (var i = 0; i < 100; i++) {
      testQuery(QueryMonitoringMode.EXACT, listener);
    }

    g.tx().open();
    g.V().hasLabel("software").toList();
    g.tx().commit();

    assertThat(listener.query).isNull();
  }

  @Test
  @LoadGraphWith(MODERN)
  public void testLightweightDurationExcludesDelayBeforeClose() throws Exception {
    final var listener = new RememberingListener();
    final long delayMillis = 200;

    ((YTDBTransaction) g().tx())
        .withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT)
        .withQueryListener(listener);

    g.tx().open();

    try (var q = g().V().hasLabel("person")) {
      q.toList(); // consume all results

      final long afterLastCallNanos = System.nanoTime();

      Thread.sleep(delayMillis);

      // close() is called here by try-with-resources;
      // the reported duration should NOT include the sleep
      assertThat(System.nanoTime() - afterLastCallNanos)
          .as("sanity check: sleep actually elapsed")
          .isGreaterThanOrEqualTo(delayMillis * 1_000_000 / 2);
    }
    g.tx().commit();

    assertThat(listener.executionTimeNanos)
        .isLessThan(delayMillis * 1_000_000);
  }

  @Test
  @LoadGraphWith(MODERN)
  public void testListenerNotNotifiedWhenTraversalNeverIterated() throws Exception {
    final var listener = new RememberingListener();

    ((YTDBTransaction) g().tx())
        .withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT)
        .withQueryListener(listener);

    g.tx().open();

    //noinspection EmptyTryBlock
    try (var ignored = g().V().hasLabel("person")) {
      // never call hasNext/next
    }
    g.tx().commit();

    assertThat(listener.query).isNull();
  }

  private void testQuery(QueryMonitoringMode mode, RememberingListener listener) throws Exception {
    final var rand = RandomUtils.insecure();
    ((YTDBTransaction) g().tx())
        .withQueryMonitoringMode(mode)
        .withQueryListener(listener);

    final var summary = "test_" + rand.randomInt(0, 1000);
    g.tx().open();

    final String qStr;
    final long beforeMillis;
    final long beforeNanos;
    final long afterMillis;
    final long afterNanos;
    final var withSummary = rand.randomBoolean();

    @SuppressWarnings("resource") var gs = g();
    if (withSummary) {
      gs = gs.with(YTDBQueryConfigParam.querySummary, summary);
    }

    try (var q = gs.V().hasLabel("person")) {

      qStr = q.getBytecode().toString();
      beforeMillis = System.currentTimeMillis();
      beforeNanos = System.nanoTime();

      //noinspection ResultOfMethodCallIgnored
      q.hasNext(); // query has started

      Thread.sleep(rand.randomInt(0, 50));
      q.toList(); // query has finished

      afterMillis = System.currentTimeMillis();
      afterNanos = System.nanoTime();
    }
    g.tx().commit();

    final var duration = afterNanos - beforeNanos;

    assertThat(listener.query).isEqualTo(qStr);
    if (withSummary) {
      assertThat(listener.querySummary).isEqualTo(summary);
    } else {
      assertThat(listener.querySummary).isNull();
    }
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
