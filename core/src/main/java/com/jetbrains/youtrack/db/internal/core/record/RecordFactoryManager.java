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

import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.internal.common.exception.SystemException;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.RecordBytes;
import com.jetbrains.youtrack.db.internal.core.record.impl.StatefullEdgeEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexEntityImpl;

/**
 * Record factory. To use your own record implementation use the declareRecordType() method. Example
 * of registration of the record MyRecord:
 *
 * <p><code>
 * declareRecordType('m', "myrecord", MyRecord.class);
 * </code>
 */
@SuppressWarnings("unchecked")
public class RecordFactoryManager {

  protected final String[] recordTypeNames = new String[Byte.MAX_VALUE];
  protected final Class<? extends DBRecord>[] recordTypes = new Class[Byte.MAX_VALUE];
  protected final RecordFactory[] recordFactories = new RecordFactory[Byte.MAX_VALUE];

  public interface RecordFactory {

    DBRecord newRecord(RecordId rid, DatabaseSessionInternal database);
  }

  public RecordFactoryManager() {
    declareRecordType(
        EntityImpl.RECORD_TYPE,
        "entity",
        EntityImpl.class, (rid, database) -> new EntityImpl(database, rid));
    declareRecordType(VertexEntityImpl.RECORD_TYPE,
        "vertex",
        VertexEntityImpl.class,
        (rid, database) -> new VertexEntityImpl(database, rid));
    declareRecordType(StatefullEdgeEntityImpl.RECORD_TYPE,
        "statefulEdge",
        StatefullEdgeEntityImpl.class,
        (rid, database) -> new StatefullEdgeEntityImpl(database, rid));
    declareRecordType(
        Blob.RECORD_TYPE, "blob",
        Blob.class, (rid, database) -> new RecordBytes(database, rid));
  }

  public String getRecordTypeName(final byte iRecordType) {
    var name = recordTypeNames[iRecordType];
    if (name == null) {
      throw new IllegalArgumentException("Unsupported record type: " + iRecordType);
    }
    return name;
  }

  public DBRecord newInstance(RecordId rid, DatabaseSessionInternal database) {
    try {
      return getFactory(database.getRecordType()).newRecord(rid, database);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unsupported record type: " + database.getRecordType(), e);
    }
  }

  public RecordAbstract newInstance(
      final byte iRecordType, RecordId rid, DatabaseSessionInternal database) {
    try {
      return (RecordAbstract) getFactory(iRecordType).newRecord(rid, database);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unsupported record type: " + iRecordType, e);
    }
  }

  public void declareRecordType(
      byte iByte, String iName, Class<? extends DBRecord> iClass, final RecordFactory iFactory) {
    if (recordTypes[iByte] != null) {
      throw new SystemException(
          "Record type byte '" + iByte + "' already in use : " + recordTypes[iByte].getName());
    }
    recordTypeNames[iByte] = iName;
    recordTypes[iByte] = iClass;
    recordFactories[iByte] = iFactory;
  }

  protected RecordFactory getFactory(final byte iRecordType) {
    final var factory = recordFactories[iRecordType];
    if (factory == null) {
      throw new IllegalArgumentException("Record type '" + iRecordType + "' is not supported");
    }
    return factory;
  }
}
