package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;

/**
 *
 */
public class UpdatableResult extends ResultInternal {
  protected ResultInternal previousValue = null;

  public UpdatableResult(DatabaseSessionInternal session, Entity entity) {
    super(session, entity);
  }

  @Override
  public void setProperty(String name, Object value) {
    assert session == null || session.assertIfNotActive();
    castToEntity().setProperty(name, value);
  }

  public void removeProperty(String name) {
    assert session == null || session.assertIfNotActive();
    castToEntity().removeProperty(name);
  }
}
