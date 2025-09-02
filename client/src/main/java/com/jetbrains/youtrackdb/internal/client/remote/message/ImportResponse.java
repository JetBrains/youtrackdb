package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ImportResponse implements BinaryResponse {

  private List<String> messages = new ArrayList<>();

  public ImportResponse(List<String> messages) {
    this.messages = messages;
  }

  public ImportResponse() {
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    for (var string : messages) {
      channel.writeString(string);
    }
    channel.writeString(null);
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      BinaryProtocolSession session) throws IOException {
    String message;
    while ((message = network.readString()) != null) {
      messages.add(message);
    }
  }

  public List<String> getMessages() {
    return messages;
  }
}
