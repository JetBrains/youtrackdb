package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.type.IdentityWrapper;

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
