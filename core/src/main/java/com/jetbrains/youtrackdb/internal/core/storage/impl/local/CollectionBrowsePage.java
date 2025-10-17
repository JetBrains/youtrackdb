package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;

public class CollectionBrowsePage implements Iterable<CollectionBrowseEntry> {

  private final List<CollectionBrowseEntry> entries;
  private final long lastPosition;

  public CollectionBrowsePage(List<CollectionBrowseEntry> entries) {
    this.entries = entries;
    this.lastPosition = entries.getLast().collectionPosition();
  }

  @Override
  public Iterator<CollectionBrowseEntry> iterator() {
    return entries.iterator();
  }

  @Override
  public Spliterator<CollectionBrowseEntry> spliterator() {
    return entries.spliterator();
  }

  public long getLastPosition() {
    return lastPosition;
  }
}
