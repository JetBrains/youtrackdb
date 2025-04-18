package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement.STATUS;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary.ResultSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.Locale;
import javax.annotation.Nullable;

public class MessageHelper {

  public static void writeIdentifiable(
      DatabaseSessionEmbedded session, ChannelDataOutput channel, final Identifiable o)
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

  @Nullable
  public static Identifiable readIdentifiable(
      DatabaseSessionEmbedded session, final ChannelDataInput network, RecordSerializer serializer)
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
      DatabaseSessionEmbedded session, ChannelDataInput network, RecordSerializer serializer)
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

  public static void writeProjection(DatabaseSessionEmbedded session, BasicResult item,
      ChannelDataOutput channel)
      throws IOException {
    channel.writeByte(QueryResponse.RECORD_TYPE_PROJECTION);
    var ser = new ResultSerializerNetwork();
    ser.toStream(session, item, channel);
  }

  public static void writeResult(
      DatabaseSessionEmbedded session, Result row, ChannelDataOutput channel)
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

  public static RemoteResult readResult(DatabaseSessionRemote session, ChannelDataInput channel)
      throws IOException {
    var type = channel.readByte();
    if (type == QueryResponse.RECORD_TYPE_PROJECTION) {
      return readProjection(session, channel);
    }

    throw new IllegalStateException("Unknown record type: " + type);
  }

  public static Result readResult(DatabaseSessionEmbedded session, ChannelDataInput channel)
      throws IOException {
    var type = channel.readByte();
    if (type == QueryResponse.RECORD_TYPE_PROJECTION) {
      return readProjection(session, channel);
    }

    throw new IllegalStateException("Unknown record type: " + type);
  }

  private static RemoteResult readProjection(DatabaseSessionRemote session,
      ChannelDataInput channel) throws IOException {
    var ser = new ResultSerializerNetwork();
    return ser.fromStream(session, channel);
  }

  public static Result readProjection(DatabaseSessionEmbedded session,
      ChannelDataInput channel) throws IOException {
    var ser = new ResultSerializerNetwork();
    return ser.fromStream(session, channel);
  }
}
