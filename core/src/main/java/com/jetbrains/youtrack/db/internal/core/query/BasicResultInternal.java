package com.jetbrains.youtrack.db.internal.core.query;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.record.RID;
import javax.annotation.Nonnull;

public interface BasicResultInternal extends BasicResult {

  void setProperty(@Nonnull String name, Object value);

  void setMetadata(String key, Object value);

  void setIdentity(@Nonnull RID identity);
}