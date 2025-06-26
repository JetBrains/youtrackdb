package com.jetbrains.youtrack.db.api.remote.query;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import javax.annotation.Nullable;

public interface RemoteResult extends BasicResult {

  boolean isBlob();

  byte[] asBlob();

  @Nullable
  byte[] asBlobOrNull();
}
