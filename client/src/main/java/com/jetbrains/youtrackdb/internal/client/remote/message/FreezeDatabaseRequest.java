package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

public class FreezeDatabaseRequest implements BinaryRequest<FreezeDatabaseResponse> {

  private String name;
  private String type;

  public FreezeDatabaseRequest(String name, String type) {
    super();
    this.name = name;
    this.type = type;
  }

  public FreezeDatabaseRequest() {
    // TODO Auto-generated constructor stub
  }

  @Override
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      BinaryProtocolSession session) throws IOException {
    network.writeString(name);
    network.writeString(type);
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
    name = channel.readString();
    type = channel.readString();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_DB_FREEZE;
  }

  @Override
  public String requiredServerRole() {
    return "database.freeze";
  }

  @Override
  public boolean requireServerUser() {
    return true;
  }

  @Override
  public String getDescription() {
    return "Freeze Database";
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public FreezeDatabaseResponse createResponse() {
    return new FreezeDatabaseResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeFreezeDatabase(this);
  }
}
