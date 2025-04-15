package com.jetbrains.youtrack.db.internal.core.tx;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public class FrontendTransactionId {

  private final Optional<String> nodeOwner;
  private final int position;
  private final long sequence;

  public FrontendTransactionId(Optional<String> nodeOwner, int position, long sequence) {
    assert nodeOwner != null;
    this.nodeOwner = nodeOwner;
    this.position = position;
    this.sequence = sequence;
  }

  public int getPosition() {
    return position;
  }

  public long getSequence() {
    return sequence;
  }

  public Optional<String> getNodeOwner() {
    return nodeOwner;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    var that = (FrontendTransactionId) o;
    return position == that.position
        && sequence == that.sequence
        && Objects.equals(nodeOwner, that.nodeOwner);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nodeOwner, position, sequence);
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
