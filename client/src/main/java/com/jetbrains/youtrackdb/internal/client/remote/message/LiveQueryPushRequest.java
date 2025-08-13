package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.api.common.query.BasicResult;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.client.remote.RemotePushHandler;
import com.jetbrains.youtrackdb.internal.client.remote.message.live.LiveQueryResult;
import com.jetbrains.youtrackdb.internal.common.exception.ErrorCode;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.SocketChannelBinary;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

public class LiveQueryPushRequest implements BinaryPushRequest {

  public static final byte HAS_MORE = 1;
  public static final byte END = 2;
  public static final byte ERROR = 3;

  private int monitorId;
  private byte status;
  private int errorIdentifier;
  private ErrorCode errorCode;
  private String errorMessage;
  private List<LiveQueryResult> events;

  public LiveQueryPushRequest(
      int monitorId, int errorIdentifier, ErrorCode errorCode, String errorMessage) {
    this.monitorId = monitorId;
    this.status = ERROR;
    this.errorIdentifier = errorIdentifier;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
  }

  public LiveQueryPushRequest(int monitorId, byte status, List<LiveQueryResult> events) {
    this.monitorId = monitorId;
    this.status = status;
    this.events = events;
  }

  public LiveQueryPushRequest() {
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel)
      throws IOException {
    channel.writeInt(monitorId);
    channel.writeByte(status);
    if (status == ERROR) {
      channel.writeInt(errorIdentifier);
      channel.writeInt(errorCode.getCode());
      channel.writeString(errorMessage);
    } else {
      channel.writeInt(events.size());
      for (var event : events) {
        channel.writeByte(event.eventType());
        MessageHelper.writeResult((Result) event.currentValue(), channel,
            session.getDatabaseTimeZone());
        if (event.eventType() == LiveQueryResult.UPDATE_EVENT) {
          MessageHelper.writeResult((Result) event.oldValue(), channel,
              session.getDatabaseTimeZone());
        }
      }
    }
  }

  @Override
  public void readMonitorIdAndStatus(ChannelDataInput network) throws IOException {
    monitorId = network.readInt();
    status = network.readByte();
  }

  @Override
  public void read(RemoteDatabaseSessionInternal session, ChannelDataInput network)
      throws IOException {
    if (status == ERROR) {
      errorIdentifier = network.readInt();
      errorCode = ErrorCode.getErrorCode(network.readInt());
      errorMessage = network.readString();
    } else {
      var eventSize = network.readInt();
      events = new ArrayList<>(eventSize);
      while (eventSize-- > 0) {
        var type = network.readByte();
        BasicResult currentValue = MessageHelper.readResult(session, network,
            session.getDatabaseTimeZone());
        BasicResult oldValue = null;
        if (type == LiveQueryResult.UPDATE_EVENT) {
          oldValue = MessageHelper.readResult(session, network, session.getDatabaseTimeZone());
        }
        events.add(new LiveQueryResult(type, currentValue, oldValue));
      }
    }
  }

  @Nullable
  @Override
  public BinaryPushResponse execute(RemotePushHandler remote, SocketChannelBinary network) {
    remote.executeLiveQueryPush(this, network);
    return null;
  }

  @Nullable
  @Override
  public BinaryPushResponse createResponse() {
    return null;
  }

  @Override
  public byte getPushCommand() {
    return ChannelBinaryProtocol.REQUEST_PUSH_LIVE_QUERY;
  }

  public int getMonitorId() {
    return monitorId;
  }

  public List<LiveQueryResult> getEvents() {
    return events;
  }

  public byte getStatus() {
    return status;
  }

  public void setStatus(byte status) {
    this.status = status;
  }

  public int getErrorIdentifier() {
    return errorIdentifier;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
