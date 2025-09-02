package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest;

import com.google.common.collect.Sets;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.ConfigurationParameters;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBPropertyImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexPropertyImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YouTrackDBFeatures.YTDBFeatures;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.AbstractGraphProvider;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Graph.Features;
import org.junit.AssumptionViolatedException;


public class YTDBGraphProvider extends AbstractGraphProvider {
  @Override
  public Map<String, Object> getBaseConfiguration(
      String graphName,
      Class<?> test,
      String testMethodName,
      LoadGraphWith.GraphData loadGraphWith) {
    if (testMethodName.contains("graphson-v1-embedded")) {
      throw new AssumptionViolatedException("graphson-v1-embedded support not implemented");
    }

    var configs = new HashMap<String, Object>();
    configs.put(Graph.GRAPH, YTDBGraph.class.getName());

    var dbType = calculateDbType();
    var directoryPath = getWorkingDirectory();

    configs.put(ConfigurationParameters.CONFIG_DB_NAME, graphName);
    configs.put(ConfigurationParameters.CONFIG_USER_NAME, "adminuser");
    configs.put(ConfigurationParameters.CONFIG_USER_PWD, "adminpwd");
    configs.put(ConfigurationParameters.CONFIG_DB_PATH, directoryPath);
    configs.put(ConfigurationParameters.CONFIG_CREATE_IF_NOT_EXISTS, true);
    configs.put(ConfigurationParameters.CONFIG_DB_TYPE, dbType.name());
    configs.put(ConfigurationParameters.CONFIG_USER_ROLE, "admin");

    return configs;
  }

  @SuppressWarnings({"rawtypes"})
  @Override
  public Set<Class> getImplementations() {
    return Sets.newHashSet(
        YTDBElementImpl.class,
        YTDBStatefulEdgeImpl.class,
        YTDBGraphEmbedded.class,
        YTDBPropertyImpl.class,
        YTDBVertexImpl.class,
        YTDBVertexPropertyImpl.class);
  }
  @Override
  public Optional<Features> getStaticFeatures() {
    return Optional.of(YTDBFeatures.INSTANCE);
  }

  @Override
  public void clear(Graph graph, Configuration configuration) {

    if (graph != null) {
      try {
        graph.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    if (configuration != null) {
      var ytdb = YTDBGraphFactory.getYTDBInstance(
          configuration.getString(ConfigurationParameters.CONFIG_DB_PATH));
      var dbName = configuration.getString(ConfigurationParameters.CONFIG_DB_NAME);

      if (ytdb != null && ytdb.exists(dbName)) {
        ytdb.drop(dbName);
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
