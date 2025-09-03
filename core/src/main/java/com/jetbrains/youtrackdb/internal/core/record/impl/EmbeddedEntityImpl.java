package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.record.EmbeddedEntity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import java.lang.ref.WeakReference;
import javax.annotation.Nullable;

public class EmbeddedEntityImpl extends EntityImpl implements EmbeddedEntity {
  public EmbeddedEntityImpl(String clazz, DatabaseSessionEmbedded session) {
    super(new ChangeableRecordId(), session, clazz);
    checkEmbeddable();
  }

  public EmbeddedEntityImpl(DatabaseSessionEmbedded session) {
    super(new ChangeableRecordId(), session);
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

  @Nullable
  @Override
  protected String checkPropertyValue(String propertyName, @Nullable Object propertyValue) {

    if (propertyValue instanceof Identifiable && !(propertyValue instanceof EmbeddedEntity)) {
      return "Links can only be used in high-level entities";
    }

    if (propertyValue instanceof TrackedMultiValue<?, ?> mv && !mv.isEmbeddedContainer()) {
      return "Link-based collections can only be used in high-level entities";
    }

    return super.checkPropertyValue(propertyName, propertyValue);
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
