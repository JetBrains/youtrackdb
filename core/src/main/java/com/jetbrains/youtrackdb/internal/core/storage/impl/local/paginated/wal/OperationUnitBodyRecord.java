package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

/**
 * href="mailto:lomakin.andrey@gmail.com">Andrey Lomakin</a>
 *
 * @since 31/12/14
 */
public abstract class OperationUnitBodyRecord extends OperationUnitRecord {

  protected OperationUnitBodyRecord() {
  }

  protected OperationUnitBodyRecord(long operationUnitId) {
    super(operationUnitId);
  }
}
