package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import com.jetbrains.youtrack.db.api.SessionListener;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.transaction.Transaction;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphImplAbstract;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
import org.apache.tinkerpop.gremlin.structure.Transaction.Status;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;

public class YTDBGraphManager implements GraphManager {
  public static final String TRAVERSAL_SOURCE_PREFIX = "ytdb";

  @Nonnull
  private final YouTrackDBServer youTrackDBServer;
  private final ConcurrentHashMap<String, YTDBServerGraphImpl> registeredGraphs = new ConcurrentHashMap<>();
  private final YTDBTransactionListener transactionListener = new YTDBTransactionListener();
  private final ThreadLocal<QuerySession> currentQuerySession = new ThreadLocal<>();
  private final ConcurrentHashMap<UUID, Map<RID, RID>> fetchedCommitedRids = new ConcurrentHashMap<>();

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

  @Nullable
  public Map<RID, RID> getCommitedRids(RequestMessage msg) {
    return fetchedCommitedRids.get(msg.getRequestId());
  }

  @Override
  @Nullable
  public Graph removeGraph(String graphName) {
    return registeredGraphs.remove(graphName);
  }

  @Override
  public void beforeQueryStart(RequestMessage msg, AuthenticatedUser authenticatedUser) {
    currentQuerySession.set(new QuerySession(authenticatedUser, msg));
  }

  public void afterQueryEnd(RequestMessage msg) {
    currentQuerySession.remove();
    fetchedCommitedRids.remove(msg.getRequestId());
  }

  public YTDBServerGraphImpl newGraphProxyInstance(String databaseName, Configuration config) {
    return new YTDBServerGraphImpl(databaseName, config);
  }

  public final class YTDBServerGraphImpl extends YTDBGraphImplAbstract implements
      Consumer<Status> {

    static {
      registerOptimizationStrategies(YTDBServerGraphImpl.class);
    }

    private final ThreadLocal<DatabaseSessionEmbedded> currentDatabaseSession = new ThreadLocal<>();
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
    public DatabaseSessionEmbedded getUnderlyingDatabaseSession() {
      var currentSession = currentDatabaseSession.get();

      if (currentSession != null) {
        if (!currentSession.isTxActive()) {
          throw new IllegalStateException("Transaction is not active");
        }

        return currentSession;
      }

      var currentQuerySession = YTDBGraphManager.this.currentQuerySession.get();
      if (currentQuerySession == null) {
        throw new IllegalStateException("User is not authenticated");
      }

      var currentUser = currentQuerySession.user;
      var databases = youTrackDBServer.getDatabases();
      var parentConfiguration = databases.getConfiguration();
      var configuration = new YouTrackDBConfigImpl();
      configuration.setParent(parentConfiguration);
      configuration.getListeners().add(YTDBGraphManager.this.transactionListener);

      var sessionPool = databases.cachedPoolNoAuthentication(databaseName, currentUser.getName(),
          configuration);
      currentSession = (DatabaseSessionEmbedded) sessionPool.acquire();

      tx().addTransactionListener(this);
      currentDatabaseSession.set(currentSession);

      return currentSession;
    }

    @Override
    public DatabaseSessionEmbedded peekUnderlyingDatabaseSession() {
      return currentDatabaseSession.get();
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void accept(Status status) {
      var currentSession = currentDatabaseSession.get();
      if (currentSession == null) {
        return;
      }

      if (currentSession.isTxActive()) {
        throw new IllegalStateException("Transaction is still active");
      }

      currentSession.close();
      currentDatabaseSession.remove();
    }

    @Override
    public boolean isSingleThreaded() {
      return false;
    }
  }

  private record QuerySession(AuthenticatedUser user, RequestMessage requestMessage) {

  }

  private final class YTDBTransactionListener implements SessionListener {

    @Override
    public void onAfterTxCommit(Transaction transaction, @Nullable Map<RID, RID> ridMapping) {
      var currentQuerySession = YTDBGraphManager.this.currentQuerySession.get();

      if (currentQuerySession != null && ridMapping != null && !ridMapping.isEmpty()) {
        var requestId = currentQuerySession.requestMessage.getRequestId();
        fetchedCommitedRids.put(requestId, ridMapping);
      }
    }
  }
}
