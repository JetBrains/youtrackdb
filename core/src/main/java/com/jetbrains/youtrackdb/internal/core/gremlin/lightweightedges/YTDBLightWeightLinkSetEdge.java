package com.jetbrains.youtrackdb.internal.core.gremlin.lightweightedges;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.jspecify.annotations.NonNull;

public final class YTDBLightWeightLinkSetEdge extends YTDBLightweightEdgeAbstract {
  public YTDBLightWeightLinkSetEdge(
      @NonNull YTDBGraphInternal graph,
      @NonNull EntityImpl owner,
      @NonNull String label) {
    super(graph, owner, label);
  }

  @Override
  public Iterator<Vertex> vertices(Direction direction) {
    if (direction == Direction.OUT) {
      var entity = getOwnerEntity();

      if (entity == null) {
        throw new IllegalStateException("Edge '" + label + "' has been removed");
      }

      var linkSet = entity.getLinkSet(label);

      if (linkSet == null) {
        throw new IllegalStateException(
            "Edge '" + label + "' not found in vertex " + getOwnerEntity().getIdentity());
      }

      return IteratorUtils.map(linkSet.iterator(),
          rid -> new YTDBVertexImpl(graph, rid.getIdentity()));
    }

    return super.vertices(direction);
  }
}
