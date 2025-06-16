package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.YTDBVertex;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphCountStrategy;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphIoStepStrategy;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphMatchStepStrategy;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.Io;

public abstract class YTDBSingleThreadGraphContainer implements YTDBGraphInternal {
  static {
    TraversalStrategies.GlobalCache.registerStrategies(
        YTDBGraphImpl.class,
        TraversalStrategies.GlobalCache.getStrategies(Graph.class)
            .clone()
            .addStrategies(
                YTDBGraphStepStrategy.instance(),
                YTDBGraphCountStrategy.instance(),
                YTDBGraphMatchStepStrategy.instance(),
                YTDBGraphIoStepStrategy.instance()));
  }

  protected final ThreadLocal<YTDBSingleThreadGraph> activeGraph = new ThreadLocal<>();
  private final Set<YTDBSingleThreadGraph> graphs = ConcurrentHashMap.newKeySet();

  protected final Configuration config;
  private final Transaction tx;

  public YTDBSingleThreadGraphContainer(Configuration config) {
    this.config = config;
    tx = new YTDBMultiThreadGraphTransaction(this);
  }

  @Override
  public YTDBVertex addVertex(Object... keyValues) {
    return this.currentSingleThreadGraph().addVertex(keyValues);
  }

  @Override
  public YTDBVertex addVertex(String label) {
    return this.currentSingleThreadGraph().addVertex(label);
  }

  @Override
  public <C extends GraphComputer> C compute(Class<C> graphComputerClass)
      throws IllegalArgumentException {
    return this.currentSingleThreadGraph().compute(graphComputerClass);
  }

  @Override
  public GraphComputer compute() throws IllegalArgumentException {
    return this.currentSingleThreadGraph().compute();
  }

  @Override
  public Iterator<Vertex> vertices(Object... vertexIds) {
    return this.currentSingleThreadGraph().vertices(vertexIds);
  }

  @Override
  public Iterator<Edge> edges(Object... edgeIds) {
    return this.currentSingleThreadGraph().edges(edgeIds);
  }

  @Override
  public Transaction tx() {
    return tx;
  }

  @Override
  public void close() throws Exception {
    closeGraphs();
  }

  @Override
  public Variables variables() {
    return this.currentSingleThreadGraph().variables();
  }

  @Override
  public Features features() {
    return YouTrackDBFeatures.YTDBFeatures.INSTANCE;
  }

  @Override
  public Configuration configuration() {
    return config;
  }

  protected void closeGraphs() throws Exception {
    var currentGraph = activeGraph.get();
    if (currentGraph != null) {
      currentGraph.close();
    }

    var pendingGraphs = new ArrayList<>(graphs);
    for (var graph : pendingGraphs) {
      graph.getUnderlyingDatabaseSession().activateOnCurrentThread();
      graph.close();
    }

    assert graphs.isEmpty();
  }

  @Override
  public DatabaseSessionEmbedded getUnderlyingDatabaseSession() {
    return this.currentSingleThreadGraph().getUnderlyingDatabaseSession();
  }

  @Override
  public YTDBElementFactory elementFactory() {
    return this.currentSingleThreadGraph().elementFactory();
  }

  @Override
  public boolean isOpen() {
    var currentGraph = activeGraph.get();
    if (currentGraph == null) {
      return false;
    }

    return !currentGraph.getUnderlyingDatabaseSession().isClosed();
  }

  public YTDBSingleThreadGraph currentSingleThreadGraph() {
    var graph = activeGraph.get();

    if (graph == null || !validateGraph(graph)) {
      graph = initSingleThreadGraph();
      activeGraph.set(graph);
      graphs.add(graph);
    }

    return graph;
  }

  protected boolean validateGraph(YTDBSingleThreadGraph graph) {
    return true;
  }

  public abstract YTDBSingleThreadGraph initSingleThreadGraph();

  public void removeFromGraphs(YTDBSingleThreadGraph graph) {
    graphs.remove(graph);

    var currentGraph = activeGraph.get();
    if (currentGraph == graph) {
      activeGraph.remove();
    }
  }

  @SuppressWarnings({"deprecation", "rawtypes"})
  @Override
  public <I extends Io> I io(Io.Builder<I> builder) {
    //noinspection unchecked
    return (I)
        YTDBGraphInternal.super.io(builder.onMapper(
            mb -> mb.addRegistry(YTDBIoRegistry.instance())));
  }

}
