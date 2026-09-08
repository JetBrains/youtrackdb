package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

/** Immutable durable value for one index descriptor lifecycle. */
public record IndexBuildState(
    int formatVersion,
    RID descriptorIdentity,
    IndexLifecycle lifecycle,
    UUID buildIncarnation,
    @Nullable Long ownerEpoch,
    @Nullable Long completionCut,
    long completedUnits,
    boolean suspended,
    IndexBuildFailure failure,
    @Nullable String failureMessage) {

  public static final int CURRENT_FORMAT_VERSION = 1;

  public IndexBuildState {
    if (formatVersion != CURRENT_FORMAT_VERSION) {
      throw new IllegalArgumentException("Unsupported index build state format " + formatVersion);
    }
    Objects.requireNonNull(descriptorIdentity, "descriptorIdentity");
    Objects.requireNonNull(lifecycle, "lifecycle");
    Objects.requireNonNull(buildIncarnation, "buildIncarnation");
    Objects.requireNonNull(failure, "failure");
    if (!descriptorIdentity.isPersistent()) {
      throw new IllegalArgumentException("The index descriptor identity must be persistent");
    }
    if (ownerEpoch != null && ownerEpoch < 0) {
      throw new IllegalArgumentException("The owner epoch cannot be negative");
    }
    if (completionCut != null && completionCut < 0) {
      throw new IllegalArgumentException("The completion cut cannot be negative");
    }
    if (completedUnits < 0) {
      throw new IllegalArgumentException("Completed units cannot be negative");
    }
    if (lifecycle == IndexLifecycle.INVALID && failure == IndexBuildFailure.NONE) {
      throw new IllegalArgumentException("An invalid index requires a failure code");
    }
    if (lifecycle != IndexLifecycle.INVALID
        && failure == IndexBuildFailure.INDEX_BUILD_STATE_MISSING) {
      throw new IllegalArgumentException("Missing build state requires an invalid lifecycle");
    }
  }

  public static IndexBuildState initial(RID descriptorIdentity) {
    return new IndexBuildState(
        CURRENT_FORMAT_VERSION,
        descriptorIdentity,
        IndexLifecycle.EXISTS,
        UUID.randomUUID(),
        null,
        null,
        0,
        false,
        IndexBuildFailure.NONE,
        null);
  }

  public static IndexBuildState quarantineMissing(RID descriptorIdentity) {
    return new IndexBuildState(
        CURRENT_FORMAT_VERSION,
        descriptorIdentity,
        IndexLifecycle.INVALID,
        UUID.randomUUID(),
        null,
        null,
        0,
        false,
        IndexBuildFailure.INDEX_BUILD_STATE_MISSING,
        "The durable index build state record was missing");
  }

  public static boolean isLegalTransition(IndexLifecycle current, IndexLifecycle replacement) {
    return (current == IndexLifecycle.EXISTS && replacement == IndexLifecycle.MAINTAINED)
        || (current == IndexLifecycle.MAINTAINED && replacement == IndexLifecycle.USABLE)
        || (current == IndexLifecycle.MAINTAINED && replacement == IndexLifecycle.INVALID)
        || (current == IndexLifecycle.INVALID && replacement == IndexLifecycle.MAINTAINED);
  }
}
