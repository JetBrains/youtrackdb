package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
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
