package com.jetbrains.youtrackdb.internal.core.gremlin.lightweightedges;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.jspecify.annotations.NonNull;

public final class YTDBLightWeightLinkEdge extends YTDBLightweightEdgeAbstract {
  public YTDBLightWeightLinkEdge(
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

      var link = entity.getLink(label);
      if (link == null) {
        throw new IllegalStateException(
            "Edge '" + label + "' not found in vertex " + entity.getIdentity());
      }

      return IteratorUtils.singletonIterator(new YTDBVertexImpl(graph, link));
    }

    return super.vertices(direction);
  }
}
