package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;

public interface MetadataUpdateListener {
  void onSchemaUpdate(DatabaseSessionInternal session, String databaseName);

  void onSequenceLibraryUpdate(DatabaseSessionInternal session, String databaseName);

  void onStorageConfigurationUpdate(String databaseName,
      StorageConfiguration update);

  void onFunctionLibraryUpdate(DatabaseSessionInternal session, String databaseName);
}
