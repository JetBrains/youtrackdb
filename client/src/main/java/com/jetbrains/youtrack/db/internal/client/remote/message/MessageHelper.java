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
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37Client;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary.ResultSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BonsaiCollectionPointer;
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
      DatabaseSessionInternal session, ChannelDataOutput channel, final Identifiable o,
      RecordSerializer serializer)
      throws IOException {
    if (o == null) {
      channel.writeShort(ChannelBinaryProtocol.RECORD_NULL);
    } else if (o instanceof RecordId) {
      channel.writeShort(ChannelBinaryProtocol.RECORD_RID);
      channel.writeRID((RID) o);
    } else {
      writeRecord(session, channel, o.getRecord(session), serializer);
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
    if (iRecord.getRecordType() == EntityImpl.RECORD_TYPE
        && (dbSerializerName == null || !dbSerializerName.equals(serializer.toString()))) {
      ((EntityImpl) iRecord).deserializeProperties();
      stream = serializer.toStream(session, iRecord);
    } else {
      stream = iRecord.toStream();
    }

    return stream;
  }

  public static Map<UUID, BonsaiCollectionPointer> readCollectionChanges(ChannelDataInput network)
      throws IOException {
    Map<UUID, BonsaiCollectionPointer> collectionsUpdates = new HashMap<>();
    var count = network.readInt();
    for (var i = 0; i < count; i++) {
      final var mBitsOfId = network.readLong();
      final var lBitsOfId = network.readLong();

      final var pointer =
          CollectionNetworkSerializer.INSTANCE.readCollectionPointer(network);

      collectionsUpdates.put(new UUID(mBitsOfId, lBitsOfId), pointer);
    }
    return collectionsUpdates;
  }

  public static void writeCollectionChanges(
      ChannelDataOutput channel, Map<UUID, BonsaiCollectionPointer> changedIds)
      throws IOException {
    channel.writeInt(changedIds.size());
    for (var entry : changedIds.entrySet()) {
      channel.writeLong(entry.getKey().getMostSignificantBits());
      channel.writeLong(entry.getKey().getLeastSignificantBits());
      CollectionNetworkSerializer.INSTANCE.writeCollectionPointer(channel, entry.getValue());
    }
  }

  public static void writePhysicalPositions(
      ChannelDataOutput channel, PhysicalPosition[] previousPositions) throws IOException {
    if (previousPositions == null) {
      channel.writeInt(0); // NO ENTRIEs
    } else {
      channel.writeInt(previousPositions.length);

      for (final var physicalPosition : previousPositions) {
        channel.writeLong(physicalPosition.clusterPosition);
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

        position.clusterPosition = network.readLong();
        position.recordSize = network.readInt();
        position.recordVersion = network.readVersion();

        physicalPositions[i] = position;
      }
    }
    return physicalPositions;
  }

  public static RawPair<String[], int[]> readClustersArray(final ChannelDataInput network)
      throws IOException {
    final int tot = network.readShort();
    final var clusterNames = new String[tot];
    final var clusterIds = new int[tot];

    for (var i = 0; i < tot; ++i) {
      var clusterName = network.readString().toLowerCase(Locale.ENGLISH);
      final int clusterId = network.readShort();
      clusterNames[i] = clusterName;
      clusterIds[i] = clusterId;
    }

    return new RawPair<>(clusterNames, clusterIds);
  }

  public static void writeClustersArray(
      ChannelDataOutput channel, RawPair<String[], int[]> clusters, int protocolVersion)
      throws IOException {
    final var clusterNames = clusters.first;
    final var clusterIds = clusters.second;

    channel.writeShort((short) clusterNames.length);

    for (var i = 0; i < clusterNames.length; i++) {
      channel.writeString(clusterNames[i]);
      channel.writeShort((short) clusterIds[i]);
    }
  }

  public static void writeTransactionEntry(
      final DataOutput iNetwork, final NetworkRecordOperation txEntry) throws IOException {
    iNetwork.writeByte(txEntry.getType());
    iNetwork.writeInt(txEntry.getId().getClusterId());
    iNetwork.writeLong(txEntry.getId().getClusterPosition());
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
    var clusterId = iNetwork.readInt();
    var clusterPosition = iNetwork.readLong();
    result.setId(new RecordId(clusterId, clusterPosition));
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
    final var rec2 = record;
    rec2.setVersion(version);
    serializer.fromStream(session, content, record, null);
    final var rec1 = record;
    rec1.unsetDirty();

    return record;
  }

  private static void writeProjection(DatabaseSessionInternal session, Result item,
      ChannelDataOutput channel)
      throws IOException {
    channel.writeByte(QueryResponse.RECORD_TYPE_PROJECTION);
    var ser = new ResultSerializerNetwork();
    ser.toStream(session, item, channel);
  }

  private static void writeBlob(
      DatabaseSessionInternal session, Result row, ChannelDataOutput channel,
      RecordSerializer recordSerializer)
      throws IOException {
    channel.writeByte(QueryResponse.RECORD_TYPE_BLOB);
    writeIdentifiable(session, channel, row.asBlob(), recordSerializer);
  }

  private static void writeVertex(
      DatabaseSessionInternal session, Result row, ChannelDataOutput channel,
      RecordSerializer recordSerializer)
      throws IOException {
    channel.writeByte(QueryResponse.RECORD_TYPE_VERTEX);
    writeDocument(session, channel, row.asEntity().getRecord(session), recordSerializer);
  }

  private static void writeEntity(
      DatabaseSessionInternal session, Result row, ChannelDataOutput channel,
      RecordSerializer recordSerializer)
      throws IOException {
    channel.writeByte(QueryResponse.RECORD_TYPE_ELEMENT);
    writeDocument(session, channel, row.asEntity().getRecord(session), recordSerializer);
  }

  private static void writeEdge(
      DatabaseSessionInternal session, Result row, ChannelDataOutput channel,
      RecordSerializer recordSerializer)
      throws IOException {
    channel.writeByte(QueryResponse.RECORD_TYPE_EDGE);
    writeDocument(session, channel, row.asEntity().getRecord(session), recordSerializer);
  }

  private static void writeDocument(
      DatabaseSessionInternal session, ChannelDataOutput channel, EntityImpl entity,
      RecordSerializer serializer) throws IOException {
    writeIdentifiable(session, channel, entity, serializer);
  }

  public static void writeResult(
      DatabaseSessionInternal session, Result row, ChannelDataOutput channel,
      RecordSerializer recordSerializer)
      throws IOException {
    if (row.isBlob()) {
      writeBlob(session, row, channel, recordSerializer);
    } else if (row.isVertex()) {
      writeVertex(session, row, channel, recordSerializer);
    } else if (row.isStatefulEdge()) {
      writeEdge(session, row, channel, recordSerializer);
    } else if (row.isEntity()) {
      writeEntity(session, row, channel, recordSerializer);
    } else {
      writeProjection(session, row, channel);
    }
  }

  private static ResultInternal readBlob(DatabaseSessionInternal session, ChannelDataInput channel)
      throws IOException {
    RecordSerializer serializer = RecordSerializerNetworkV37.INSTANCE;
    return new ResultInternal(session, readIdentifiable(session, channel, serializer));
  }

  public static ResultInternal readResult(DatabaseSessionInternal session, ChannelDataInput channel)
      throws IOException {
    var type = channel.readByte();
    return switch (type) {
      case QueryResponse.RECORD_TYPE_BLOB -> readBlob(session, channel);
      case QueryResponse.RECORD_TYPE_VERTEX -> readVertex(session, channel);
      case QueryResponse.RECORD_TYPE_EDGE -> readEdge(session, channel);
      case QueryResponse.RECORD_TYPE_ELEMENT -> readEntityAsResult(session, channel);
      case QueryResponse.RECORD_TYPE_PROJECTION -> readProjection(session, channel);
      default -> new ResultInternal(session);
    };
  }

  private static ResultInternal readEntityAsResult(DatabaseSessionInternal session,
      ChannelDataInput channel)
      throws IOException {
    return new ResultInternal(session, readEntity(session, channel));
  }

  private static ResultInternal readVertex(DatabaseSessionInternal session,
      ChannelDataInput channel)
      throws IOException {
    return new ResultInternal(session, readEntity(session, channel));
  }

  private static ResultInternal readEdge(DatabaseSessionInternal session, ChannelDataInput channel)
      throws IOException {
    return new ResultInternal(session, readEntity(session, channel));
  }

  private static DBRecord readEntity(DatabaseSessionInternal session, ChannelDataInput channel)
      throws IOException {
    RecordSerializer serializer = RecordSerializerNetworkV37Client.INSTANCE;
    return (DBRecord) readIdentifiable(session, channel, serializer);
  }

  private static ResultInternal readProjection(DatabaseSessionInternal session,
      ChannelDataInput channel) throws IOException {
    var ser = new ResultSerializerNetwork();
    return ser.fromStream(session, channel);
  }
}
