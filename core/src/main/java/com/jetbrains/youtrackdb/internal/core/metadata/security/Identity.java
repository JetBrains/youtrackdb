package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.type.IdentityWrapper;

/**
 *
 */
public abstract class Identity extends IdentityWrapper {

  public static final String CLASS_NAME = "OIdentity";

  public Identity(DatabaseSessionEmbedded session, String iClassName) {
    super(session, iClassName);
  }

  public Identity(DatabaseSessionEmbedded db,
      EntityImpl entity) {
    super(entity);
  }
}
