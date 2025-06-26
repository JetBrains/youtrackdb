package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;

public interface MetadataUpdateListener {

  void onSchemaUpdate(DatabaseSessionInternal session, String databaseName, SchemaShared schema);

  void onSequenceLibraryUpdate(DatabaseSessionInternal session, String databaseName);

  void onStorageConfigurationUpdate(String databaseName,
      StorageConfiguration update);

  void onIndexManagerUpdate(DatabaseSessionInternal session, String databaseName,
      IndexManagerAbstract indexManager);

  void onFunctionLibraryUpdate(DatabaseSessionInternal session, String databaseName);
}
