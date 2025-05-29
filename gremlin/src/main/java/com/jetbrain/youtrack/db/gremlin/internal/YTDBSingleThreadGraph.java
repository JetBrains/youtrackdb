package com.jetbrain.youtrack.db.gremlin.internal;

import static com.jetbrain.youtrack.db.gremlin.internal.StreamUtils.asStream;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrack.db.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang.NotImplementedException;
import com.jetbrain.youtrack.db.gremlin.internal.io.YTDBIoRegistry;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization.YTDBGraphCountStrategy;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization.YTDBGraphMatchStepStrategy;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization.YTDBGraphStepStrategy;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.Io;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class YTDBSingleThreadGraph implements YTDBGraphInternal {
  public static final Logger logger = LoggerFactory.getLogger(YTDBSingleThreadGraph.class);

  static {
    TraversalStrategies.GlobalCache.registerStrategies(
        YTDBSingleThreadGraph.class,
        TraversalStrategies.GlobalCache.getStrategies(Graph.class)
            .clone()
            .addStrategies(
                YTDBGraphStepStrategy.instance(),
                YTDBGraphCountStrategy.instance(),
                YTDBGraphMatchStepStrategy.instance()));
  }

  private final Features features;
  private final Configuration configuration;
  private final YTDBSingleThreadGraphFactory factory;
  private YTDBElementFactory elementFactory;
  private final YTDBSingleThreadGraphTransaction tx;
  private final DatabaseSessionEmbedded session;

  public YTDBSingleThreadGraph(final YTDBSingleThreadGraphFactory factory,
      final DatabaseSessionEmbedded session, final Configuration configuration) {
    this.factory = factory;
    this.configuration = configuration;
    this.features = YouTrackDBFeatures.YTDBFeatures.INSTANCE_TX;
    this.tx = new YTDBSingleThreadGraphTransaction(this);
    this.elementFactory = new YTDBElementFactory(this);
    this.session = session;
  }

  @Override
  public Features features() {
    return features;
  }

  public DatabaseSessionEmbedded getUnderlyingDatabaseSession() {
    return session;
  }

  @Override
  public Vertex addVertex(Object... keyValues) {
    this.tx().readWrite();
    ElementHelper.legalPropertyKeyValueArray(keyValues);

    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw Vertex.Exceptions.userSuppliedIdsNotSupported();
    }

    var label = ElementHelper.getLabelValue(keyValues).orElse(SchemaClass.VERTEX_CLASS_NAME);
    var schema = session.getSchema();
    var vertexClass = schema.getClass(label);
    if (vertexClass == null) {
      var vClass = schema.getClass(SchemaClass.VERTEX_CLASS_NAME);
      vertexClass = schema.createClass(label, vClass);
    }

    var transaction = session.getActiveTransaction();
    var ytdbVertex = transaction.newVertex(vertexClass);

    var vertex = elementFactory().wrapVertex(ytdbVertex);
    vertex.property(keyValues);

    return vertex;
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
  public YTDBElementFactory elementFactory() {
    return elementFactory;
  }

  @Override
  public Iterator<Vertex> vertices(Object... vertexIds) {
    this.tx().readWrite();
    return elements(
        SchemaClass.VERTEX_CLASS_NAME,
        entity ->
            elementFactory()
                .wrapVertex(
                    entity.asVertex()),
        vertexIds);
  }

  private IndexManagerEmbedded getIndexManager() {
    return session.getSharedContext().getIndexManager();
  }

  private Schema getSchema() {
    return session.getMetadata().getSchema();
  }

  public Set<String> getIndexedKeys(String className) {
    var indexes = getIndexManager().getClassIndexes(session, className).iterator();
    var indexedKeys = new HashSet<String>();
    indexes.forEachRemaining(
        index -> {
          indexedKeys.addAll(index.getDefinition().getFields());
        });
    return indexedKeys;
  }

  @Override
  public Set<String> getIndexedKeys(final Class<? extends Element> elementClass, String label) {
    if (Vertex.class.isAssignableFrom(elementClass)) {
      return getVertexIndexedKeys(label);
    } else if (Edge.class.isAssignableFrom(elementClass)) {
      return getEdgeIndexedKeys(label);
    } else {
      throw new IllegalArgumentException("Class is not indexable: " + elementClass);
    }
  }

  public Set<String> getVertexIndexedKeys(final String label) {
    var cls = getSchema().getClass(label);
    if (cls != null && cls.isSubClassOf(SchemaClass.VERTEX_CLASS_NAME)) {
      return getIndexedKeys(label);
    }
    return Collections.emptySet();
  }

  public Set<String> getEdgeIndexedKeys(final String label) {
    var cls = getSchema().getClass(label);
    if (cls != null && cls.isSubClassOf(SchemaClass.EDGE_CLASS_NAME)) {
      return getIndexedKeys(label);
    }
    return Collections.emptySet();
  }

  @Override
  public Iterator<Edge> edges(Object... edgeIds) {
    this.tx().readWrite();

    return elements(
        SchemaClass.EDGE_CLASS_NAME,
        entity ->
            elementFactory()
                .wrapEdge(entity.asStatefulEdge()),
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
          ids.filter(id -> ((RecordId) id).isValidPosition()).map(tx::loadEntity);
      return entities.map(toA).iterator();
    }
  }

  private static RID createRecordId(Object id) {
    if (id instanceof RecordId) {
      return (RecordId) id;
    }
    if (id instanceof String) {
      return new RecordId((String) id);
    }
    if (id instanceof YTDBElement) {
      return ((YTDBElement) id).id();
    }

    throw new IllegalArgumentException(
        "Orient IDs have to be a String or ORecordId - you provided a " + id.getClass());
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
  public DatabaseSessionEmbedded getUnderlyingSession() {
    return session;
  }


  private static void prepareIndexConfiguration(Configuration config) {
    var defaultIndexType = SchemaClass.INDEX_TYPE.NOTUNIQUE.name();
    var defaultKeyType = PropertyType.STRING;
    String defaultClassName = null;
    String defaultCollate = null;
    Map<String, Object> defaultMetadata = null;

    if (!config.containsKey("type")) {
      config.setProperty("type", defaultIndexType);
    }
    if (!config.containsKey("keytype")) {
      config.setProperty("keytype", defaultKeyType);
    }
    if (!config.containsKey("class")) {
      config.setProperty("class", defaultClassName);
    }
    if (!config.containsKey("collate")) {
      config.setProperty("collate", defaultCollate);
    }
    if (!config.containsKey("metadata")) {
      config.setProperty("metadata", defaultMetadata);
    }
  }

  private <E extends Element> void createIndex(
      final String key, String className, final Configuration configuration) {
    prepareIndexConfiguration(configuration);

    var callable =
        new Function<YTDBSingleThreadGraph, SchemaClass>() {
          @Override
          @Nullable
          public SchemaClass apply(final YTDBSingleThreadGraph g) {
            var indexType = configuration.getString("type");
            var keyType = (PropertyType) configuration.getProperty("keytype");
            var collate = configuration.getString("collate");

            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) configuration.getProperty("metadata");

            var session = getUnderlyingSession();
            final var schema = session.getMetadata().getSchema();

            final var cls = schema.getClassInternal(className);
            if (cls == null) {
              throw new IllegalArgumentException(
                  "unable to find class " + className + " for index " + key);
            }
            final var property = cls.getProperty(key);
            if (property != null) {
              keyType = property.getType();
            }

            var indexDefinition = new PropertyIndexDefinition(className, key,
                PropertyTypeInternal.convertFromPublicType(keyType));
            if (collate != null) {
              indexDefinition.setCollate(collate);
            }
            session.getSharedContext().getIndexManager()
                .createIndex(g.getUnderlyingSession(),
                    className + "." + key,
                    indexType,
                    indexDefinition,
                    cls.getPolymorphicCollectionIds(),
                    null,
                    metadata);
            return null;
          }
        };
    execute(callable, "create key index on '", className, ".", key, "'");
  }

  private void execute(
      final Function<YTDBSingleThreadGraph, ?> function, final String... iOperationStrings)
      throws RuntimeException {
    if (logger.isWarnEnabled() && iOperationStrings.length > 0) {
      // COMPOSE THE MESSAGE
      final var msg = new StringBuilder(256);
      for (var s : iOperationStrings) {
        msg.append(s);
      }

      // ASSURE PENDING TX IF ANY IS COMMITTED
      LogManager.instance().warn(this, msg.toString());
    }

    function.apply(this);
  }

  @Override
  public <I extends Io> I io(Io.Builder<I> builder) {
    return (I)
        YTDBGraphInternal.super.io(builder.onMapper(mb -> mb.addRegistry(new YTDBIoRegistry(session))));
  }

  @Override
  public String toString() {
    return StringFactory.graphString(this, session.getURL());
  }

  public void setElementFactory(YTDBElementFactory elementFactory) {
    this.elementFactory = elementFactory;
  }

  @Override
  public YTDBSingleThreadGraphFactory getFactory() {
    return factory;
  }
}
