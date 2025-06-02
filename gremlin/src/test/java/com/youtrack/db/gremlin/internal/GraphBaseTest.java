package com.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraphFactory;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphInternal;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

public abstract class GraphBaseTest extends DbTestBase {

  protected Graph graph;

  @Before
  public void setupGraphDB() {
    graph = openGraph();
  }

  protected Graph openGraph() {
    YTDBGraphFactory.registerYTDBInstance(dbPath, youTrackDB);
    var config = getBaseConfiguration();

    return GraphFactory.open(config);
  }

  @Nonnull
  protected BaseConfiguration getBaseConfiguration() {
    var config = new BaseConfiguration();
    config.setProperty(Graph.GRAPH, YTDBGraph.class.getName());

    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME, databaseName);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER, adminUser);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER_PWD, adminPassword);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_PATH, dbPath);

    return config;
  }


  @After
  public void closeGraphDB() throws Exception {
    graph.close();

    YTDBGraphFactory.unregisterYTDBInstance(dbPath);
  }

  protected static int usedIndexes(Graph graph, GraphTraversal<?, ?> traversal) {
    var ytdbGraph = (YTDBGraphInternal) graph;
    var session = ytdbGraph.getUnderlyingSession();

    return session.computeInTxInternal(transaction -> {
      YTDBGraphStepStrategy.instance().apply(traversal.asAdmin());

      var usedIndexes = 0;
      for (var step : traversal.asAdmin().getSteps()) {
        if (step instanceof YTDBGraphStep<?, ?> ytdbGraphStep) {
          var query = ytdbGraphStep.buildQuery();

          Assert.assertNotNull(query);
          usedIndexes += query.usedIndexes(ytdbGraph);
        }
      }

      return usedIndexes;
    });
  }
}
