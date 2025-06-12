package com.jetbrains.youtrack.db.internal.core.gremlin;


import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.common.SessionPool;
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
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_INTEGRATE)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_COMPUTER)
@GraphFactoryClass(YTDBGraphFactory.class)
public final class YTDBGraphImpl implements YTDBGraphInternal {

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

  private final ThreadLocal<YTDBSingleThreadGraph> graphInternal = new ThreadLocal<>();
  private final Set<YTDBSingleThreadGraph> graphs = ConcurrentHashMap.newKeySet();

  private final Configuration config;
  private final Transaction tx;
  private final SessionPool<DatabaseSession> sessionPool;

  public YTDBGraphImpl(SessionPool<DatabaseSession> sessionPool, Configuration config) {
    this.sessionPool = sessionPool;
    this.config = config;
    tx = new YTDBMultiThreadGraphTransaction(this);
  }

  @Override
  public YTDBVertex addVertex(Object... keyValues) {
    return this.graph().addVertex(keyValues);
  }

  @Override
  public YTDBVertex addVertex(String label) {
    return this.graph().addVertex(label);
  }

  @Override
  public <C extends GraphComputer> C compute(Class<C> graphComputerClass)
      throws IllegalArgumentException {
    return this.graph().compute(graphComputerClass);
  }

  @Override
  public GraphComputer compute() throws IllegalArgumentException {
    return this.graph().compute();
  }

  @Override
  public Iterator<Vertex> vertices(Object... vertexIds) {
    return this.graph().vertices(vertexIds);
  }

  @Override
  public Iterator<Edge> edges(Object... edgeIds) {
    return this.graph().edges(edgeIds);
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
    return this.graph().variables();
  }

  @Override
  public Features features() {
    return YouTrackDBFeatures.YTDBFeatures.INSTANCE;
  }

  @Override
  public Configuration configuration() {
    return config;
  }

  YTDBGraphInternal graph() {
    var graph = graphInternal.get();

    if (graph == null) {
      graph = GremlinUtils.wrapSession(sessionPool.acquire(), this);
      graphInternal.set(graph);
      graphs.add(graph);
    }

    return graph;
  }

  @SuppressWarnings({"deprecation", "rawtypes"})
  @Override
  public <I extends Io> I io(Io.Builder<I> builder) {
    //noinspection unchecked
    return (I)
        YTDBGraphInternal.super.io(builder.onMapper(
            mb -> mb.addRegistry(YTDBIoRegistry.instance())));
  }

  private void closeGraphs() throws Exception {
    var currentGraph = graphInternal.get();
    if (currentGraph != null) {
      currentGraph.close();
    }

    var pendingGraphs = new ArrayList<>(graphs);
    for (var graph : pendingGraphs) {
      graph.getUnderlyingDatabaseSession().activateOnCurrentThread();
      graph.close();
    }

    assert graphs.isEmpty();

    sessionPool.close();
  }

  @Override
  public DatabaseSessionEmbedded getUnderlyingDatabaseSession() {
    return this.graph().getUnderlyingDatabaseSession();
  }

  @Override
  public YTDBElementFactory elementFactory() {
    return this.graph().elementFactory();
  }


  public boolean isOpen() {
    if (sessionPool.isClosed()) {
      return false;
    }

    var currentGraph = graphInternal.get();
    if (currentGraph == null) {
      return false;
    }

    return !currentGraph.getUnderlyingDatabaseSession().isClosed();
  }

  @Override
  public String toString() {
    return StringFactory.graphString(this, sessionPool.getDbName());
  }

  public void removeFromGraphs(YTDBSingleThreadGraph graph) {
    graphs.remove(graph);

    var currentGraph = graphInternal.get();
    if (currentGraph == graph) {
      graphInternal.remove();
    }
  }
}
