package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphCountStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphIoStepStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphMatchStepStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.parser.DDLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.TokenMgrError;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.NotImplementedException;
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
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
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
    ElementHelper.attachProperties(vertex, keyValues);

    return vertex;
  }

  @Override
  public YTDBVertex addVertex(String label) {
    return this.addVertex(T.label, label);
  }

  private YTDBVertex createVertexWithClass(DatabaseSessionEmbedded sessionEmbedded, String label) {
    executeSchemaCode(session -> {
      var schema = session.getSharedContext().getSchema();
      var vertexClass = schema.getClass(label);

      if (vertexClass == null) {
        var vClass = schema.getClass(SchemaClass.VERTEX_CLASS_NAME);
        schema.getOrCreateClass(session, label, vClass);
      } else if (!vertexClass.isVertexType()) {
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
      return IteratorUtils.map(itty, toA::apply);
    } else {
      var tx = session.getActiveTransaction();
      var ids = Stream.of(elementIds)
          .filter(Objects::nonNull) // looks like Gremlin allows nulls in here.
          .map(YTDBGraphImplAbstract::createRecordId);
      var entities =
          ids.filter(id -> ((RecordIdInternal) id).isValidPosition()).map(rid -> {
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
      return RecordIdInternal.fromString(strRid, false);
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
  public void executeCommand(String command, Map<?, ?> params) {
    var normalized = command == null ? "" : command.trim().toUpperCase();

    if (isSchemaCommand(command == null ? "" : command.trim())) {
      try (var session = acquireSession()) {
        session.command(command, params);
      }
      return;
    }

    switch (normalized) {
      case "BEGIN" -> {
        var tx = tx();
        if (!tx.isOpen()) {
          tx.readWrite();
        } else {
          throw new IllegalStateException("There already is an active transaction");
        }
        return;
      }
      case "COMMIT" -> {
        var tx = tx();
        if (tx.isOpen()) {
          tx.commit();
        } else {
          throw new IllegalStateException("No active transaction to commit");
        }
        return;
      }
      case "ROLLBACK" -> {
        var tx = tx();
        if (tx.isOpen()) {
          tx.rollback();
        } else {
          throw new IllegalStateException("No active transaction to rollback");
        }
        return;
      }
    }

    var session = tx().getDatabaseSession();
    session.command(command, params);
  }

  private static boolean isSchemaCommand(String command) {
    if (command == null || command.isBlank()) {
      return false;
    }
    try {
      var is = new ByteArrayInputStream(command.getBytes(StandardCharsets.UTF_8));
      var parser = new YouTrackDBSql(is);
      var statement = parser.parse();
      return statement instanceof DDLStatement;
    } catch (ParseException | TokenMgrError e) {
      return false;
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
        YTDBGraphFactory.CONFIG_DB_NAME) + "]";
  }

  @SuppressWarnings("resource")
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
  public void backup(Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier,
      Function<String, OutputStream> ibuOutputStreamSupplier,
      Consumer<String> ibuFileRemover) {
    try (var session = acquireSession()) {
      session.backup(ibuFilesSupplier, ibuInputStreamSupplier, ibuOutputStreamSupplier,
          ibuFileRemover);
    }
  }

  @Override
  public String backup(Path path) {
    try (var session = acquireSession()) {
      return session.backup(path);
    }
  }

  @Override
  public String fullBackup(Path path) {
    try (var session = acquireSession()) {
      return session.fullBackup(path);
    }
  }

  @Override
  public UUID uuid() {
    try (var session = acquireSession()) {
      return session.uuid();
    }
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
