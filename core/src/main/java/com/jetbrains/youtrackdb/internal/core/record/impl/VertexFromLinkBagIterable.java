package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.util.Iterator;
import javax.annotation.Nonnull;

/**
 * Iterable that wraps a LinkBag and yields vertices directly from the secondary RIDs
 * stored in each RidPair, without loading intermediate edge records. This provides an
 * optimized path for {@code getVertices()} when the underlying storage is a LinkBag
 * with double-sided entries.
 */
public class VertexFromLinkBagIterable implements Iterable<Vertex>, Sizeable {

  @Nonnull
  private final LinkBag linkBag;
  @Nonnull
  private final DatabaseSessionEmbedded session;

  public VertexFromLinkBagIterable(
      @Nonnull LinkBag linkBag,
      @Nonnull DatabaseSessionEmbedded session) {
    this.linkBag = linkBag;
    this.session = session;
  }

  @Nonnull
  @Override
  public Iterator<Vertex> iterator() {
    return new VertexFromLinkBagIterator(
        linkBag.iterator(), session, linkBag.size());
  }

  @Override
  public int size() {
    return linkBag.size();
  }

  @Override
  public boolean isSizeable() {
    return linkBag.isSizeable();
  }
}
