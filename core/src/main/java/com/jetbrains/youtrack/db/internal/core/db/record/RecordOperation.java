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
package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.internal.core.id.ImmutableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;

/**
 * Contains the information about a database operation.
 */
public final class RecordOperation implements Comparable<RecordOperation> {

  public static final byte DELETED = 1;
  public static final byte UPDATED = 2;
  public static final byte CREATED = 3;

  public byte type;
  public final RecordAbstract record;

  public long recordCallBackDirtyCounter;

  // used in processing of server transactions
  public boolean callHooksOnServerTx = false;
  public final ImmutableRecordId initialRecordId;

  public RecordOperation(final RecordAbstract record, final byte status) {
    // CLONE RECORD AND CONTENT
    this.record = record;
    this.type = status;
    this.initialRecordId = new ImmutableRecordId(record.getIdentity());
  }

  @Override
  public int hashCode() {
    return record.getIdentity().hashCode();
  }

  @Override
  public boolean equals(final Object obj) {
    if (!(obj instanceof RecordOperation)) {
      return false;
    }

    return record.equals(((RecordOperation) obj).record);
  }

  @Override
  public String toString() {
    return "RecordOperation [record=" + record + ", type=" + getName(type) + "]";
  }

  public RecordId getRecordId() {
    return record != null ? record.getIdentity() : null;
  }

  public static String getName(final int type) {
    return switch (type) {
      case RecordOperation.CREATED -> "CREATE";
      case RecordOperation.UPDATED -> "UPDATE";
      case RecordOperation.DELETED -> "DELETE";
      default -> "?";
    };
  }

  @Override
  public int compareTo(RecordOperation o) {
    return record.compareTo(o.record);
  }
}
