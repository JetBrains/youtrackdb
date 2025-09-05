package com.jetbrains.youtrackdb.internal.core.gremlin;

import static com.jetbrains.youtrackdb.internal.core.gremlin.StreamUtils.asStream;

import com.jetbrains.youtrackdb.api.YouTrackDB.ConfigurationParameters;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphCountStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphIoStepStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphMatchStepStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang.NotImplementedException;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Transaction.Status;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.Io;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class YTDBGraphImplAbstract implements YTDBGraphInternal, Consumer<Status> {

  public static void registerOptimizationStrategies(Class<? extends YTDBGraphImplAbstract> cls) {
    TraversalStrategies.GlobalCache.registerStrategies(
        cls,
        TraversalStrategies.GlobalCache.getStrategies(Graph.class)
            .clone()
            .addStrategies(
                YTDBGraphStepStrategy.instance(),
                YTDBGraphCountStrategy.instance(),
                YTDBGraphMatchStepStrategy.instance(),
                YTDBGraphIoStepStrategy.instance()));
  }


  public static final Logger logger = LoggerFactory.getLogger(YTDBGraphImplAbstract.class);

  private final Features features;
  private final Configuration configuration;

  private final ThreadLocal<ThreadLocalState> threadLocalState = ThreadLocal.withInitial(
      () -> new ThreadLocalState(new YTDBTransaction(this)));

  public YTDBGraphImplAbstract(final Configuration configuration) {
    this.configuration = configuration;
    this.features = YouTrackDBFeatures.YTDBFeatures.INSTANCE;
  }

  @Override
  public Features features() {
    return features;
  }

  @Override
  public YTDBVertex addVertex(Object... keyValues) {
    var tx = tx();
    tx.readWrite();

    ElementHelper.legalPropertyKeyValueArray(keyValues);
    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw Vertex.Exceptions.userSuppliedIdsNotSupported();
    }

    var label = ElementHelper.getLabelValue(keyValues).orElse(Vertex.DEFAULT_LABEL);
    var vertex = createVertexWithClass(tx.getDatabaseSession(), label);
    ((YTDBElementImpl) vertex).property(keyValues);

    return vertex;
  }

  @Override
  public YTDBVertex addVertex(String label) {
    return this.addVertex(T.label, label);
  }

  private YTDBVertex createVertexWithClass(DatabaseSessionEmbedded sessionEmbedded, String label) {
    executeSchemaCode(session -> {
      var schema = session.getSharedContext().getSchema();
      var vertexClass = schema.getClass(session, label);

      if (vertexClass == null) {
        var vClass = schema.getClass(session, SchemaClass.VERTEX_CLASS_NAME);
        schema.getOrCreateClass(session, label, vClass);
      } else if (!vertexClass.isVertexType(session)) {
        throw new IllegalArgumentException("Class " + label + " is not a vertex type");
      }
    });

    var transaction = sessionEmbedded.getActiveTransaction();
    var ytdbVertex = transaction.newVertex(label);

    return new YTDBVertexImpl(this, ytdbVertex);
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
    var tx = tx();
    tx.readWrite();
    return elements(
        tx.getDatabaseSession(),
        SchemaClass.VERTEX_CLASS_NAME,
        entity ->
            new YTDBVertexImpl(this,
                entity.asVertex()),
        vertexIds);
  }

  @Override
  public Iterator<Edge> edges(Object... edgeIds) {
    var tx = tx();
    tx.readWrite();

    return elements(
        tx.getDatabaseSession(),
        SchemaClass.EDGE_CLASS_NAME,
        entity ->
            new YTDBStatefulEdgeImpl(this, entity.asStatefulEdge()),
        edgeIds);
  }

  private static <A extends Element> Iterator<A> elements(
      DatabaseSessionEmbedded session, String elementClass, Function<Entity, A> toA,
      Object... elementIds) {
    var polymorphic = true;
    if (elementIds.length == 0) {
      // return all vertices as stream
      var itty = session.browseClass(elementClass, polymorphic);
      return asStream(itty).map(toA).iterator();
    } else {
      var tx = session.getActiveTransaction();
      var ids = Stream.of(elementIds).map(YTDBGraphImplAbstract::createRecordId);
      var entities =
          ids.filter(id -> ((RecordId) id).isValidPosition()).map(rid -> {
            try {
              return tx.loadEntity(rid);
            } catch (RecordNotFoundException e) {
              return null;
            }
          }).filter(Objects::nonNull);
      return entities.map(toA).iterator();
    }
  }

  private static RID createRecordId(Object id) {
    if (id instanceof RID rid) {
      return rid;
    }
    if (id instanceof String strRid) {
      return new RecordId(strRid);
    }
    if (id instanceof Element gremlinElement) {
      return createRecordId(gremlinElement.id());
    }
    if (id instanceof Identifiable identifiable) {
      return identifiable.getIdentity();
    }

    throw new IllegalArgumentException(
        "YouTrackDB IDs have to be a String or RID - you provided a " + id.getClass());
  }


  @Override
  public YTDBTransaction tx() {
    return this.threadLocalState.get().transaction;
  }

  @Override
  public void executeSchemaCode(Consumer<DatabaseSessionEmbedded> code) {
    try (var session = acquireSession()) {
      code.accept(session);
    }
  }

  @Override
  public <R> R computeSchemaCode(Function<DatabaseSessionEmbedded, R> code) {
    try (var session = acquireSession()) {
      return code.apply(session);
    }
  }

  @Override
  public Variables variables() {
    throw new NotImplementedException();
  }

  @Override
  public Configuration configuration() {
    return configuration;
  }

  @Override
  public void close() {
    var threadLocalState = this.threadLocalState.get();

    var tx = threadLocalState.transaction;
    if (tx.isOpen()) {
      tx.close();
    }

    var session = threadLocalState.sessionEmbedded;
    if (session != null) {
      session.close();
    }
  }

  @SuppressWarnings("rawtypes")
  @Override
  public <I extends Io> I io(Io.Builder<I> builder) {
    //noinspection unchecked
    return (I)
        YTDBGraphInternal.super.io(
            builder.onMapper(mb -> mb.addRegistry(YTDBIoRegistry.instance())));
  }

  @Override
  public String toString() {
    return YTDBGraph.class.getSimpleName() + "[" + configuration.getString(
        ConfigurationParameters.CONFIG_DB_NAME) + "]";
  }

  public DatabaseSessionEmbedded getUnderlyingDatabaseSession() {
    var threadLocalState = this.threadLocalState.get();
    var currentSession = threadLocalState.sessionEmbedded;

    if (currentSession != null) {
      if (!currentSession.isTxActive()) {
        tx().addTransactionListener(this);
      }

      return currentSession;
    }

    currentSession = acquireSession();
    tx().addTransactionListener(this);

    threadLocalState.sessionEmbedded = currentSession;
    return currentSession;
  }

  @Override
  public void accept(Status status) {
    var threadLocalState = this.threadLocalState.get();
    var currentSession = threadLocalState.sessionEmbedded;
    if (currentSession == null) {
      return;
    }

    if (currentSession.isTxActive()) {
      throw new IllegalStateException("Transaction is still active");
    }

    currentSession.close();
    threadLocalState.sessionEmbedded = null;
  }

  @Override
  public abstract boolean isOpen();

  public abstract DatabaseSessionEmbedded acquireSession();

  private static final class ThreadLocalState {

    @Nullable
    private DatabaseSessionEmbedded sessionEmbedded;
    private final YTDBTransaction transaction;

    private ThreadLocalState(YTDBTransaction transaction) {
      this.transaction = transaction;
    }
  }
}
