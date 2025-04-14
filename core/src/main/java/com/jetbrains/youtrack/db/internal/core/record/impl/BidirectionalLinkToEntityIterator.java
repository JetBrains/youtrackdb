package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Relation;
import java.util.Iterator;

public class BidirectionalLinkToEntityIterator<T extends Entity> implements Iterator<T> {

  private final Iterator<? extends Relation<T>> linksIterator;
  private final Direction direction;

  public BidirectionalLinkToEntityIterator(Iterator<? extends Relation<T>> iterator,
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
      case OUT -> edge.toEntity();
      case IN -> edge.fromEntity();
      default -> throw new IllegalStateException("Unexpected direction: " + direction);
    };
  }
}
