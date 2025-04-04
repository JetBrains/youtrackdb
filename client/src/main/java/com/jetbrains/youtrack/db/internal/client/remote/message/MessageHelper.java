package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.client.remote.CollectionNetworkSerializer;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement.STATUS;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary.ResultSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.core.tx.NetworkRecordOperation;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

public class MessageHelper {

  public static void writeIdentifiable(
      DatabaseSessionInternal session, ChannelDataOutput channel, final Identifiable o)
      throws IOException {
    if (o == null) {
      channel.writeShort(ChannelBinaryProtocol.RECORD_NULL);
    } else if (o instanceof RecordId) {
      channel.writeShort(ChannelBinaryProtocol.RECORD_RID);
      channel.writeRID((RID) o);
    } else {
      var record = (RecordAbstract) o;
      if (record.isEmbedded()) {
        writeProjection(session, record.asEntity().detach(), channel);
      }

      writeIdentifiable(session, channel, record.getIdentity());
    }
  }

  public static void writeRecord(
      DatabaseSessionInternal session, ChannelDataOutput channel, RecordAbstract iRecord,
      RecordSerializer serializer)
      throws IOException {
    channel.writeShort((short) 0);
    channel.writeByte(iRecord.getRecordType());
    channel.writeRID(iRecord.getIdentity());
    channel.writeVersion(iRecord.getVersion());
    try {
      final var stream = getRecordBytes(session, iRecord, serializer);
      channel.writeBytes(stream);
    } catch (Exception e) {
      channel.writeBytes(null);
      final var message =
          "Error on marshalling record " + iRecord.getIdentity() + " (" + e + ")";

      throw BaseException.wrapException(new SerializationException(session, message), e, session);
    }
  }

  public static byte[] getRecordBytes(@Nullable DatabaseSessionInternal session,
      final RecordAbstract iRecord, RecordSerializer serializer) {
    final byte[] stream;
    String dbSerializerName = null;
    if (session != null) {
      dbSerializerName = session.getSerializer().toString();
    }
    if (EntityHelper.isEntity(iRecord.getRecordType())
        && (dbSerializerName == null || !dbSerializerName.equals(serializer.toString()))) {
      ((EntityImpl) iRecord).deserializeProperties();
      stream = serializer.toStream(session, iRecord);
    } else {
      stream = iRecord.toStream();
    }

    return stream;
  }

  public static Map<UUID, LinkBagPointer> readCollectionChanges(ChannelDataInput network)
      throws IOException {
    Map<UUID, LinkBagPointer> collectionsUpdates = new HashMap<>();
    var count = network.readInt();
    for (var i = 0; i < count; i++) {
      final var mBitsOfId = network.readLong();
      final var lBitsOfId = network.readLong();

      final var pointer =
          CollectionNetworkSerializer.readCollectionPointer(network);

      collectionsUpdates.put(new UUID(mBitsOfId, lBitsOfId), pointer);
    }
    return collectionsUpdates;
  }

  public static void writeCollectionChanges(
      ChannelDataOutput channel, Map<UUID, LinkBagPointer> changedIds)
      throws IOException {
    channel.writeInt(changedIds.size());
    for (var entry : changedIds.entrySet()) {
      channel.writeLong(entry.getKey().getMostSignificantBits());
      channel.writeLong(entry.getKey().getLeastSignificantBits());
      CollectionNetworkSerializer.writeCollectionPointer(channel, entry.getValue());
    }
  }

  public static void writePhysicalPositions(
      ChannelDataOutput channel, PhysicalPosition[] previousPositions) throws IOException {
    if (previousPositions == null) {
      channel.writeInt(0); // NO ENTRIEs
    } else {
      channel.writeInt(previousPositions.length);

      for (final var physicalPosition : previousPositions) {
        channel.writeLong(physicalPosition.collectionPosition);
        channel.writeInt(physicalPosition.recordSize);
        channel.writeVersion(physicalPosition.recordVersion);
      }
    }
  }

  public static PhysicalPosition[] readPhysicalPositions(ChannelDataInput network)
      throws IOException {
    final var positionsCount = network.readInt();
    final PhysicalPosition[] physicalPositions;
    if (positionsCount == 0) {
      physicalPositions = CommonConst.EMPTY_PHYSICAL_POSITIONS_ARRAY;
    } else {
      physicalPositions = new PhysicalPosition[positionsCount];

      for (var i = 0; i < physicalPositions.length; i++) {
        final var position = new PhysicalPosition();

        position.collectionPosition = network.readLong();
        position.recordSize = network.readInt();
        position.recordVersion = network.readVersion();

        physicalPositions[i] = position;
      }
    }
    return physicalPositions;
  }

