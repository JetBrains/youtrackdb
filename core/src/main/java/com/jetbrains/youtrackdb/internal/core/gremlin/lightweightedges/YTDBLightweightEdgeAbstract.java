package com.jetbrains.youtrackdb.internal.core.gremlin.lightweightedges;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import javax.annotation.Nonnull;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public abstract class YTDBLightweightEdgeAbstract implements YTDBEdge {
  private final ThreadLocal<EntityImpl> threadLocalOwnerEntity = new ThreadLocal<>();

  protected final @Nonnull YTDBGraphInternal graph;
  protected final @Nonnull String label;
  private final RID ownerRID;

  @Nonnull
  private final EntityImpl fastPathOwnerEntity;

  public YTDBLightweightEdgeAbstract(@Nonnull YTDBGraphInternal graph, @Nonnull EntityImpl owner,
      @Nonnull String label) {
    this.graph = graph;
    this.label = label;

    this.ownerRID = owner.getIdentity();
    this.fastPathOwnerEntity = owner;
  }

  @Override
  public Object id() {
    return ownerRID + ":" + label;
  }

  @Override
  public String label() {
    return label;
  }

  @Override
  public YTDBGraph graph() {
    return graph;
  }

  @Override
  public boolean hasProperty(String key) {
    return false;
  }

  @Override
  public boolean removeProperty(String key) {
    return false;
  }

  @Override
  public <V> YTDBProperty<V> property(String key, V value) {
    throw new UnsupportedOperationException("Lightweight edges do not have properties");
  }

  @Override
  public void remove() {
    getOwnerEntity().removeProperty(label);
  }

  @Override
  public Iterator<Vertex> vertices(Direction direction) {
    if (direction == Direction.BOTH) {
      //noinspection unchecked
      return org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.concat(vertices(Direction.IN),
          vertices(Direction.OUT));
    }

    var entity = getOwnerEntity();
    if (entity == null) {
      throw new IllegalStateException("Edge '" + label + "' has been removed");
    }
    if (direction == Direction.IN) {
      if (!entity.hasProperty(label)) {
        throw new IllegalStateException(
            "Edge '" + label + "' not found in vertex " + entity.getIdentity());
      }

      var oppositeLinkName = EntityImpl.getOppositeLinkBagPropertyName(label);
      LinkBag oppositeLinkBag = entity.getProperty(oppositeLinkName);
      if (oppositeLinkBag == null) {
        return IteratorUtils.emptyIterator();
      }

      return org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(
          oppositeLinkBag.iterator(),
          rid -> new YTDBVertexImpl(graph, rid)
      );
    }

    throw new UnsupportedOperationException("Direction " + direction + " is not supported");
  }

  @Override
  public <V> Iterator<Property<V>> properties(String... propertyKeys) {
    return IteratorUtils.emptyIterator();
  }

  public EntityImpl getOwnerEntity() {
    var graphTx = graph.tx();
    var session = graphTx.getDatabaseSession();

    if (fastPathOwnerEntity.isNotBound(session)) {
      var tx = session.getActiveTransaction();

      var entity = threadLocalOwnerEntity.get();
      if (entity == null) {
        entity = (EntityImpl) tx.loadEntity(ownerRID);
        threadLocalOwnerEntity.set(entity);

        return entity;
      }

      if (entity.isNotBound(session)) {
        entity = tx.load(entity);
        threadLocalOwnerEntity.set(entity);
      }

      return entity;
    } else {
      return fastPathOwnerEntity;
    }
  }
}
