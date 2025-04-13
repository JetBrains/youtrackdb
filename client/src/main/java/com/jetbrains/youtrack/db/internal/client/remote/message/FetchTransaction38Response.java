package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.client.remote.message.tx.IndexChange;
import com.jetbrains.youtrack.db.internal.client.remote.message.tx.RecordOperation38Response;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.DocumentSerializerDelta;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37Client;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class FetchTransaction38Response implements BinaryResponse {

  private long txId;
  private List<RecordOperation38Response> operations;
  private List<IndexChange> indexChanges;

  public FetchTransaction38Response() {
  }

  public FetchTransaction38Response(
      DatabaseSessionInternal session, long txId,
      Iterable<RecordOperation> operations,
      Map<String, FrontendTransactionIndexChanges> indexChanges,
      Map<RecordId, RecordId> updatedRids,
      DatabaseSessionInternal database) {
    // In some cases the reference are update twice is not yet possible to guess what is the id in
    // the client

    this.txId = txId;
    this.indexChanges = new ArrayList<>();
    List<RecordOperation38Response> netOperations = new ArrayList<>();
    for (RecordOperation txEntry : operations) {
      RecordOperation38Response request = new RecordOperation38Response();
      request.setType(txEntry.type);
      request.setVersion(txEntry.record.getVersion());
      request.setId(txEntry.getRecordId());
      var oldID = updatedRids.get(txEntry.getRecordId());
      request.setOldId(oldID != null ? oldID : txEntry.getRecordId());
      request.setRecordType(RecordInternal.getRecordType(txEntry.record));
      if (txEntry.type == RecordOperation.UPDATED
          && txEntry.record instanceof EntityImpl entity) {
        var result =
            database.getStorage()
                .readRecord(database, (RecordId) entity.getIdentity(), false, false, null);

        EntityImpl entityFromPersistence = new EntityImpl(entity.getIdentity());
        entityFromPersistence.fromStream(result.buffer);
        request.setOriginal(
            RecordSerializerNetworkV37Client.INSTANCE.toStream(session, entityFromPersistence));
        DocumentSerializerDelta delta = DocumentSerializerDelta.instance();
        request.setRecord(delta.serializeDelta(session, entity));
      } else {
        request.setRecord(
            RecordSerializerNetworkV37Client.INSTANCE.toStream(session, txEntry.record));
      }
      request.setContentChanged(RecordInternal.isContentChanged(txEntry.record));
      netOperations.add(request);
    }
    this.operations = netOperations;

    for (Map.Entry<String, FrontendTransactionIndexChanges> change : indexChanges.entrySet()) {
      this.indexChanges.add(new IndexChange(change.getKey(), change.getValue()));
    }
  }

  @Override
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {
    channel.writeLong(txId);

    for (RecordOperation38Response txEntry : operations) {
      writeTransactionEntry(channel, txEntry, serializer);
    }

    // END OF RECORD ENTRIES
    channel.writeByte((byte) 0);

    // SEND MANUAL INDEX CHANGES
    MessageHelper.writeTransactionIndexChanges(
        channel, (RecordSerializerNetworkV37) serializer, indexChanges);
  }

  static void writeTransactionEntry(
      final ChannelDataOutput iNetwork,
      final RecordOperation38Response txEntry,
      RecordSerializer serializer)
      throws IOException {
    iNetwork.writeByte((byte) 1);
    iNetwork.writeByte(txEntry.getType());
    iNetwork.writeRID(txEntry.getId());
    iNetwork.writeRID(txEntry.getOldId());
    iNetwork.writeByte(txEntry.getRecordType());

    switch (txEntry.getType()) {
      case RecordOperation.CREATED:
        iNetwork.writeBytes(txEntry.getRecord());
        break;

      case RecordOperation.UPDATED:
        iNetwork.writeVersion(txEntry.getVersion());
        iNetwork.writeBytes(txEntry.getOriginal());
        iNetwork.writeBytes(txEntry.getRecord());
        iNetwork.writeBoolean(txEntry.isContentChanged());
        break;

      case RecordOperation.DELETED:
        iNetwork.writeVersion(txEntry.getVersion());
        iNetwork.writeBytes(txEntry.getRecord());
        break;
    }
  }

  @Override
  public void read(DatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    RecordSerializerNetworkV37Client serializer = RecordSerializerNetworkV37Client.INSTANCE;
    txId = network.readLong();
    operations = new ArrayList<>();
    byte hasEntry;
    do {
      hasEntry = network.readByte();
      if (hasEntry == 1) {
        RecordOperation38Response entry = readTransactionEntry(network, serializer);
        operations.add(entry);
      }
    } while (hasEntry == 1);

    // RECEIVE MANUAL INDEX CHANGES
    this.indexChanges = MessageHelper.readTransactionIndexChanges(db, network, serializer);
  }

  static RecordOperation38Response readTransactionEntry(
      ChannelDataInput channel, RecordSerializer ser) throws IOException {
    RecordOperation38Response entry = new RecordOperation38Response();
    entry.setType(channel.readByte());
    entry.setId(channel.readRID());
    entry.setOldId(channel.readRID());
    entry.setRecordType(channel.readByte());
    switch (entry.getType()) {
      case RecordOperation.CREATED:
        entry.setRecord(channel.readBytes());
        break;
      case RecordOperation.UPDATED:
        entry.setVersion(channel.readVersion());
        entry.setOriginal(channel.readBytes());
        entry.setRecord(channel.readBytes());
        entry.setContentChanged(channel.readBoolean());
        break;
      case RecordOperation.DELETED:
        entry.setVersion(channel.readVersion());
        entry.setRecord(channel.readBytes());
        break;
      default:
        break;
    }
    return entry;
  }

  public long getTxId() {
    return txId;
  }

  public List<RecordOperation38Response> getOperations() {
    return operations;
  }

  public List<IndexChange> getIndexChanges() {
    return indexChanges;
  }
}
