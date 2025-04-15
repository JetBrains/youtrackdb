package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.MetadataUpdateListener;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;

class QueryMetadataUpdateListener implements MetadataUpdateListener {

  private boolean updated = false;

  @Override
  public void onSchemaUpdate(DatabaseSessionInternal session, String databaseName,
      SchemaShared schema) {
    updated = true;
  }

  @Override
  public void onSequenceLibraryUpdate(DatabaseSessionInternal session, String databaseName) {
    updated = true;
  }

  @Override
  public void onStorageConfigurationUpdate(String databaseName, StorageConfiguration update) {
    updated = true;
  }

  @Override
  public void onIndexManagerUpdate(DatabaseSessionInternal session, String databaseName,
      IndexManagerAbstract indexManager) {
    updated = true;
  }

  @Override
  public void onFunctionLibraryUpdate(DatabaseSessionInternal session, String databaseName) {
    updated = true;
  }

  public boolean isUpdated() {
    return updated;
  }
}
