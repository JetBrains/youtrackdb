package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LightweightRelationImpl<T extends Entity> implements
    Relation<T> {
  @Nullable
  protected final T out;
  @Nullable
  protected final T in;

  @Nonnull
  protected final DatabaseSessionEmbedded session;

  protected final String label;

  public LightweightRelationImpl(@Nonnull DatabaseSessionEmbedded session,
      @Nullable T out, @Nullable T in,
      String label) {
    this.out = out;
    this.in = in;
    this.session = session;
    this.label = label;
  }


  @Nullable
  @Override
  public T getFrom() {
    return out;
  }

  @Nullable
  @Override
  public T getTo() {
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

  @Override
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
  public @Nonnull Map<String, Object> toMap() {
    return Map.of("out", out, "in", in, "label", label);
  }

  @Override
  public @Nonnull String toJSON() {
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
    if (!(obj instanceof Relation<?> bidirectionalRelation)) {
      return false;
    }

    if (!bidirectionalRelation.isLightweight()) {
      return false;
    }

    return out.equals(bidirectionalRelation.getFrom()) && in.equals(
        bidirectionalRelation.getTo())
        && label.equals(bidirectionalRelation.label());

  }

  @Override
  public int hashCode() {
    //noinspection ObjectInstantiationInEqualsHashCode
    return Objects.hash(out, in, label);
  }

  @Override
  public String toString() {
    return "LightweightRelationImpl {" +
        " out=" + out +
        ", in=" + in +
        ", label='" + label + '\'' +
        '}';
  }
}
