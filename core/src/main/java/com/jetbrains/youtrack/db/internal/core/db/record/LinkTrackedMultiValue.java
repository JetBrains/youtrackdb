package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import javax.annotation.Nullable;

public interface LinkTrackedMultiValue<K> extends TrackedMultiValue<K, Identifiable> {
  default void checkValue(Identifiable value) {
    if (value instanceof Entity entity && entity.isEmbedded()) {
      throw new SchemaException(
          "Cannot add an embedded entity to a link based data container");
    }
    if (!(value instanceof Identifiable)) {
      throw new SchemaException(
          "Cannot add a non-identifiable entity to a link based data container");
    }
  }

  @Nullable
  default RID convertToRid(Identifiable e) {
    if (e == null) {
      return null;
    }

    if (e instanceof DBRecord record && record.isUnloaded()) {
      throw new IllegalArgumentException(
          "Record  " + record + "is unloaded and can not be processed");
    }

    var rid = e.getIdentity();
    if (rid.isPersistent()) {
      return rid;
    }

    var session = getSession();
    if (session == null) {
      throw new IllegalStateException(
          "Cannot add an identifiable to collection that is not attached to a session");
    }
    return session.refreshRid(e.getIdentity());
  }


  @Override
  default boolean isEmbeddedContainer() {
    return false;
  }

  static void checkEntityAsOwner(RecordElement newOwner) {
    if (newOwner == null) {
      return;
    }
    if (newOwner instanceof Entity entity) {
      if (entity.isEmbedded()) {
        throw new IllegalArgumentException(
            "Link based collection can only be set to an high-level entity");
      }
    } else {
      throw new IllegalArgumentException(
          "Link based collection can only be set to an high-level entity");
    }
  }
}
