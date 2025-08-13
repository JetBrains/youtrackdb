package com.jetbrains.youtrackdb.internal.core.tx;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Optional;

public record FrontendTransactionId(Optional<String> nodeOwner, int position, long sequence) {

  public FrontendTransactionId {
    assert nodeOwner != null;
  }

  public static FrontendTransactionId read(DataInput input) throws IOException {
    Optional<String> nodeOwner;
    if (input.readBoolean()) {
      nodeOwner = Optional.of(input.readUTF());
    } else {
      nodeOwner = Optional.empty();
    }
    var position = input.readInt();
    var sequence = input.readLong();
    return new FrontendTransactionId(nodeOwner, position, sequence);
  }

  public void write(DataOutput out) throws IOException {
    if (nodeOwner.isPresent()) {
      out.writeBoolean(true);
      out.writeUTF(nodeOwner.get());
    } else {
      out.writeBoolean(false);
    }
    out.writeInt(position);
    out.writeLong(sequence);
  }

  @Override
  public String toString() {
    return position + ":" + sequence + " owner:" + nodeOwner;
  }
}
