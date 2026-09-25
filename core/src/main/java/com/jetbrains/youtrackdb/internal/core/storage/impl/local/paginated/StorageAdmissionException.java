package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Names one cause that rejects a storage image during admission.
 *
 * <p>Admission is the decision that accepts an existing storage image for use. Admission runs
 * before write-ahead log initialization and before recovery. A write-ahead log records a change
 * before that change reaches its final file.
 *
 * <p>This exception extends {@link IOException} on purpose. Every present caller of the bootstrap
 * authority already catches {@link IOException} and wraps that failure into a storage exception.
 * The narrower type therefore adds a named cause without breaking one present catch site.
 */
public class StorageAdmissionException extends IOException {

  private final Reason reason;

  public StorageAdmissionException(
      final Reason reason, final Path storageDirectory, final String message) {
    this(reason, storageDirectory, message, null);
  }

  public StorageAdmissionException(
      final Reason reason,
      final Path storageDirectory,
      final String message,
      final Throwable cause) {
    super(composeMessage(reason, storageDirectory, message), cause);
    this.reason = reason;
  }

  /** Returns the single named cause of this rejection. */
  public Reason reason() {
    return reason;
  }

  /**
   * Builds the diagnostic message of one rejection.
   *
   * <p>This message carries the reason identifier and the storage path, because a developer and a
   * log reader need both values. The operator-facing message of the caller states the cause in
   * plain words through {@link Reason#description()}, so no caller repeats the identifier.
   */
  private static String composeMessage(
      final Reason reason, final Path storageDirectory, final String message) {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(message, "message");
    return message
        + " (admission reason: "
        + reason.name()
        + ", storage path: "
        + storageDirectory
        + ")";
  }

  /** Enumerates every cause that the authority decoder or the authority selection can report. */
  public enum Reason {

    /** The directory holds no bootstrap authority artifact at all. */
    AUTHORITY_MISSING,

    /**
     * One authority copy failed its integrity check.
     *
     * <p>An integrity check covers the record size, a truncated read, a grown read, and the
     * checksum. Selection tolerates a single damaged copy. A directory whose every copy fails the
     * integrity check reports {@link #INTERRUPTED_BIRTH} under the residue precedence rule, and
     * that verdict carries each damaged-copy failure as a suppressed exception.
     */
    AUTHORITY_DAMAGED,

    /**
     * One checksum-valid authority copy holds an impossible value.
     *
     * <p>An impossible value is a wrong record magic value or a non-positive generation. A
     * generation is a counter inside the record, where a higher value means a newer record.
     */
    AUTHORITY_INVALID_CONTENT,

    /** One authority path or the authority lock path is not a regular file. */
    NON_REGULAR_AUTHORITY_FILE,

    /** Two readable authority copies name different storage identities. */
    FOREIGN_AUTHORITY_COPY,

    /** Two readable authority copies hold conflicting content at the same generation. */
    AUTHORITY_AMBIGUOUS,

    /** Two consecutive generations describe an illegal lifecycle transition. */
    AUTHORITY_ILLEGAL_TRANSITION,

    /** The authority encoding version of the record is not supported by this build. */
    UNSUPPORTED_ENCODING_VERSION,

    /** The authority feature format version of the record is not supported by this build. */
    UNSUPPORTED_FEATURE_FORMAT_VERSION,

    /** The lifecycle state code of the record is not supported by this build. */
    UNSUPPORTED_LIFECYCLE_STATE,

    /**
     * The storage layout version of the record is not supported by this build.
     *
     * <p>The storage layout version is the version of the on-disk storage layout. An image that an
     * earlier commit of this branch created holds the empty value zero in that field, so that image
     * also reports this reason.
     */
    UNSUPPORTED_STORAGE_LAYOUT_VERSION,

    /**
     * The image carries an interrupted storage birth.
     *
     * <p>This reason covers a readable record in the birth-in-progress lifecycle state. This reason
     * also covers a directory that holds a bootstrap authority artifact without any readable
     * record.
     */
    INTERRUPTED_BIRTH,

    /** The image carries a readable record in the restore-in-progress lifecycle state. */
    INTERRUPTED_RESTORE,

    /**
     * The destructive restore restart refuses the target because of the lifecycle state.
     *
     * <p>A destructive restart is the full deletion of an interrupted restore target and a fresh
     * restore. The restart accepts a readable record in the restore-in-progress lifecycle state
     * only. The restart reports this reason for a readable record in the active lifecycle state,
     * and the restart then changes no file.
     */
    RESTART_TARGET_NOT_RESTORE_IN_PROGRESS,

    /** The destructive restore restart found the normal database lock busy. */
    RESTART_TARGET_BUSY,

    /** The logical sequence floor of the record is negative. */
    INVALID_SEQUENCE_FLOOR,

    /** A low-level input or output failure prevented the admission decision. */
    AUTHORITY_IO_FAILURE;

    /**
     * Returns one plain sentence that an operator can read.
     *
     * <p>A bootstrap authority record is the durable record that names one storage identity, one
     * storage lineage, and one lifecycle state. Every operator-facing message of a rejected
     * admission uses the sentence of this method instead of the reason identifier.
     */
    public String description() {
      return switch (this) {
        case AUTHORITY_MISSING ->
            "The directory holds no bootstrap authority record of a database.";
        case AUTHORITY_DAMAGED ->
            "One bootstrap authority record failed its integrity check.";
        case AUTHORITY_INVALID_CONTENT ->
            "One bootstrap authority record holds an impossible value.";
        case NON_REGULAR_AUTHORITY_FILE ->
            "One bootstrap authority file is no regular file.";
        case FOREIGN_AUTHORITY_COPY ->
            "Two bootstrap authority records name two different databases.";
        case AUTHORITY_AMBIGUOUS ->
            "Two bootstrap authority records of the same age hold different content.";
        case AUTHORITY_ILLEGAL_TRANSITION ->
            "Two bootstrap authority records describe an impossible lifecycle change.";
        case UNSUPPORTED_ENCODING_VERSION ->
            "The bootstrap authority record uses a record layout that this build cannot read.";
        case UNSUPPORTED_FEATURE_FORMAT_VERSION ->
            "The bootstrap authority record names a feature set that this build cannot serve.";
        case UNSUPPORTED_LIFECYCLE_STATE ->
            "The bootstrap authority record names a lifecycle state that this build cannot use.";
        case UNSUPPORTED_STORAGE_LAYOUT_VERSION ->
            "The image uses an on-disk storage layout that this build cannot read.";
        case INTERRUPTED_BIRTH ->
            "The database carries no complete bootstrap authority record. An interrupted creation"
                + " produces this state. Damaged bootstrap authority records of a complete database"
                + " produce the same state. Inspect the database directory before any deletion. A"
                + " backup restores a complete database. A drop removes a database whose creation"
                + " never finished.";
        case INTERRUPTED_RESTORE ->
            "The restore of the database never finished. Restart the restore, or drop the"
                + " database.";
        case RESTART_TARGET_NOT_RESTORE_IN_PROGRESS ->
            "The database carries no interrupted restore, so the destructive restart refuses the"
                + " database.";
        case RESTART_TARGET_BUSY ->
            "The interrupted restore target is busy, so the destructive restart refuses the"
                + " database.";
        case INVALID_SEQUENCE_FLOOR ->
            "The bootstrap authority record holds a negative operation counter.";
        case AUTHORITY_IO_FAILURE ->
            "A read failure of the bootstrap authority files stopped the admission decision.";
      };
    }
  }
}
