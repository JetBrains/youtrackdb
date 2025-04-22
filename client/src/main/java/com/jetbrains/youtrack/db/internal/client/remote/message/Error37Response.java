package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryProptocolSession;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.common.exception.ErrorCode;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 *
 */
public class Error37Response implements BinaryResponse {

  private ErrorCode code;
  private int errorIdentifier;
  private Map<String, String> messages;
  private byte[] verbose;

  public Error37Response(
      ErrorCode code, int errorIdentifier, Map<String, String> messages, byte[] verbose) {
    this.code = code;
    this.errorIdentifier = errorIdentifier;
    this.messages = messages;
    this.verbose = verbose;
  }

  public Error37Response() {
  }

  @Override
  public void read(RemoteDatabaseSessionInternal dbSession, ChannelDataInput network,
      BinaryProptocolSession session) throws IOException {
    var code = network.readInt();
    this.errorIdentifier = network.readInt();
    this.code = ErrorCode.getErrorCode(code);
    messages = new HashMap<>();
    while (network.readByte() == 1) {
      var key = network.readString();
      var value = network.readString();
      messages.put(key, value);
    }
    verbose = network.readBytes();
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    channel.writeInt(code.getCode());
    channel.writeInt(errorIdentifier);
    for (var entry : messages.entrySet()) {
      // MORE DETAILS ARE COMING AS EXCEPTION
      channel.writeByte((byte) 1);

      channel.writeString(entry.getKey());
      channel.writeString(entry.getValue());
    }
    channel.writeByte((byte) 0);

    channel.writeBytes(verbose);
  }

  public int getErrorIdentifier() {
    return errorIdentifier;
  }

  public ErrorCode getCode() {
    return code;
  }

  public Map<String, String> getMessages() {
    return messages;
  }

  public byte[] getVerbose() {
    return verbose;
  }
}
