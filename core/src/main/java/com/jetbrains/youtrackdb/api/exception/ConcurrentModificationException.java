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
package com.jetbrains.youtrackdb.api.exception;

import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.common.exception.ErrorCode;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
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

    final var sb =
        new StringBuilder()
            .append("Cannot ").append(operation)
            .append(" the record ").append(rid)
            .append(" because ");

    if (databaseVersion < 0) {
      sb.append("it does not exist in the database.");
    } else {
      sb.append("the version is not the latest.");
    }

    sb.append(" Probably you are ")
        .append(operation.toLowerCase(Locale.ENGLISH), 0, operation.length() - 1).append("ing ");

    if (databaseVersion < 0) {
      sb.append("a record that has been deleted by another user (your=v").append(recordVersion)
          .append(")");
    } else {
      sb.append("an old record or it has been modified by another user (db=v")
          .append(databaseVersion).append(" your=v").append(recordVersion).append(")");
    }

    return sb.toString();
  }
}
