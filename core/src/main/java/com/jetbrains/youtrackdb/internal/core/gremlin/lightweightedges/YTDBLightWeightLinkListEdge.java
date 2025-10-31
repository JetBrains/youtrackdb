package com.jetbrains.youtrackdb.internal.core.gremlin.lightweightedges;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.jspecify.annotations.NonNull;

public class YTDBLightWeightLinkListEdge extends YTDBLightweightEdgeAbstract {
  public YTDBLightWeightLinkListEdge(
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

      var linkList = entity.getLinkList(label);
      if (linkList == null) {
        throw new IllegalStateException(
            "Edge '" + label + "' not found in vertex " + entity.getIdentity());
      }

      return IteratorUtils.map(linkList.iterator(),
          rid -> new YTDBVertexImpl(graph, rid.getIdentity()));
    }

    return super.vertices(direction);
  }
}
