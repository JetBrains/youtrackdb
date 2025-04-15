package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.common.exception.ErrorCode;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
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
  public void read(DatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
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
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
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
