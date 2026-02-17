package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nonnull;

public record RidPair(@Nonnull RID primaryRid,
                      @Nonnull RID secondaryRid) implements Comparable<RidPair> {

  public static RidPair ofSingle(@Nonnull RID rid) {
    return new RidPair(rid, rid);
  }

  public static RidPair ofPair(@Nonnull RID primaryRid, @Nonnull RID secondaryRid) {
    return new RidPair(primaryRid, secondaryRid);
  }

  public boolean isLightweight() {
    return primaryRid.equals(secondaryRid);
  }

  @Override
  public int compareTo(@Nonnull RidPair other) {
    return primaryRid.compareTo(other.primaryRid);
  }
}
