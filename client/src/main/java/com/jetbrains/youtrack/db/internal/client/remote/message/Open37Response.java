package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryProptocolSession;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.TimeZone;

/**
 *
 */
public class Open37Response implements BinaryResponse {

  private int sessionId;
  private byte[] sessionToken;
  private TimeZone timeZone;

  public Open37Response() {
  }

  public Open37Response(int sessionId, byte[] sessionToken, TimeZone timeZone) {
    this.sessionId = sessionId;
    this.sessionToken = sessionToken;
    this.timeZone = timeZone;
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    channel.writeInt(sessionId);
    channel.writeBytes(sessionToken);
    channel.writeString(timeZone.getID());
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      BinaryProptocolSession session) throws IOException {
    sessionId = network.readInt();
    sessionToken = network.readBytes();
    timeZone = TimeZone.getTimeZone(network.readString());
  }

  public int getSessionId() {
    return sessionId;
  }

  public byte[] getSessionToken() {
    return sessionToken;
  }

  public TimeZone getTimeZone() {
    return timeZone;
  }
}
