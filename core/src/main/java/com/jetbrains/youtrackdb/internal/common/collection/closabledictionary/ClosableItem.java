package com.jetbrains.youtrackdb.internal.common.collection.closabledictionary;

/**
 * Item is going to be stored inside of {@link ClosableLinkedContainer}. This interface presents
 * item that may be in two states open and closed.
 */
public interface ClosableItem {

  boolean isOpen();

  void close();

  /** Eviction may release a file whose synchronization failed. */
  default void closeForEviction() {
    close();
  }

  /** Whether closing a closed item must reopen it to retry pending synchronization. */
  default boolean needsSynchronizationOnClose() {
    return false;
  }

  void open();
}
