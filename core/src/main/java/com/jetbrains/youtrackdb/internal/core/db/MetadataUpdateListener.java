package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerAbstract;

public interface MetadataUpdateListener {
  void onSchemaUpdate(DatabaseSessionInternal session, String databaseName);

  void onSequenceLibraryUpdate(DatabaseSessionInternal session, String databaseName);

  void onStorageConfigurationUpdate(String databaseName,
      StorageConfiguration update);

  void onIndexManagerUpdate(DatabaseSessionInternal session, String databaseName,
      IndexManagerAbstract indexManager);

  void onFunctionLibraryUpdate(DatabaseSessionInternal session, String databaseName);
}
