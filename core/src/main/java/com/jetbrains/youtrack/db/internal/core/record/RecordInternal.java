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

package com.jetbrains.youtrack.db.internal.core.record;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;

public class RecordInternal {

  /**
   * Internal only. Fills in one shot the record.
   */
  public static RecordAbstract fill(
      final DBRecord record,
      final RID iRid,
      final int iVersion,
      final byte[] iBuffer,
      final boolean iDirty) {
    final var rec = (RecordAbstract) record;
    rec.fill(iRid, iVersion, iBuffer, iDirty);
    return rec;
  }

  public static void checkForBinding(DBRecord record) {
    ((RecordAbstract) record).checkForBinding();
  }

  public static void fromStream(
      final DBRecord record, final byte[] iBuffer) {
    final var rec = (RecordAbstract) record;
    rec.fromStream(iBuffer);
  }

  /**
   * Internal only. Changes the identity of the record.
   */
  public static RecordAbstract setIdentity(final DBRecord record, final RecordId iIdentity) {
    final var rec = (RecordAbstract) record;
    rec.setIdentity(iIdentity);
    return rec;
  }

  /**
   * Internal only. Unsets the dirty status of the record.
   */
  public static void unsetDirty(final DBRecord record) {
    final var rec = (RecordAbstract) record;
    rec.unsetDirty();
  }

  /**
   * Internal only. Sets the version.
   */
  public static void setVersion(final DBRecord record, final int iVersion) {
    final var rec = (RecordAbstract) record;
    rec.setVersion(iVersion);
  }

  /**
   * Internal only. Return the record type.
   */
  public static byte getRecordType(DatabaseSessionInternal db, final DBRecord record) {
    if (record instanceof RecordAbstract) {
      return ((RecordAbstract) record).getRecordType();
    }
    final RecordAbstract rec = record.getRecord(db);
    return rec.getRecordType();
  }

  public static boolean isContentChanged(final DBRecord record) {
    final var rec = (RecordAbstract) record;
    return rec.isContentChanged();
  }

  public static void setContentChanged(final DBRecord record, final boolean changed) {
    final var rec = (RecordAbstract) record;
    rec.setContentChanged(changed);
  }

  public static void clearSource(final DBRecord record) {
    final var rec = (RecordAbstract) record;
    rec.clearSource();
  }

  public static void setRecordSerializer(final DBRecord record,
      final RecordSerializer serializer) {
    ((RecordAbstract) record).recordSerializer = serializer;
  }
}
