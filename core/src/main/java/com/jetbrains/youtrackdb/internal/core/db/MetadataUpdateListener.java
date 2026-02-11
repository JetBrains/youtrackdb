package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;

public interface MetadataUpdateListener {

  void onSchemaUpdate(DatabaseSessionEmbedded session, String databaseName, SchemaShared schema);

  void onSequenceLibraryUpdate(DatabaseSessionEmbedded session, String databaseName);

  void onStorageConfigurationUpdate(String databaseName,
      StorageConfiguration update);

  void onIndexManagerUpdate(DatabaseSessionEmbedded session, String databaseName,
      IndexManagerAbstract indexManager);

  void onFunctionLibraryUpdate(DatabaseSessionEmbedded session, String databaseName);
}
