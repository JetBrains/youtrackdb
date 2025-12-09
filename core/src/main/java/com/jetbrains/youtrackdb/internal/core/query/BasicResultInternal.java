package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nonnull;

public interface BasicResultInternal extends BasicResult {

  void setProperty(@Nonnull String name, Object value);

  void setMetadata(String key, Object value);

  void setIdentity(@Nonnull RID identity);
}