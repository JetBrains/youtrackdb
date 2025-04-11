package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.type.IdentityWrapper;

/**
 *
 */
public abstract class Identity extends IdentityWrapper {

  public static final String CLASS_NAME = "OIdentity";

  public Identity(DatabaseSessionInternal session, String iClassName) {
    super(session, iClassName);
  }

  public Identity(DatabaseSessionInternal db,
      EntityImpl entity) {
    super(entity);
  }
}
