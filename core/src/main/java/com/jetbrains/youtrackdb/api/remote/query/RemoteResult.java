package com.jetbrains.youtrackdb.api.remote.query;

import com.jetbrains.youtrackdb.api.common.query.BasicResult;
import javax.annotation.Nullable;

public interface RemoteResult extends BasicResult {

  boolean isBlob();

  byte[] asBlob();

  @Nullable
  byte[] asBlobOrNull();
}
