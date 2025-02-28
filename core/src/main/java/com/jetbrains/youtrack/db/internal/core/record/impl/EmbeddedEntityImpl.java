package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import java.lang.ref.WeakReference;

public class EmbeddedEntityImpl extends EntityImpl {

  public EmbeddedEntityImpl(String clazz, DatabaseSessionInternal session) {
    super(session, clazz);
    checkEmbeddable();
  }

  public EmbeddedEntityImpl(DatabaseSessionInternal session) {
    super(session);
    status = STATUS.LOADED;
  }

  @Override
  public boolean isEmbedded() {
    return true;
  }


  @Override
  public void setOwner(RecordElement iOwner) {
    checkForBinding();

    if (iOwner == null) {
      return;
    }

    this.owner = new WeakReference<>(iOwner);
  }

  @Override
  public boolean isVertex() {
    return false;
  }

  @Override
  public boolean isStatefulEdge() {
    return false;
  }

  @Override
  public void unload() {
    throw new UnsupportedOperationException("Cannot unload embedded entity");
  }

  @Override
  public EntityImpl reset() {
    throw new UnsupportedOperationException("Cannot reset embedded entity");
  }
}
