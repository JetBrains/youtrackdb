package com.jetbrains.youtrackdb.internal.core.gremlin.lightweightedges;

import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.jspecify.annotations.NonNull;

public final class YTDBLightWeightLinkBagEdge extends YTDBLightweightEdgeAbstract {
  public YTDBLightWeightLinkBagEdge(@NonNull YTDBGraphInternal graph, @NonNull EntityImpl owner,
      @NonNull String label) {
    super(graph, owner, label);
  }

  @Override
  public Iterator<Vertex> vertices(@Nonnull Direction direction) {
    if (direction == Direction.OUT) {
      var entity = getOwnerEntity();
      if (entity == null) {
        throw new IllegalStateException("Edge '" + label + "' has been removed");
      }

      LinkBag linkBag = entity.getProperty(label);

      if (linkBag == null) {
        throw new IllegalStateException(
            "Edge '" + label + "' not found in vertex " + entity.getIdentity());
      }

      return IteratorUtils.map(linkBag.iterator(), rid -> new YTDBVertexImpl(graph, rid));
    }

    return super.vertices(direction);
  }
}
