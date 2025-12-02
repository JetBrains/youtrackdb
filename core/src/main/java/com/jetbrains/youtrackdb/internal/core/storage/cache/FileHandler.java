package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.concur.collection.CASObjectArray;

public record FileHandler(long fileId, Object casArray) {

  public static FileHandler SPECIAL_VALUE_RENAME_WHEN_UNDERSTAND_MEANING_OF_IT = new FileHandler(-1, null);

  public FileHandler(long fileId) {
    this(fileId, new CASObjectArray<>());
  }
}