  public static RawPair<String[], int[]> readCollectionsArray(final ChannelDataInput network)
      throws IOException {
    final int tot = network.readShort();
    final var collectionNames = new String[tot];
    final var collectionIds = new int[tot];

    for (var i = 0; i < tot; ++i) {
      var collectionName = network.readString().toLowerCase(Locale.ENGLISH);
      final int collectionId = network.readShort();
      collectionNames[i] = collectionName;
      collectionIds[i] = collectionId;
    }

    return new RawPair<>(collectionNames, collectionIds);
  }

  public static void writeCollectionsArray(
      ChannelDataOutput channel, RawPair<String[], int[]> collections, int protocolVersion)
      throws IOException {
    final var collectionNames = collections.first();
    final var collectionIds = collections.second();

    channel.writeShort((short) collectionNames.length);

    for (var i = 0; i < collectionNames.length; i++) {
      channel.writeString(collectionNames[i]);
      channel.writeShort((short) collectionIds[i]);
    }
  }

  public static void writeTransactionEntry(
      final DataOutput iNetwork, final NetworkRecordOperation txEntry) throws IOException {
    iNetwork.writeByte(txEntry.getType());
    iNetwork.writeInt(txEntry.getId().getCollectionId());
    iNetwork.writeLong(txEntry.getId().getCollectionPosition());
    iNetwork.writeLong(txEntry.getDirtyCounter());
    iNetwork.writeByte(txEntry.getRecordType());

    switch (txEntry.getType()) {
      case RecordOperation.CREATED:
        var record = txEntry.getRecord();
        iNetwork.writeInt(record.length);
        iNetwork.write(record);
        break;

      case RecordOperation.UPDATED:
        iNetwork.writeInt(txEntry.getVersion());
        var record2 = txEntry.getRecord();
        iNetwork.writeInt(record2.length);
        iNetwork.write(record2);
        iNetwork.writeBoolean(txEntry.isContentChanged());
        break;

      case RecordOperation.DELETED:
        iNetwork.writeInt(txEntry.getVersion());
        break;
    }
  }

  static void writeTransactionEntry(
      final ChannelDataOutput iNetwork,
      final NetworkRecordOperation txEntry)
      throws IOException {
    iNetwork.writeByte(txEntry.getType());
    iNetwork.writeRID(txEntry.getId());
    iNetwork.writeLong(txEntry.getDirtyCounter());
    iNetwork.writeByte(txEntry.getRecordType());

    switch (txEntry.getType()) {
      case RecordOperation.CREATED:
        iNetwork.writeBytes(txEntry.getRecord());
        break;

      case RecordOperation.UPDATED:
        iNetwork.writeVersion(txEntry.getVersion());
        iNetwork.writeBytes(txEntry.getRecord());
        iNetwork.writeBoolean(txEntry.isContentChanged());
        break;

      case RecordOperation.DELETED:
        iNetwork.writeVersion(txEntry.getVersion());
        break;
    }
  }

  public static NetworkRecordOperation readTransactionEntry(final DataInput iNetwork)
      throws IOException {
    var result = new NetworkRecordOperation();
    result.setType(iNetwork.readByte());
    var collectionId = iNetwork.readInt();
    var collectionPosition = iNetwork.readLong();
    result.setId(new RecordId(collectionId, collectionPosition));
    result.setDirtyCounter(iNetwork.readLong());
    result.setRecordType(iNetwork.readByte());

    switch (result.getType()) {
      case RecordOperation.CREATED:
        var length = iNetwork.readInt();
        var record = new byte[length];
        iNetwork.readFully(record);
        result.setRecord(record);
        break;

      case RecordOperation.UPDATED:
        result.setVersion(iNetwork.readInt());
        var length2 = iNetwork.readInt();
        var record2 = new byte[length2];
        iNetwork.readFully(record2);
        result.setRecord(record2);
        result.setContentChanged(iNetwork.readBoolean());
        break;

      case RecordOperation.DELETED:
        result.setVersion(iNetwork.readInt());
        break;
    }
    return result;
  }

