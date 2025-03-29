package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import java.util.Iterator;

public class BidirectionalLinkToEntityIterator<T extends Entity> implements Iterator<T> {

  private final Iterator<BidirectionalLink<T>> linksIterator;
  private final Direction direction;

  public BidirectionalLinkToEntityIterator(Iterator<BidirectionalLink<T>> iterator,
      Direction direction) {
    if (direction == Direction.BOTH) {
      throw new IllegalArgumentException(
          "edge to vertex iterator does not support BOTH as direction");
    }
    this.linksIterator = iterator;
    this.direction = direction;
  }

  @Override
  public boolean hasNext() {
    return linksIterator.hasNext();
  }

  @Override
  public T next() {
    var edge = linksIterator.next();
    return switch (direction) {
      case OUT -> edge.getToEntity();
      case IN -> edge.getFromEntity();
      default -> throw new IllegalStateException("Unexpected direction: " + direction);
    };
  }
}
