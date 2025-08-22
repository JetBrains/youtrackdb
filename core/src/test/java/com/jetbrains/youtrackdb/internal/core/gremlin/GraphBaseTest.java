package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
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

    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_PATH, dbPath);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME, databaseName);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER, adminUser);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER_ROLE, "admin");
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER_PWD, adminPassword);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_TYPE, dbType);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_CREATE_IF_NOT_EXISTS, true);

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
  }

  protected static int usedIndexes(DatabaseSessionEmbedded session,
      GraphTraversal<?, ?> traversal) {
    YTDBGraphStepStrategy.instance().apply(traversal.asAdmin());

    return session.computeInTxInternal(transaction -> {
      var usedIndexes = 0;
      for (var step : traversal.asAdmin().getSteps()) {
        if (step instanceof YTDBGraphStep<?, ?> ytdbGraphStep) {
          var query = ytdbGraphStep.buildQuery(session);

          Assert.assertNotNull(query);
          usedIndexes += query.usedIndexes(session);
        }
      }

      return usedIndexes;
    });
  }
}
