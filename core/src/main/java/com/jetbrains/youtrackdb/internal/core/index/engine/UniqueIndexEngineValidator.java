package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.IndexUnique;

public class UniqueIndexEngineValidator implements IndexEngineValidator<Object, RID> {

  private final IndexUnique indexUnique;

  public UniqueIndexEngineValidator(IndexUnique oIndexUnique) {
    indexUnique = oIndexUnique;
  }

  @Override
  public Object validate(Object key, RID oldValue, RID newValue) {
    if (oldValue != null) {
      // CHECK IF THE ID IS THE SAME OF CURRENT: THIS IS THE UPDATE CASE
      if (!oldValue.equals(newValue)) {
          throw new RecordDuplicatedException(
              null, String.format(
              "Cannot index record %s: found duplicated key '%s' in index '%s' previously"
                  + " assigned to the record %s",
              newValue.getIdentity(), key, indexUnique.getName(), oldValue.getIdentity()),
              indexUnique.getName(),
              oldValue.getIdentity(), key);
      } else {
        return IndexEngineValidator.IGNORE;
      }
    }

    assert newValue.isPersistent();
    return newValue.getIdentity();
  }
}
