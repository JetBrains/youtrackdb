/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.api.exception;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrack.db.internal.common.exception.ErrorCode;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import java.util.Locale;
import java.util.Objects;

/**
 * Exception thrown when MVCC is enabled and a record cannot be updated or deleted because versions
 * don't match.
 */
public class ConcurrentModificationException extends NeedRetryException
    implements HighLevelException {

  private static final long serialVersionUID = 1L;

  private RID rid;
  private int databaseVersion = 0;
  private int recordVersion = 0;
  private int recordOperation;

  public ConcurrentModificationException(ConcurrentModificationException exception) {
    super(exception, ErrorCode.MVCC_ERROR);

    this.rid = exception.rid;
    this.recordVersion = exception.recordVersion;
    this.databaseVersion = exception.databaseVersion;
    this.recordOperation = exception.recordOperation;
  }

  protected ConcurrentModificationException(String dbName, final String message) {
    super(dbName, message);
  }

  public ConcurrentModificationException(
      String dbName, final RID iRID,
      final int iDatabaseVersion,
      final int iRecordVersion,
      final int iRecordOperation) {
    super(dbName,
        makeMessage(iRecordOperation, iRID, iDatabaseVersion, iRecordVersion),
        ErrorCode.MVCC_ERROR);

    rid = iRID;
    databaseVersion = iDatabaseVersion;
    recordVersion = iRecordVersion;
    recordOperation = iRecordOperation;
  }

  @Override
  public boolean equals(final Object obj) {
    if (!(obj instanceof ConcurrentModificationException other)) {
      return false;
    }

    if (recordOperation == other.recordOperation && rid.equals(other.rid)) {
      if (databaseVersion == other.databaseVersion) {
        return recordOperation == other.recordOperation;
      }
    }

    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(rid, databaseVersion, recordVersion, recordOperation);
  }

  public int getEnhancedDatabaseVersion() {
    return databaseVersion;
  }

  public int getEnhancedRecordVersion() {
    return recordVersion;
  }

  public RID getRid() {
    return rid;
  }

  private static String makeMessage(
      int recordOperation, RID rid, int databaseVersion, int recordVersion) {
    final var operation = RecordOperation.getName(recordOperation);

    var sb =
        "Cannot "
            + operation
            + " the record "
            + rid
            + " because the version is not the latest. Probably you are "
            + operation.toLowerCase(Locale.ENGLISH).substring(0, operation.length() - 1)
            + "ing an old record or it has been modified by another user (db=v"
            + databaseVersion
            + " your=v"
            + recordVersion
            + ")";
    return sb;
  }
}
