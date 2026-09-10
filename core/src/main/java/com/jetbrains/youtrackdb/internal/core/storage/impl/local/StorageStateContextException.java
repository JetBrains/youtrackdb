package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

/** Indicates that the calling thread lacks the storage state protection required by an operation. */
public final class StorageStateContextException extends IllegalStateException {

  public StorageStateContextException(String message) {
    super(message);
  }
}
