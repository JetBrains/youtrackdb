package com.jetbrains.youtrack.db.internal.core.gremlin;


import com.jetbrains.youtrack.db.api.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrack.db.api.gremlin.YTDBVertex;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphCountStrategy;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphIoStepStrategy;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphMatchStepStrategy;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.Io;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;

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
  private final ConcurrentHashMap<Thread, YTDBSingleThreadGraph> graphs = new ConcurrentHashMap<>();

  private final Configuration config;
  private final YTDBSingleThreadGraphFactory factory;
  private final Transaction tx;
  private final YTDBElementFactory elementFactory;

  public YTDBGraphImpl(YTDBSingleThreadGraphFactory factory, Configuration config) {
    this.factory = factory;
    this.config = config;
    tx = new YTDBMultiThreadGraphTransaction(this);
    elementFactory = new YTDBElementFactory(this);
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

  YTDBSingleThreadGraph graph() {
    var graph = graphInternal.get();

    if (graph == null) {
      graph = (YTDBSingleThreadGraph) factory.openGraph();
      graph.setElementFactory(elementFactory);
      graphInternal.set(graph);
      graphs.put(Thread.currentThread(), graph);
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

  private void closeGraphs() {
    graphInternal.remove();

    graphs.forEach((k, v) -> {
      var session = v.getUnderlyingDatabaseSession();
      session.activateOnCurrentThread();
      v.close();
    });

    graphs.clear();
  }

  @Override
  public DatabaseSessionEmbedded getUnderlyingDatabaseSession() {
    return this.graph().getUnderlyingDatabaseSession();
  }


  @Override
  public YTDBElementFactory elementFactory() {
    return this.graph().elementFactory();
  }

  @Override
  public Set<String> getIndexedKeys(Class<? extends Element> elementClass, String label) {
    return this.graph().getIndexedKeys(elementClass, label);
  }

  @Override
  public YTDBSingleThreadGraphFactory getFactory() {
    return factory;
  }

  public boolean isOpen() {
    return factory.isOpen() && graphInternal.get() != null && !graphInternal.get().isClosed();
  }

  @Override
  public String toString() {
    return factory.toString();
  }
}
