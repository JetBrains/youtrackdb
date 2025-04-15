package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class OpenResponse implements BinaryResponse {

  private int sessionId;
  private byte[] sessionToken;
  private int[] collectionIds;
  private String[] collectionNames;

  private byte[] distributedConfiguration;
  private String serverVersion;

  public OpenResponse() {
  }

  public OpenResponse(
      int sessionId,
      byte[] sessionToken,
      int[] collectionIds,
      String[] collectionNames,
      byte[] distriConf,
      String version) {
    this.sessionId = sessionId;
    this.sessionToken = sessionToken;
    this.collectionIds = collectionIds;
    this.collectionNames = collectionNames;
    this.distributedConfiguration = distriConf;
    this.serverVersion = version;
  }

  @Override
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {
    channel.writeInt(sessionId);
    if (protocolVersion > ChannelBinaryProtocol.PROTOCOL_VERSION_26) {
      channel.writeBytes(sessionToken);
    }

    MessageHelper.writeCollectionsArray(
        channel, new RawPair<>(collectionNames, collectionIds), protocolVersion);
    channel.writeBytes(distributedConfiguration);
    channel.writeString(serverVersion);
  }

  @Override
  public void read(DatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    sessionId = network.readInt();
    sessionToken = network.readBytes();
    final var collections = MessageHelper.readCollectionsArray(network);
    distributedConfiguration = network.readBytes();
    serverVersion = network.readString();
  }

  public int getSessionId() {
    return sessionId;
  }

  public byte[] getSessionToken() {
    return sessionToken;
  }

  public int[] getCollectionIds() {
    return collectionIds;
  }

  public String[] getCollectionNames() {
    return collectionNames;
  }

  public byte[] getDistributedConfiguration() {
    return distributedConfiguration;
  }
}
