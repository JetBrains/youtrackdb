package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.DocumentSerializerDelta;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkDistributed;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Optional;

public class FrontendTransactionDataChange {

  private byte type;
  private byte recordType;
  private RecordId id;
  private Optional<byte[]> record;
  private int version;
  private boolean contentChanged;

  public FrontendTransactionDataChange(DatabaseSessionInternal session, RecordOperation operation) {
    this.type = operation.type;
    var rec = operation.record;
    this.recordType = RecordInternal.getRecordType(rec);
    this.id = rec.getIdentity();
    this.version = rec.getVersion();
    switch (operation.type) {
      case RecordOperation.CREATED:
        this.record = Optional.of(
            RecordSerializerNetworkDistributed.INSTANCE.toStream(session, rec));
        this.contentChanged = RecordInternal.isContentChanged(rec);
        break;
      case RecordOperation.UPDATED:
        if (recordType == EntityImpl.RECORD_TYPE) {
          record = Optional.of(
              DocumentSerializerDelta.instance().serializeDelta(session, (EntityImpl) rec));
        } else {
          record = Optional.of(RecordSerializerNetworkDistributed.INSTANCE.toStream(session, rec));
        }
        this.contentChanged = RecordInternal.isContentChanged(rec);
        break;
      case RecordOperation.DELETED:
        record = Optional.empty();
        break;
    }
  }

  private FrontendTransactionDataChange() {
  }

  public FrontendTransactionDataChange(
      byte type,
      byte recordType,
      RecordId id,
      Optional<byte[]> record,
      int version,
      boolean contentChanged) {
    this.type = type;
    this.recordType = recordType;
    this.id = id;
    this.record = record;
    this.version = version;
    this.contentChanged = contentChanged;
  }

  public void serialize(DataOutput output) throws IOException {
    output.writeByte(type);
    output.writeByte(recordType);
    output.writeInt(id.getClusterId());
    output.writeLong(id.getClusterPosition());
    if (record.isPresent()) {
      output.writeBoolean(true);
      output.writeInt(record.get().length);
      output.write(record.get(), 0, record.get().length);
    } else {
      output.writeBoolean(false);
    }
    output.writeInt(this.version);
    output.writeBoolean(this.contentChanged);
  }

  public static FrontendTransactionDataChange deserialize(DataInput input) throws IOException {
    FrontendTransactionDataChange change = new FrontendTransactionDataChange();
    change.type = input.readByte();
    change.recordType = input.readByte();
    int cluster = input.readInt();
    long position = input.readLong();
    change.id = new RecordId(cluster, position);
    boolean isThereRecord = input.readBoolean();
    if (isThereRecord) {
      int size = input.readInt();
      byte[] record = new byte[size];
      input.readFully(record);
      change.record = Optional.of(record);
    } else {
      change.record = Optional.empty();
    }
    change.version = input.readInt();
    change.contentChanged = input.readBoolean();
    return change;
  }

  public byte getRecordType() {
    return recordType;
  }

  public byte getType() {
    return type;
  }

  public int getVersion() {
    return version;
  }

  public Optional<byte[]> getRecord() {
    return record;
  }

  public boolean isContentChanged() {
    return contentChanged;
  }

  public RecordId getId() {
    return id;
  }
}
