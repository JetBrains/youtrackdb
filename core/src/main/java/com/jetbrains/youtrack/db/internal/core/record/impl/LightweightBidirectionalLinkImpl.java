package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LightweightBidirectionalLinkImpl<T extends Entity> implements BidirectionalLink<T> {

  @Nullable
  protected final T out;
  @Nullable
  protected final T in;

  @Nonnull
  protected final DatabaseSessionInternal session;

  protected final String label;

  public LightweightBidirectionalLinkImpl(@Nonnull DatabaseSessionInternal session,
      @Nullable T out, @Nullable T in,
      String label) {
    this.out = out;
    this.in = in;
    this.session = session;
    this.label = label;
  }


  @Nullable
  @Override
  public T fromEntity() {
    return out;
  }

  @Nullable
  @Override
  public T toEntity() {
    return in;
  }

  @Override
  public boolean isLabeled(@Nonnull String[] labels) {
    if (labels.length == 0) {
      return true;
    }

    for (var label : labels) {
      return label.equalsIgnoreCase(this.label);
    }

    return false;
  }

  @Nullable
  public T getEntity(@Nonnull Direction dir) {
    if (dir == Direction.IN) {
      return in;
    } else if (dir == Direction.OUT) {
      return out;
    }

    throw new IllegalArgumentException("Direction not supported: " + dir);
  }

  @Override
  public boolean isLightweight() {
    return true;
  }

  @Override
  public Entity asEntity() {
    throw new IllegalStateException("LightweightBidirectionalLinkImpl is not a entity");
  }

  @Override
  public Map<String, Object> toMap() {
    return Map.of("out", out, "in", in, "label", label);
  }

  @Override
  public String toJSON() {
    return "{\"out\":\""
        + out.getIdentity()
        + "\", \"in\":\""
        + in.getIdentity()
        + "\", \"label\":\""
        + label
        + "\"}";
  }

  @Override
  public String label() {
    return label;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof BidirectionalLink<?> bidirectionalLink)) {
      return false;
    }

    if (!bidirectionalLink.isLightweight()) {
      return false;
    }

    return out.equals(bidirectionalLink.fromEntity()) && in.equals(bidirectionalLink.toEntity())
        && label.equals(bidirectionalLink.label());

  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  public String toString() {
    return super.toString();
  }
}
