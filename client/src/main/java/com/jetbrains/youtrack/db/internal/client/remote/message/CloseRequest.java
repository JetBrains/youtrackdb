package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import javax.annotation.Nullable;

public class CloseRequest implements BinaryRequest<BinaryResponse> {

  @Override
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      BinaryProtocolSession session) throws IOException {
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_DB_CLOSE;
  }

  @Override
  public String getDescription() {
    return "Close Database";
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Nullable
  @Override
  public BinaryResponse createResponse() {
    return null;
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor ex) {
    return ex.executeClose(this);
  }
}
