package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import java.util.Collection;
import java.util.Iterator;
import javax.annotation.Nonnull;

/**
 *
 */
public class BidirectionalLinksIterable<T extends Entity> implements Iterable<T>, Sizeable {

  private final Iterable<? extends BidirectionalLink<T>> links;
  private final Direction direction;

  public BidirectionalLinksIterable(Iterable<? extends BidirectionalLink<T>> links,
      Direction direction) {
    this.links = links;
    this.direction = direction;
  }

  @Nonnull
  @Override
  public Iterator<T> iterator() {
    return new BidirectionalLinkToEntityIterator<>(links.iterator(), direction);
  }

  @Override
  public int size() {
    switch (links) {
      case null -> {
        return 0;
      }
      case Sizeable sizeable -> {
        return sizeable.size();
      }
      case Collection<?> collection -> {
        return collection.size();
      }
      default -> {
        throw new UnsupportedOperationException("Size is not supported for this type: "
            + links.getClass().getName());
      }
    }
  }

  @Override
  public boolean isSizeable() {
    return links == null || links instanceof Sizeable sizeable && sizeable.isSizeable()
        || links instanceof Collection<?>;
  }
}
