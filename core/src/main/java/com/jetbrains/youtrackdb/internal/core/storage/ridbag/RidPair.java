package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nonnull;

public record RidPair(@Nonnull RID primaryRid,
    @Nonnull RID secondaryRid) implements Comparable<RidPair> {

  public static RidPair ofPair(@Nonnull RID primaryRid, @Nonnull RID secondaryRid) {
    return new RidPair(primaryRid, secondaryRid);
  }

  /**
   * Validates that this RidPair represents a valid edge entry (primaryRid != secondaryRid).
   * Call this when reading RidPairs from edge-specific LinkBags to detect legacy lightweight
   * edges. Non-edge LinkBag entries legitimately have primaryRid == secondaryRid.
   *
   * @throws IllegalStateException if primaryRid equals secondaryRid
   */
  public void validateEdgePair() {
    if (primaryRid.equals(secondaryRid)) {
      throw new IllegalStateException(
          "Legacy lightweight edge detected: primaryRid == secondaryRid (" + primaryRid + "). "
              + "Lightweight edges are no longer supported after edge unification (YTDB-605). "
              + "All edges must have a distinct edge record RID (primaryRid) and opposite "
              + "vertex RID (secondaryRid).");
    }
  }

  @Override
  public int compareTo(@Nonnull RidPair other) {
    return primaryRid.compareTo(other.primaryRid);
  }
}
