package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
import java.util.Collection;
import java.util.Iterator;
import javax.annotation.Nonnull;

/**
 *
 */
public class BidirectionalLinksIterable<T extends Entity> implements Iterable<T>, Sizeable {

  private final Iterable<? extends Relation<T>> links;
  private final Direction direction;

  public BidirectionalLinksIterable(Iterable<? extends Relation<T>> links,
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
