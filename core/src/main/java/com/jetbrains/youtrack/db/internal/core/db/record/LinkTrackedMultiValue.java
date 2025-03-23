package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import javax.annotation.Nullable;

public interface LinkTrackedMultiValue<K> extends TrackedMultiValue<K, Identifiable> {

  @Nullable
  default Identifiable convertToRid(Identifiable e) {
    if (e == null) {
      return null;
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
    e = session.refreshRid(e.getIdentity());
    return e;
  }

  @Override
  default boolean isLinkCollectionsProhibited() {
    return true;
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
      if (newOwner instanceof TrackedMultiValue<?, ?> trackedMultiValue
          && !trackedMultiValue.isLinkCollectionsProhibited()) {
        return;
      }
      throw new IllegalArgumentException(
          "Link based collection can only be set to an high-level entity");
    }
  }
}
