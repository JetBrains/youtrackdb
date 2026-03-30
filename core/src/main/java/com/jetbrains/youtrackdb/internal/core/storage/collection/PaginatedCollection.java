package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.io.IOException;
import javax.annotation.Nonnull;

public abstract class PaginatedCollection extends StorageComponent implements StorageCollection {

  public enum RECORD_STATUS {
    NOT_EXISTENT, PRESENT, ALLOCATED, REMOVED
  }

  public static final String DEF_EXTENSION = ".pcl";

  @SuppressWarnings("SameReturnValue")
  public static int getLatestBinaryVersion() {
    return 3;
  }

  protected PaginatedCollection(
      final AbstractStorage storage,
      final String name,
      final String extension,
      final String lockName,
      final boolean durable) {
    super(storage, name, extension, lockName, durable);
  }

  public abstract RECORD_STATUS getRecordStatus(final long collectionPosition,
      @Nonnull AtomicOperation atomicOperation) throws IOException;

  public abstract StoragePaginatedCollectionConfiguration generateCollectionConfig();

  public abstract long getFileId();
}
