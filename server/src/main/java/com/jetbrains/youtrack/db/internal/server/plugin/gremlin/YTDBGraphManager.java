package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import com.jetbrains.youtrack.db.internal.core.gremlin.GremlinUtils;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBSingleThreadGraph;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.script.Bindings;
import javax.script.SimpleBindings;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang.NotImplementedException;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;

public class YTDBGraphManager implements GraphManager {
  private static final String TRAVERSAL_SOURCE_PREFIX = "g";

  @Nonnull
  private final YouTrackDBServer youTrackDBServer;

  private final ConcurrentHashMap<String, UserSession> activeUserSessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Graph> graphs = new ConcurrentHashMap<>();

  private final ThreadLocal<UserSession> currentSession = new ThreadLocal<>();

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
    return graphs.get(graphName);
  }

  @Override
  public void putGraph(String graphName, Graph g) {
    throw new UnsupportedOperationException("putGraph is not supported in YTDB");
  }

  public YTDBServerGraph newGraph(String graphName) {
    return new YTDBServerGraph(graphName);
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
    throw new UnsupportedOperationException("rollbackAll is not supported in YTDB");
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
    throw new UnsupportedOperationException("commitAll is not supported in YTDB");
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
    return graphs.compute(graphName, (name, registeredGraph) -> {
      if (registeredGraph != null) {
        return registeredGraph;
      }

      var g = supplier.apply(name);
      if (!(g instanceof YTDBServerGraph)) {
        throw new IllegalArgumentException(
            "Graph must be of type " + YTDBServerGraph.class.getName());
      }

      return g;
    });
  }

  @Override
  @Nullable
  public Graph removeGraph(String graphName) {
    return graphs.remove(graphName);
  }

  @Override
  public void beforeQueryStart(RequestMessage msg) {
    var sessionId = (String) msg.getArgs().get(Tokens.ARGS_SESSION);
    if (sessionId == null) {
      sessionId = msg.getRequestId().toString();
    }

    var session = activeUserSessions.get(sessionId);
    if (session == null) {
      throw new IllegalStateException(
          "Session with id " + sessionId + " not found in GraphManager");
    }

    currentSession.set(session);
  }

  @Override
  public void onQueryError(RequestMessage msg, Throwable error) {
    currentSession.remove();
  }

  @Override
  public void onQuerySuccess(RequestMessage msg) {
    currentSession.remove();
  }

  @Override
  public void onSessionStart(String sessionId, AuthenticatedUser user) {
    if (user == null) {
      throw new IllegalArgumentException("Anonymous users are not allowed");
    }

    activeUserSessions.compute(sessionId, (id, userSession) -> {
      if (userSession == null) {
        return new UserSession(new ConcurrentHashMap<>(), user.getName());
      }
      return userSession;
    });
  }


  @Override
  public void onSessionClose(String sessionId) {
    var session = activeUserSessions.remove(sessionId);
    session.clear();

    if (currentSession.get() == session) {
      currentSession.remove();
    }
  }

  private record UserSession(ConcurrentHashMap<String, YTDBSingleThreadGraph> graphs,
                             String userName) {

    void clear() {
      graphs.forEach((name, graph) -> {
        graph.getUnderlyingDatabaseSession().activateOnCurrentThread();
        graph.close();
      });

      graphs.clear();
    }
  }

  public final class YTDBServerGraph implements Graph {

    private final String name;

    private YTDBServerGraph(String name) {
      this.name = name;
    }

    @Override
    public Vertex addVertex(Object... keyValues) {
      var userSession = getUserSession();
      var singleThreadGraph = getOrCreateSessionGraph(userSession);
      return singleThreadGraph.addVertex(keyValues);
    }

    @Nonnull
    private YTDBSingleThreadGraph getOrCreateSessionGraph(UserSession userSession) {
      return userSession.graphs.compute(name, (graphName, graph) -> {
        if (graph == null) {
          var databaseSession = youTrackDBServer.getDatabases()
              .openNoAuthenticate(name, userSession.userName);
          graph = GremlinUtils.wrapSession(databaseSession, null);
        }

        return graph;
      });
    }


    @Override
    public <C extends GraphComputer> C compute(Class<C> graphComputerClass)
        throws IllegalArgumentException {
      throw new NotImplementedException();
    }

    @Override
    public GraphComputer compute() throws IllegalArgumentException {
      throw new NotImplementedException();
    }

    @Override
    public Iterator<Vertex> vertices(Object... vertexIds) {
      var session = getUserSession();
      var singleThreadGraph = getOrCreateSessionGraph(session);
      return singleThreadGraph.vertices(vertexIds);
    }

    @Override
    public Iterator<Edge> edges(Object... edgeIds) {
      var session = getUserSession();
      var singleThreadGraph = getOrCreateSessionGraph(session);
      return singleThreadGraph.edges(edgeIds);
    }

    @Override
    public Transaction tx() {
      var session = getUserSession();
      var singleThreadGraph = getOrCreateSessionGraph(session);
      return singleThreadGraph.tx();
    }

    @Override
    public void close() {
      activeUserSessions.forEach((id, session) -> {
        var graph = session.graphs.remove(name);

        if (graph != null) {
          graph.getUnderlyingDatabaseSession().activateOnCurrentThread();
          graph.close();
        }
      });
    }

    @Override
    public Variables variables() {
      throw new NotImplementedException();
    }

    @Override
    public Configuration configuration() {
      var session = currentSession.get();

      if (session != null) {
        var graph = session.graphs.get(name);

        if (graph != null) {
          return graph.configuration();
        }
      }

      var baseConfiguration = new BaseConfiguration();
      baseConfiguration.setProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME, name);
      return baseConfiguration;
    }

    @Nonnull
    private UserSession getUserSession() {
      var session = currentSession.get();
      if (session == null) {
        throw new IllegalStateException("No active user session found");
      }

      return session;
    }
  }
}
