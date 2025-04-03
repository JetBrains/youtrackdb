package com.jetbrains.youtrack.db.internal.core.storage;

import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import java.util.Set;

public interface StorageInfo {

  // MISC
  StorageConfiguration getConfiguration();

  boolean isAssigningCollectionIds();

  Set<String> getCollectionNames();

  int getCollections();

  String getURL();

  RecordConflictStrategy getRecordConflictStrategy();

  int getCollectionIdByName(String lowerCase);

  String getPhysicalCollectionNameById(int iCollectionId);

  String getName();
}
