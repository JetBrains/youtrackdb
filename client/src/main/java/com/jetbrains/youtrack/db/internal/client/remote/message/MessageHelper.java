package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary.RemoteResultImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary.ResultSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.TimeZone;

public class MessageHelper {

  private static void writeProjection(BasicResult item,
      ChannelDataOutput channel, TimeZone databaseTimeZone)
      throws IOException {
    channel.writeByte(QueryResponse.RECORD_TYPE_PROJECTION);
    ResultSerializerNetwork.toStream(item, channel, databaseTimeZone);
  }

  public static void writeResult(
      DatabaseSessionEmbedded session, Result row, ChannelDataOutput channel)
      throws IOException {
    if (row.isBlob()) {
      writeBlob(row, channel);
    } else if (row.isEntity()) {
      writeEntity(session, row.asEntity(), channel);
    } else if (row.isProjection()) {
      writeProjection(row, channel, session.getDatabaseTimeZone());
    } else {
      throw new IllegalArgumentException("Unsupported result type: " + row);
    }
  }

  public static void writeResult(
      RemoteDatabaseSessionInternal session, RemoteResult row, ChannelDataOutput channel)
      throws IOException {
    if (row.isProjection()) {
      writeProjection(row, channel, session.getDatabaseTimeZone());
    } else {
      throw new IllegalArgumentException("Unsupported result type: " + row);
    }
  }

  private static void writeEntity(DatabaseSessionEmbedded session, Entity entity,
      ChannelDataOutput channel) throws IOException {
    if (entity.isUnloaded()) {
      channel.writeByte(QueryResponse.RECORD_TYPE_RID);
      channel.writeRID(entity.getIdentity());
    } else {
      writeProjection(entity, channel, session.getDatabaseTimeZone());
    }
  }

  private static void writeBlob(Result row, ChannelDataOutput channel) throws IOException {
    channel.writeByte(QueryResponse.RECORD_TYPE_BLOB);
    channel.writeBytes(row.asBlob().toStream());
  }

  public static RemoteResult readResult(RemoteDatabaseSessionInternal session,
      ChannelDataInput channel)
      throws IOException {
    var type = channel.readByte();

    if (type == QueryResponse.RECORD_TYPE_RID) {
      return new RemoteResultImpl(session, channel.readRID());
    } else if (type == QueryResponse.RECORD_TYPE_BLOB) {
      return new RemoteResultImpl(session, channel.readBytes());
    } else if (type == QueryResponse.RECORD_TYPE_PROJECTION) {
      return readProjection(session, channel);
    }

    throw new IllegalStateException("Unknown record type: " + type);
  }

  public static ResultInternal readResult(DatabaseSessionEmbedded session,
      ChannelDataInput channel)
      throws IOException {
    var type = channel.readByte();

    if (type == QueryResponse.RECORD_TYPE_RID) {
      return new ResultInternal(session, channel.readRID());
    } else if (type == QueryResponse.RECORD_TYPE_PROJECTION) {
      return readProjection(session, channel);
    }

    throw new IllegalStateException("Unknown record type: " + type);
  }

  private static RemoteResult readProjection(RemoteDatabaseSessionInternal session,
      ChannelDataInput channel) throws IOException {
    return ResultSerializerNetwork.fromStream(session, channel);
  }

  private static ResultInternal readProjection(DatabaseSessionEmbedded session,
      ChannelDataInput channel) throws IOException {
    return ResultSerializerNetwork.fromStream(session, channel);
  }
}
