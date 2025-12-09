package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
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
      case OUT -> edge.getTo();
      case IN -> edge.getFrom();
      default -> throw new IllegalStateException("Unexpected direction: " + direction);
    };
  }
}
