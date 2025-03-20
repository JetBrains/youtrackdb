package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import java.util.List;

/**
 *
 */
public class RebeginTransaction38Request extends BeginTransaction38Request {

  public RebeginTransaction38Request(
      DatabaseSessionInternal session, long txId,
      Iterable<RecordOperation> operations, List<RawPair<RecordId, Long>> receivedDirtyCounters) {
    super(session, txId, operations, receivedDirtyCounters);
  }

  public RebeginTransaction38Request() {
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_TX_REBEGIN;
  }

  @Override
  public String getDescription() {
    return "Re-begin transaction";
  }
}
