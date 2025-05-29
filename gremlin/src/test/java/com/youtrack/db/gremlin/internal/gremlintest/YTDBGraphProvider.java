package com.youtrack.db.gremlin.internal.gremlintest;

import com.google.common.collect.Sets;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraphFactory;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBElement;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphInternal;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBProperty;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBSingleThreadGraph;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBStatefulEdge;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBVertex;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBVertexProperty;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;

import java.util.*;

import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.AbstractGraphProvider;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.structure.TransactionMultiThreadedTest;
import org.apache.tinkerpop.gremlin.structure.TransactionTest;
import org.apache.tinkerpop.gremlin.structure.VertexTest;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.FeatureSupportTest.GraphFunctionalityTest;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.GraphTest;
import org.apache.tinkerpop.gremlin.structure.io.IoCustomTest;
import org.junit.AssumptionViolatedException;

public class YTDBGraphProvider extends AbstractGraphProvider {

  protected static final Map<Class<?>, List<String>> IGNORED_TESTS;

  static {
    IGNORED_TESTS = new HashMap<>();
    IGNORED_TESTS.put(
        GraphTest.class,
        List.of(
            "shouldNotMixTypesForGettingSpecificEdgesWithStringFirst",
            "shouldNotMixTypesForGettingSpecificEdgesWithEdgeFirst",
            "shouldNotMixTypesForGettingSpecificVerticesWithStringFirst",
            "shouldNotMixTypesForGettingSpecificVerticesWithVertexFirst",
            "shouldRemoveVertices"));

    // YouTrackDB can not modify schema when the transaction is on, which
    // break the tests
    IGNORED_TESTS.put(
        GraphFunctionalityTest.class, List.of("shouldSupportTransactionsIfAGraphConstructsATx"));

    // This tests become broken after gremlin 3.2.0
    IGNORED_TESTS.put(IoCustomTest.class, List.of("shouldSerializeTree"));

    IGNORED_TESTS.put(
        TransactionTest.class,
        List.of(
            "shouldExecuteWithCompetingThreads",
            "shouldAllowReferenceOfEdgeIdOutsideOfOriginalThreadManual",
            "shouldAllowReferenceOfVertexIdOutsideOfOriginalThreadManual",
            "shouldSupportTransactionIsolationCommitCheck",
            "shouldNotShareTransactionReadWriteConsumersAcrossThreads",
            "shouldNotShareTransactionCloseConsumersAcrossThreads",
            "shouldNotifyTransactionListenersInSameThreadOnlyOnCommitSuccess",
            "shouldNotifyTransactionListenersInSameThreadOnlyOnRollbackSuccess"));
    IGNORED_TESTS.put(
        TransactionMultiThreadedTest.class,
        List.of(
            "shouldCommit",
            "shouldCommitEdge",
            "shouldDeleteVertexOnCommit",
            "shouldRollbackAddedVertex"));
    IGNORED_TESTS.put(
        VertexTest.BasicVertexTest.class,
        List.of("shouldNotGetConcurrentModificationException"));
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
    configs.put("name", graphName);
    if (testMethodName.equals("shouldPersistDataOnClose")) {
      configs.put(
          YTDBGraphFactory.CONFIG_YOUTRACK_DB_PATH,
          "./target/databases/test-"
              + graphName
              + "-"
              + test.getSimpleName()
              + "-"
              + testMethodName);
    }
    return configs;
  }

  @SuppressWarnings({"rawtypes"})
  @Override
  public Set<Class> getImplementations() {
    return Sets.newHashSet(
        YTDBStatefulEdge.class,
        YTDBElement.class,
        YTDBSingleThreadGraph.class,
        YTDBProperty.class,
        YTDBVertex.class,
        YTDBVertexProperty.class);
  }

  @Override
  public void clear(Graph graph, Configuration configuration) {
    if (graph != null) {
      var g = (YTDBGraphInternal) graph;
      var f = g.getFactory();
      var ytdb = f.getYouTrackDB();

      if (ytdb.isOpen()) {
        ytdb.drop(f.getDatabaseName());
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
}
