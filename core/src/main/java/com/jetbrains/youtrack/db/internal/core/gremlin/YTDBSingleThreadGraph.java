package com.jetbrains.youtrack.db.internal.core.gremlin;

import static com.jetbrains.youtrack.db.internal.core.gremlin.StreamUtils.asStream;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.gremlin.YTDBVertex;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang.NotImplementedException;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.Io;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class YTDBSingleThreadGraph implements YTDBGraphInternal {

  public static final Logger logger = LoggerFactory.getLogger(YTDBSingleThreadGraph.class);

  private final Features features;
  private final Configuration configuration;
  private final YTDBSingleThreadGraphTransaction tx;
  private final DatabaseSessionEmbedded session;
  private final YTDBElementFactory elementFactory;
  @Nullable
  private final YTDBGraphImpl parentGraph;

  public YTDBSingleThreadGraph(final DatabaseSessionEmbedded session,
      final Configuration configuration, YTDBElementFactory elementFactory,
      @Nullable YTDBGraphImpl parentGraph) {
    this.configuration = configuration;
    this.elementFactory = elementFactory;
    this.parentGraph = parentGraph;
    this.features = YouTrackDBFeatures.YTDBFeatures.INSTANCE;
    this.tx = new YTDBSingleThreadGraphTransaction(this);
    this.session = session;
  }

  @Override
  public Features features() {
    return features;
  }

  @Override
  public YTDBVertex addVertex(Object... keyValues) {
    this.tx().readWrite();
    ElementHelper.legalPropertyKeyValueArray(keyValues);

    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw Vertex.Exceptions.userSuppliedIdsNotSupported();
    }

    var label = ElementHelper.getLabelValue(keyValues).orElse(Vertex.DEFAULT_LABEL);
    var vertex = createVertexWithClass(label);
    ((YTDBAbstractElement) vertex).property(keyValues);

    return vertex;
  }

  @Override
  public YTDBVertex addVertex(String label) {
    return this.addVertex(T.label, label);
  }

  private YTDBVertex createVertexWithClass(String label) {
    var vertexClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(label);

    if (vertexClass == null) {
      if (session.isTxActive()) {
        try (var copy = session.copy()) {
          var schemaCopy = copy.getSchema();
          var vClass = schemaCopy.getClass(SchemaClass.VERTEX_CLASS_NAME);
          schemaCopy.getOrCreateClass(label, vClass);
        }
        vertexClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(label);
      } else {
        var schema = session.getSchema();
        var vClass = schema.getClass(SchemaClass.VERTEX_CLASS_NAME);
        vertexClass = schema.getOrCreateClass(label, vClass);
      }
    } else if (!vertexClass.isVertexType()) {
      throw new IllegalArgumentException("Class " + label + " is not a vertex type");
    }

    var transaction = session.getActiveTransaction();
    var ytdbVertex = transaction.newVertex(vertexClass);

    return elementFactory.wrapVertex(this, ytdbVertex);
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
    this.tx().readWrite();
    return elements(
        SchemaClass.VERTEX_CLASS_NAME,
        entity ->
            elementFactory()
                .wrapVertex(this,
                    entity.asVertex()),
        vertexIds);
  }

  @Override
  public Iterator<Edge> edges(Object... edgeIds) {
    this.tx().readWrite();

    return elements(
        SchemaClass.EDGE_CLASS_NAME,
        entity ->
            elementFactory()
                .wrapEdge(this, entity.asStatefulEdge()),
        edgeIds);
  }

  private <A extends Element> Iterator<A> elements(
      String elementClass, Function<Entity, A> toA, Object... elementIds) {
    var polymorphic = true;
    if (elementIds.length == 0) {
      // return all vertices as stream
      var itty = session.browseClass(elementClass, polymorphic);
      return asStream(itty).map(toA).iterator();
    } else {
      var tx = session.getActiveTransaction();
      var ids = Stream.of(elementIds).map(YTDBSingleThreadGraph::createRecordId);
      var entities =
          ids.filter(id -> ((RecordId) id).isValidPosition()).map(rid -> {
            try {
              return tx.loadEntity(rid);
            } catch (RecordNotFoundException e) {
              //noinspection ReturnOfNull
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
    if (id instanceof YTDBAbstractElement gremlinElement) {
      return gremlinElement.id();
    }
    if (id instanceof Identifiable identifiable) {
      return identifiable.getIdentity();
    }

    throw new IllegalArgumentException(
        "YouTrackDB IDs have to be a String or RID - you provided a " + id.getClass());
  }


  @Override
  public YTDBSingleThreadGraphTransaction tx() {
    return this.tx;
  }

  /**
   * Checks if the Graph has been closed.
   *
   * @return True if it is closed, otherwise false
   */
  public boolean isClosed() {
    return session == null || session.isClosed();
  }

  public void begin() {
    this.tx().doOpen();
  }

  public void commit() {
    this.tx().commit();
  }

  public void rollback() {
    this.tx().rollback();
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
    var url = session.getURL();
    try {
      this.tx().close();
    } finally {
      try {
        if (!session.isClosed()) {
          session.close();
        }
      } catch (Exception e) {
        LogManager.instance().error(this, "Error during context close for db " + url, e);
      }
      if (parentGraph != null) {
        parentGraph.removeFromGraphs(this);
      }
    }
  }

  public void createVertexClass(final String label) {
    createClass(label, SchemaClass.VERTEX_CLASS_NAME);
  }

  public void createEdgeClass(final String label) {
    createClass(label, SchemaClass.EDGE_CLASS_NAME);
  }

  public void createClass(final String className, final String superClassName) {
    var schema = session.getMetadata().getSchema();

    var superClass = schema.getClass(superClassName);
    if (superClass == null) {
      var allClasses = session.getMetadata().getSchema().getClasses();
      throw new IllegalArgumentException(
          "unable to find class " + superClassName + ". Available classes: " + allClasses);
    }

    schema.createClass(className, superClass);
  }

  @Override
  public DatabaseSessionEmbedded getUnderlyingDatabaseSession() {
    return session;
  }

  @Override
  public YTDBElementFactory elementFactory() {
    return elementFactory;
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
    return StringFactory.graphString(this, session.getURL());
  }


}
