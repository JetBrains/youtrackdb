package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;

import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GremlinProcessRunner.class)
public class YTDBQueryMetricsStrategyTest extends YTDBAbstractGremlinTest {

  @Test
  @LoadGraphWith(MODERN)
  public void testMetricsSimple() {
    final var tx = ((YTDBTransaction) g().tx())
        .withQueryListener(QueryMonitoringMode.LIGHTWEIGHT, "abc", new QueryMetricsListener() {
          @Override
          public void queryFinished(QueryDetails queryDetails, long startedAtMillis,
              long executionTimeNanos) {
            System.out.println(
                "Query: " + queryDetails.getQuery() + " took " + executionTimeNanos + "ns");
          }
        });

    g.V().hasLabel("person").toList();
    g.V().hasLabel("software").toList();
    g.tx().commit();
  }
}
