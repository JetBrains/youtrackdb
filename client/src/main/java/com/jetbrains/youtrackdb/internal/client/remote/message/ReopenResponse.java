package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.TimeZone;

public class ReopenResponse implements BinaryResponse {

  private int sessionId;
  private TimeZone timeZone;

  public ReopenResponse() {
  }

  public ReopenResponse(int sessionId, TimeZone timeZone) {
    this.sessionId = sessionId;
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    channel.writeInt(sessionId);
    channel.writeString(timeZone.getID());
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      BinaryProtocolSession session) throws IOException {
    sessionId = network.readInt();
    timeZone = TimeZone.getTimeZone(network.readString());
  }

  public int getSessionId() {
    return sessionId;
  }

  public TimeZone getTimeZone() {
    return timeZone;
  }
}
