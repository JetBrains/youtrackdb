package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

/**
 * Reports one disagreement between two durable metadata sources of one storage image.
 *
 * <p>A storage is one physical database image on disk. Storage admission is the decision that
 * accepts an existing storage image for use. Admission runs before write-ahead log setup and
 * before recovery. A consistency check is a later check that compares two durable sources without
 * deciding admission.
 *
 * <p>Track 24 turned the two later checks into consistency checks. The first later check runs
 * during the load of the storage configuration and compares the storage layout version. The second
 * later check runs during the load of the shared context and compares the genesis marker. Genesis
 * is the creation of the initial database metadata. The genesis marker is the durable value that
 * records a finished genesis.
 *
 * <p>Neither consistency check reverses the admission decision, because admission already happened
 * before recovery. Each consistency check keeps the storage closed for the caller. Each message
 * names the two disagreeing sources.
 *
 * <p>The value of {@link #inconsistency()} names the check that reported the disagreement. The drop
 * path tolerates the genesis marker value, so an operator can always discard an image whose genesis
 * never finished. The drop path stays loud for the storage layout version value, because an
 * operator must see a version mismatch.
 */
public class InconsistentStorageMetadataException extends DatabaseException
    implements HighLevelException {

  /** Names the consistency check that found the disagreement. */
  public enum Inconsistency {
    /** The storage configuration and the bootstrap authority record name different layouts. */
    STORAGE_LAYOUT_VERSION,
    /** The accepted lifecycle state says active, and the genesis marker says unfinished. */
    GENESIS_MARKER
  }

  private final Inconsistency inconsistency;

  @SuppressWarnings("unused")
  public InconsistentStorageMetadataException(InconsistentStorageMetadataException exception) {
    super(exception);
    this.inconsistency = exception.inconsistency;
  }

  public InconsistentStorageMetadataException(String dbName, Inconsistency inconsistency,
      String message) {
    super(dbName, message);
    this.inconsistency = inconsistency;
  }

  /** The consistency check that reported this result. */
  public Inconsistency inconsistency() {
    return inconsistency;
  }
}