  static NetworkRecordOperation readTransactionEntry(
      ChannelDataInput channel) throws IOException {
    var entry = new NetworkRecordOperation();
    entry.setType(channel.readByte());
    entry.setId(channel.readRID());
    entry.setDirtyCounter(channel.readLong());
    entry.setRecordType(channel.readByte());
    switch (entry.getType()) {
      case RecordOperation.CREATED:
        entry.setRecord(channel.readBytes());
        break;
      case RecordOperation.UPDATED:
        entry.setVersion(channel.readVersion());
        entry.setRecord(channel.readBytes());
        entry.setContentChanged(channel.readBoolean());
        break;
      case RecordOperation.DELETED:
        entry.setVersion(channel.readVersion());
        break;
      default:
        break;
    }
    return entry;
  }

  @Nullable
  public static Identifiable readIdentifiable(
      DatabaseSessionInternal session, final ChannelDataInput network, RecordSerializer serializer)
      throws IOException {
    final int classId = network.readShort();
    if (classId == ChannelBinaryProtocol.RECORD_NULL) {
      return null;
    }

    if (classId == ChannelBinaryProtocol.RECORD_RID) {
      return network.readRID();
    } else {
      return readRecordFromBytes(session, network, serializer);
    }
  }

  private static DBRecord readRecordFromBytes(
      DatabaseSessionInternal session, ChannelDataInput network, RecordSerializer serializer)
      throws IOException {
    var rec = network.readByte();
    final var rid = network.readRID();
    final var version = network.readVersion();
    final var content = network.readBytes();

    var record =
        YouTrackDBEnginesManager.instance()
            .getRecordFactoryManager()
            .newInstance(rec, rid, session);
    record.setVersion(version);

    var ok = false;
    record.setInternalStatus(STATUS.UNMARSHALLING);
    try {
      serializer.fromStream(session, content, record, null);
      ok = true;
    } finally {
      if (ok) {
        record.setInternalStatus(STATUS.LOADED);
      } else {
        record.setInternalStatus(STATUS.NOT_LOADED);
      }
    }

    record.unsetDirty();
    record.recordSerializer = session.getSerializer();
    return record;
  }

  private static void writeProjection(DatabaseSessionInternal session, Result item,
      ChannelDataOutput channel)
      throws IOException {
    channel.writeByte(QueryResponse.RECORD_TYPE_PROJECTION);
    var ser = new ResultSerializerNetwork();
    ser.toStream(session, item, channel);
  }

  public static void writeResult(
      DatabaseSessionInternal session, Result row, ChannelDataOutput channel)
      throws IOException {
    if (row.isBlob()) {
      channel.writeByte(QueryResponse.RECORD_TYPE_RID);
      writeIdentifiable(session, channel, row.asBlob().getIdentity());
    } else if (row.isVertex()) {
      channel.writeByte(QueryResponse.RECORD_TYPE_RID);
      writeIdentifiable(session, channel, row.asVertex().getIdentity());
    } else if (row.isStatefulEdge()) {
      channel.writeByte(QueryResponse.RECORD_TYPE_RID);
      writeIdentifiable(session, channel, row.asStatefulEdge().getIdentity());
    } else if (row.isEntity()) {
      var entity = row.asEntity();
      if (entity.isEmbedded()) {
        writeProjection(session, entity, channel);
      } else {
        channel.writeByte(QueryResponse.RECORD_TYPE_RID);
        writeIdentifiable(session, channel, row.getIdentity());
      }
    } else {
      writeProjection(session, row, channel);
    }
  }


  public static ResultInternal readResult(DatabaseSessionInternal session, ChannelDataInput channel)
      throws IOException {
    var type = channel.readByte();
    return switch (type) {
      case QueryResponse.RECORD_TYPE_PROJECTION -> readProjection(session, channel);
      case QueryResponse.RECORD_TYPE_RID ->
          new ResultInternal(session, readIdentifiable(session, channel,
              RecordSerializerNetworkV37.INSTANCE));
      default -> throw new IllegalStateException("Unknown record type: " + type);
    };
  }

  private static ResultInternal readProjection(DatabaseSessionInternal session,
      ChannelDataInput channel) throws IOException {
    var ser = new ResultSerializerNetwork();
    return ser.fromStream(session, channel);
  }
}
