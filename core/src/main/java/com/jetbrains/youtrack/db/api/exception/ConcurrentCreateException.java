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
import java.util.Objects;

/**
 * Exception thrown when a create operation get a non expected RID. This could happen with
 * distributed inserts. The client should retry to re-execute the operation.
 */
public class ConcurrentCreateException extends NeedRetryException implements
    HighLevelException {

  private RID expectedRid;
  private RID actualRid;

  public ConcurrentCreateException(ConcurrentCreateException exception) {
    super(exception, null);

    this.expectedRid = exception.expectedRid;
    this.actualRid = exception.actualRid;
  }

  protected ConcurrentCreateException(String dbName, final String message) {
    super(dbName, message);
  }

  public ConcurrentCreateException(String dbName, final RID expectedRID, final RID actualRid) {
    super(dbName, makeMessage(expectedRID, actualRid));

    this.expectedRid = expectedRID;
    this.actualRid = actualRid;
  }

  @Override
  public boolean equals(final Object obj) {
    if (!(obj instanceof ConcurrentCreateException other)) {
      return false;
    }

    return expectedRid.equals(other.expectedRid) && actualRid.equals(other.actualRid);
  }

  @Override
  public int hashCode() {
    return Objects.hash(expectedRid, actualRid);
  }

  public RID getExpectedRid() {
    return expectedRid;
  }

  public RID getActualRid() {
    return actualRid;
  }

  private static String makeMessage(RID expectedRid, RID actualRid) {
    var sb =
        "Cannot create the record "
            + expectedRid
            + " because the assigned RID was "
            + actualRid
            + " instead";
    return sb;
  }
}
