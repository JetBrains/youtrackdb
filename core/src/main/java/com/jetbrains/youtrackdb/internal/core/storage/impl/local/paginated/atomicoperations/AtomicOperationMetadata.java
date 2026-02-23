package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import java.io.Serializable;

/**
 * Basic interface for any kind of metadata which may be stored as part of atomic operation.
 *
 * <p>All metadata are associated with key, if metadata with the same key is put inside of atomic
 * operation previous instance of metadata will be overwritten.
 *
 * <p>To add metadata inside of atomic operation use {@link
 * AtomicOperationBinaryTracking#addMetadata(AtomicOperationMetadata)}.
 *
 * <p>To read metadata from atomic operation use {@link
 * AtomicOperationBinaryTracking#getMetadata(java.lang.String)}
 *
 * <p>If you wish to read metadata stored inside of atomic operation you may read them from {@link
 * AtomicUnitEndRecord#getAtomicOperationMetadata()}
 *
 * <p>If you add new metadata implementation, you have to add custom serialization method in {@link
 * AtomicUnitEndRecord} class.
 *
 * @param <T> Type of atomic operation metadata.
 */
@SuppressWarnings("SameReturnValue")
public interface AtomicOperationMetadata<T> extends Serializable {

  /**
   * Returns the key associated with this metadata entry.
   *
   * @return Key associated with given metadata
   */
  String getKey();

  /**
   * Returns the value of this metadata entry.
   *
   * @return Metadata value.
   */
  T getValue();
}
