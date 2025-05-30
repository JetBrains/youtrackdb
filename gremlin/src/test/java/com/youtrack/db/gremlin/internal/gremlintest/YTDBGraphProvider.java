package com.youtrack.db.gremlin.internal.gremlintest;

import com.google.common.collect.Sets;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraphFactory;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBElement;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphImpl;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBProperty;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBStatefulEdge;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBVertex;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBVertexProperty;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.AbstractGraphProvider;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.TransactionTest;
import org.junit.AssumptionViolatedException;


public class YTDBGraphProvider extends AbstractGraphProvider {
  protected static final Map<Class<?>, List<String>> IGNORED_TESTS;

  static {
    IGNORED_TESTS = new HashMap<>();
    IGNORED_TESTS.put(
        TransactionTest.class,
        List.of(
            "shouldExecuteWithCompetingThreads"));
  }

  @Override
  public Map<String, Object> getBaseConfiguration(
      String graphName,
      Class<?> test,
      String testMethodName,
      LoadGraphWith.GraphData loadGraphWith) {
    if (IGNORED_TESTS.containsKey(test) && IGNORED_TESTS.get(test).contains(testMethodName)) {
      throw new AssumptionViolatedException("We allow mixed ids");
    }

    if (testMethodName.contains("graphson-v1-embedded")) {
      throw new AssumptionViolatedException("graphson-v1-embedded support not implemented");
    }

    var configs = new HashMap<String, Object>();
    configs.put(Graph.GRAPH, YTDBGraph.class.getName());

    var dbType = calculateDbType();
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());

    configs.put(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME, graphName);
    configs.put(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER, "adminuser");
    configs.put(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER_PWD, "adminpwd");
    configs.put(YTDBGraphFactory.CONFIG_YOUTRACK_DB_PATH, directoryPath);
    configs.put(YTDBGraphFactory.CONFIG_YOUTRACK_DB_CREATE_IF_NOT_EXISTS, true);
    configs.put(YTDBGraphFactory.CONFIG_YOUTRACK_DB_TYPE, dbType.name());
    configs.put(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER_ROLE, "admin");

    return configs;
  }

  @SuppressWarnings({"rawtypes"})
  @Override
  public Set<Class> getImplementations() {
    return Sets.newHashSet(
        YTDBStatefulEdge.class,
        YTDBElement.class,
        YTDBGraphImpl.class,
        YTDBProperty.class,
        YTDBVertex.class,
        YTDBVertexProperty.class);
  }

  @Override
  public void clear(Graph graph, Configuration configuration) {
    if (graph != null) {
      try {
        graph.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      var ytdb = YTDBGraphFactory.getYTDBInstance(
          configuration.getString(YTDBGraphFactory.CONFIG_YOUTRACK_DB_PATH));
      var dbName = configuration.getString(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME);
      if (ytdb != null && ytdb.exists(dbName)) {
        ytdb.drop(dbName);
        YTDBGraphFactory.closeYTDBInstance(configuration);
      }
    }
  }

  @Override
  public RID convertId(Object id, Class<? extends Element> c) {
    if (id instanceof RID rid) {
      return rid;
    }

    if (id instanceof Number) {
      var numericId = ((Number) id).longValue();
      return new RecordId(new Random(numericId).nextInt(32767), numericId);
    }

    if (id instanceof String stringId) {
      try {
        return new RecordId(stringId);
      } catch (IllegalArgumentException e) {
        //skip
      }

      int numericId;
      try {
        numericId = Integer.parseInt(stringId);
      } catch (NumberFormatException e) {
        return new MockRID("Invalid id: " + id + " for " + c);
      }

      return new RecordId(numericId, numericId);
    }

    return new MockRID("Invalid id: " + id + " for " + c);
  }

  private static DatabaseType calculateDbType() {
    final var testConfig =
        System.getProperty("youtrackdb.test.env", DatabaseType.MEMORY.name().toLowerCase());

    if ("ci".equals(testConfig) || "release".equals(testConfig)) {
      return DatabaseType.DISK;
    }

    return DatabaseType.MEMORY;
  }
}
