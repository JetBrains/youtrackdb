package com.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraphFactory;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphInternal;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactory;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization.YTDBGraphStepStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

/**
 * Created by Enrico Risa on 14/11/16.
 */
public abstract class GraphBaseTest extends DbTestBase {

  protected Graph graph;

  @Before
  public void setupGraphDB() {
    graph = openGraph();
  }

  protected Graph openGraph() {
    var config = getBaseConfiguration();

    return GraphFactory.open(config);
  }

  @Nonnull
  protected BaseConfiguration getBaseConfiguration() {
    var config = new BaseConfiguration();
    config.setProperty(Graph.GRAPH, YTDBGraph.class.getName());

    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME, databaseName);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER, adminUser);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_PASS, adminPassword);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_INSTANCE, youTrackDB);
    config.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_CLOSE_ON_SHUTDOWN, false);

    return config;
  }


  @After
  public void closeGraphDB() throws Exception {
    graph.close();
  }

  protected static int usedIndexes(Graph graph, GraphTraversal<?, ?> traversal) {
    YTDBGraphStepStrategy.instance().apply(traversal.asAdmin());

    var ytdbGraphStep = (YTDBGraphStep<?, ?>) traversal.asAdmin().getStartStep();
    var query = ytdbGraphStep.buildQuery();

    Assert.assertNotNull(query);
    return query.usedIndexes((YTDBGraphInternal) graph);
  }
}
