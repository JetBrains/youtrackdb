package com.jetbrains.youtrack.db.internal.core.gremlin;

import static com.jetbrains.youtrack.db.internal.core.gremlin.StreamUtils.asStream;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.gremlin.YTDBVertex;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphCountStrategy;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphIoStepStrategy;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphMatchStepStrategy;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang.NotImplementedException;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.Io;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class YTDBGraphImplAbstract implements YTDBGraphInternal {

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
  private final ThreadLocal<YTDBTransaction> tx = ThreadLocal.withInitial(
      () -> new YTDBTransaction(this));

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
    this.tx().readWrite();

    ElementHelper.legalPropertyKeyValueArray(keyValues);
    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw Vertex.Exceptions.userSuppliedIdsNotSupported();
    }

    var label = ElementHelper.getLabelValue(keyValues).orElse(Vertex.DEFAULT_LABEL);
    var vertex = createVertexWithClass(label);
    ((YTDBElementImpl) vertex).property(keyValues);

    return vertex;
  }

  @Override
  public YTDBVertex addVertex(String label) {
    return this.addVertex(T.label, label);
  }

  private YTDBVertex createVertexWithClass(String label) {
    var session = getUnderlyingDatabaseSession();
    var vertexClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(label);

    if (vertexClass == null) {
      try (var copy = session.copy()) {
        var schemaCopy = copy.getSchema();
        var vClass = schemaCopy.getClass(SchemaClass.VERTEX_CLASS_NAME);
        schemaCopy.getOrCreateClass(label, vClass);
      }
      vertexClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(label);
    } else if (!vertexClass.isVertexType()) {
      throw new IllegalArgumentException("Class " + label + " is not a vertex type");
    }

    var transaction = session.getActiveTransaction();
    var ytdbVertex = transaction.newVertex(vertexClass);

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
    this.tx().readWrite();
    return elements(
        SchemaClass.VERTEX_CLASS_NAME,
        entity ->
            new YTDBVertexImpl(this,
                    entity.asVertex()),
        vertexIds);
  }

  @Override
  public Iterator<Edge> edges(Object... edgeIds) {
    this.tx().readWrite();

    return elements(
        SchemaClass.EDGE_CLASS_NAME,
        entity ->
            new YTDBStatefulEdgeImpl(this, entity.asStatefulEdge()),
        edgeIds);
  }

  private <A extends Element> Iterator<A> elements(
      String elementClass, Function<Entity, A> toA, Object... elementIds) {
    var session = getUnderlyingDatabaseSession();
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
    return this.tx.get();
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
  public abstract void close();

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
    return StringFactory.graphString(this,
        configuration.getString(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME));
  }

  public abstract DatabaseSessionEmbedded getUnderlyingDatabaseSession();

  public abstract DatabaseSessionEmbedded peekUnderlyingDatabaseSession();

  @Override
  public abstract boolean isOpen();
}
