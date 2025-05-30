package com.jetbrain.youtrack.db.gremlin.internal;


import com.jetbrain.youtrack.db.gremlin.api.YTDBGraphFactory;
import com.jetbrain.youtrack.db.gremlin.internal.io.YTDBIoRegistry;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization.YTDBGraphCountStrategy;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization.YTDBGraphMatchStepStrategy;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
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
                YTDBGraphMatchStepStrategy.instance()));
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
  public Vertex addVertex(Object... keyValues) {
    return this.graph().addVertex(keyValues);
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

  @SuppressWarnings("deprecation")
  @Override
  public <I extends Io> I io(Io.Builder<I> builder) {
    var graph = graph();
    return (I)
        YTDBGraphInternal.super.io(builder.onMapper(
            mb -> mb.addRegistry(new YTDBIoRegistry(graph.getUnderlyingSession()))));
  }

  private void closeGraphs() {
    graphInternal.set(null);

    graphs.forEach((k, v) -> {
      var session = v.getUnderlyingSession();
      session.activateOnCurrentThread();
      v.close();
    });

    graphs.clear();
  }

  @Override
  public DatabaseSessionEmbedded getUnderlyingSession() {
    return this.graph().getUnderlyingSession();
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
