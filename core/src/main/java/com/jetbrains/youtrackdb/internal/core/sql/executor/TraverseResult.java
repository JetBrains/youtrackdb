package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import javax.annotation.Nonnull;

/**
 *
 */
public class TraverseResult extends ResultInternal {

  protected Integer depth;

  public TraverseResult(DatabaseSessionEmbedded session) {
    super(session);
  }

  public TraverseResult(DatabaseSessionEmbedded db, Identifiable element) {
    super(db, element);
  }

  @Override
  public <T> T getProperty(@Nonnull String name) {
    assert session == null || session.assertIfNotActive();
    if ("$depth".equalsIgnoreCase(name)) {
      return (T) depth;
    }
    return super.getProperty(name);
  }

  @Override
  public void setProperty(@Nonnull String name, Object value) {
    assert session == null || session.assertIfNotActive();
    if ("$depth".equalsIgnoreCase(name)) {
      if (value instanceof Number) {
        depth = ((Number) value).intValue();
      }
    } else {
      super.setProperty(name, value);
    }
  }
}
