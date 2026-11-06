package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

/**
 * Indicates that the calling thread has incompatible storage state protection for an operation.
 * This includes missing required protection and {@link AbstractStorage#close} with force true
 * while already holding protection.
 */
public final class StorageStateContextException extends IllegalStateException {

  public StorageStateContextException(String message) {
    super(message);
  }
}
