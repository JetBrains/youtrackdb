package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphImplAbstract;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.script.Bindings;
import javax.script.SimpleBindings;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;

public class YTDBGraphManager implements GraphManager {

  private static final String TRAVERSAL_SOURCE_PREFIX = "g";

  @Nonnull
  private final YouTrackDBServer youTrackDBServer;
  private final ConcurrentHashMap<String, YTDBServerGraphImpl> registeredGraphs = new ConcurrentHashMap<>();
  private final ThreadLocal<AuthenticatedUser> currentUser = new ThreadLocal<>();

  public YTDBGraphManager(Settings settings) {
    var ytdbSettings = (YTDBSettings) settings;
    youTrackDBServer = ytdbSettings.server;
    assert youTrackDBServer != null;
  }

  @Override
  public Set<String> getGraphNames() {
    return youTrackDBServer.listDatabases();
  }

  @Override
  @Nullable
  public Graph getGraph(String graphName) {
    return registeredGraphs.get(graphName);
  }

  @Override
  public void putGraph(String graphName, Graph g) {
    throw new UnsupportedOperationException("putGraph is not supported in YTDB");
  }

  @Override
  public Set<String> getTraversalSourceNames() {
    var graphNames = getGraphNames();
    var traversalSources = new HashSet<String>(graphNames.size());

    for (var graphName : graphNames) {
      traversalSources.add(TRAVERSAL_SOURCE_PREFIX + graphName);
    }

    return traversalSources;
  }

  @Override
  @Nullable
  public TraversalSource getTraversalSource(String traversalSourceName) {
    if (!traversalSourceName.startsWith(TRAVERSAL_SOURCE_PREFIX)) {
      return null;
    }

    var graphName = traversalSourceName.substring(TRAVERSAL_SOURCE_PREFIX.length());
    var graph = getGraph(graphName);
    if (graph == null) {
      return null;
    }

    return graph.traversal();
  }

  @Override
  public void putTraversalSource(String tsName, TraversalSource ts) {
    throw new UnsupportedOperationException("putTraversalSource is not supported in YTDB");
  }

  @Override
  @Nullable
  public TraversalSource removeTraversalSource(String tsName) {
    return null;
  }

  @Override
  public Bindings getAsBindings() {
    final Bindings bindings = new SimpleBindings();

    getGraphNames().forEach((name) -> {
      var graph = getGraph(name);
      bindings.put(name, graph);
      bindings.put(TRAVERSAL_SOURCE_PREFIX + name, graph.traversal());
    });

    return bindings;
  }

  @Override
  public void rollbackAll() {
    registeredGraphs.values().forEach(graph -> {
      var tx = graph.tx();
      if (tx.isOpen()) {
        tx.rollback();
      }
    });
  }

  @Override
  public void rollback(Set<String> graphSourceNamesToCloseTxOn) {
    graphSourceNamesToCloseTxOn.forEach(graphName -> {
      var graph = getGraph(graphName);

      if (graph != null) {
        graph.tx().rollback();
      }
    });
  }

  @Override
  public void commitAll() {
    registeredGraphs.values().forEach(graph -> {
      var tx = graph.tx();
      if (tx.isOpen()) {
        tx.rollback();
      }
    });
  }

  @Override
  public void commit(Set<String> graphSourceNamesToCloseTxOn) {
    graphSourceNamesToCloseTxOn.forEach(graphName -> {
      var graph = getGraph(graphName);

      if (graph != null) {
        graph.tx().commit();
      }
    });
  }

  @Override
  public Graph openGraph(String graphName, Function<String, Graph> supplier) {
    return registeredGraphs.compute(graphName, (name, registeredGraph) -> {
      if (registeredGraph != null) {
        return registeredGraph;
      }

      var g = supplier.apply(name);
      if (!(g instanceof YTDBServerGraphImpl ytdbServerGraphImpl)) {
        throw new IllegalArgumentException(
            "Graph must be of type " + YTDBServerGraphImpl.class.getName());
      }

      return ytdbServerGraphImpl;
    });
  }

  @Override
  @Nullable
  public Graph removeGraph(String graphName) {
    return registeredGraphs.remove(graphName);
  }

  @Override
  public void beforeQueryStart(RequestMessage msg, AuthenticatedUser authenticatedUser) {
    currentUser.set(authenticatedUser);
  }

  @Override
  public void afterQueryEnd(RequestMessage msg) {
    currentUser.remove();
  }

  public YTDBServerGraphImpl newGraphProxyInstance(String databaseName, Configuration config) {
    return new YTDBServerGraphImpl(databaseName, config);
  }

  public final class YTDBServerGraphImpl extends YTDBGraphImplAbstract {
    static {
      registerOptimizationStrategies(YTDBServerGraphImpl.class);
    }

    private final String databaseName;

    public YTDBServerGraphImpl(String databaseName, Configuration config) {
      super(config);
      this.databaseName = databaseName;
    }

    @Override
    public void close() {
      //do nothing
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public DatabaseSessionEmbedded acquireSession() {
      var databases = youTrackDBServer.getDatabases();

      var currentUser = YTDBGraphManager.this.currentUser.get();
      if (currentUser == null) {
        throw new IllegalStateException("User is not authenticated");
      }

      var sessionPool = databases.cachedPoolNoAuthentication(databaseName, currentUser.getName(),
          databases.getConfiguration());
      return (DatabaseSessionEmbedded) sessionPool.acquire();
    }

    @Override
    public boolean isSingleThreaded() {
      return false;
    }
  }
}
