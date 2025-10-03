package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.MetadataUpdateListener;

class QueryMetadataUpdateListener implements MetadataUpdateListener {

  private boolean updated = false;

  @Override
  public void onSchemaUpdate(DatabaseSessionInternal session, String databaseName) {
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
  public void onFunctionLibraryUpdate(DatabaseSessionInternal session, String databaseName) {
    updated = true;
  }

  public boolean isUpdated() {
    return updated;
  }
}
