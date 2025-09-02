package com.jetbrains.youtrackdb.internal.common.collection.closabledictionary;

/**
 * Item is going to be stored inside of {@link ClosableLinkedContainer}. This interface presents
 * item that may be in two states open and closed.
 */
public interface ClosableItem {

  boolean isOpen();

  void close();

  void open();
}
