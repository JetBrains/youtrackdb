package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.DocumentSerializerDelta;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37Client;

public class NetworkRecordOperation {

  private byte type;
  private byte recordType;
  private RID id;
  private byte[] record;
  private int version;
  private boolean contentChanged;
  private long dirtyCounter;

  public NetworkRecordOperation() {
  }

  public NetworkRecordOperation(DatabaseSessionInternal session, RecordOperation txEntry) {
    this.type = txEntry.type;
    this.version = txEntry.record.getVersion();
    this.id = txEntry.record.getIdentity();
    this.dirtyCounter = txEntry.record.getDirtyCounter();
    this.recordType = txEntry.record.getRecordType();

    switch (txEntry.type) {
      case RecordOperation.CREATED:
        this.contentChanged = txEntry.record.isContentChanged();
        if (txEntry.dirtyCounterOnClientSide == 0) {
          this.record =
              RecordSerializerNetworkV37Client.INSTANCE.toStream(session, txEntry.record);
        } else {
          if (EntityImpl.RECORD_TYPE == txEntry.record.getRecordType()) {
            this.recordType = DocumentSerializerDelta.DELTA_RECORD_TYPE;
            var delta = DocumentSerializerDelta.instance();
            this.record = delta.serializeDelta(session, (EntityImpl) txEntry.record);
          } else {
            this.recordType = txEntry.record.getRecordType();
            this.record =
                RecordSerializerNetworkV37.INSTANCE.toStream(session, txEntry.record);
          }
        }
        break;
      case RecordOperation.UPDATED:
        this.contentChanged = txEntry.record.isContentChanged();
        if (EntityImpl.RECORD_TYPE == txEntry.record.getRecordType()) {
          if (session.isRemote()) {
            this.recordType = DocumentSerializerDelta.DELTA_RECORD_TYPE;
            var delta = DocumentSerializerDelta.instance();
            this.record = delta.serializeDelta(session, (EntityImpl) txEntry.record);
          } else {
            if (txEntry.dirtyCounterOnClientSide == 0) {
              this.recordType = txEntry.record.getRecordType();
              this.record =
                  RecordSerializerNetworkV37.INSTANCE.toStream(session, txEntry.record);
            } else {
              this.recordType = DocumentSerializerDelta.DELTA_RECORD_TYPE;
              var delta = DocumentSerializerDelta.instance();
              this.record = delta.serializeDelta(session, (EntityImpl) txEntry.record);
            }
          }
        } else {
          this.recordType = txEntry.record.getRecordType();
          this.record =
              RecordSerializerNetworkV37.INSTANCE.toStream(session, txEntry.record);
        }
        break;
    }
  }


  public long getDirtyCounter() {
    return dirtyCounter;
  }

  public RID getId() {
    return id;
  }

  public void setId(RID id) {
    this.id = id;
  }


  public void setDirtyCounter(long dirtyCounter) {
    this.dirtyCounter = dirtyCounter;
  }

  public byte[] getRecord() {
    return record;
  }

  public void setRecord(byte[] record) {
    this.record = record;
  }

  public byte getRecordType() {
    return recordType;
  }

  public void setRecordType(byte recordType) {
    this.recordType = recordType;
  }

  public byte getType() {
    return type;
  }

  public void setType(byte type) {
    this.type = type;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public void setContentChanged(boolean contentChanged) {
    this.contentChanged = contentChanged;
  }

  public boolean isContentChanged() {
    return contentChanged;
  }
}
