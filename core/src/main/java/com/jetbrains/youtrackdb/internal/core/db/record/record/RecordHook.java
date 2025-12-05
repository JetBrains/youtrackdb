package com.jetbrains.youtrackdb.internal.core.db.record.record;

import javax.annotation.Nonnull;

/**
 * Hook interface to catch all events regarding records.
 *
 * @see RecordHookAbstract
 */
public interface RecordHook {

  enum TYPE {
    READ,

    BEFORE_CREATE,
    AFTER_CREATE,

    BEFORE_UPDATE,
    AFTER_UPDATE,

    BEFORE_DELETE,
    AFTER_DELETE,
  }

  default void onUnregister() {
  }

  void onTrigger(@Nonnull TYPE iType, @Nonnull DBRecord iRecord);
}
