package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

/**
 * The on-disk storage layout understood by this build.
 *
 * <p>A storage layout version identifies the organization of database files. This type owns
 * layout admission and its operator guidance. It exposes no user-selectable storage option.
 */
public enum StorageFormat {
  LAYOUT_27(27);

  private final int layoutVersion;

  StorageFormat(final int layoutVersion) {
    this.layoutVersion = layoutVersion;
  }

  /** Returns the persisted storage-layout value for this descriptor. */
  public int layoutVersion() {
    return layoutVersion;
  }

  /** Returns the layout for ordinary new databases. */
  public static StorageFormat productionDefault() {
    return LAYOUT_27;
  }

  /**
   * Reports conflicting stored values after image admission, without image migration advice.
   * A null stored version identifies a missing configuration value.
   */
  public static String layoutVersionDisagreement(String storedSource, Integer storedVersion,
      String admittedSource, int admittedVersion) {
    return "The " + storedSource + " reports storage layout version "
        + (storedVersion == null ? "<missing>" : storedVersion)
        + ". The " + admittedSource + " reports storage layout version " + admittedVersion
        + ". The stored storage layout versions disagree.";
  }

  /**
   * Resolves an exact stored layout value without conversion or fallback.
   *
   * @throws IllegalArgumentException if the stored layout is not supported
   */
  public static StorageFormat resolve(final int layoutVersion) {
    return switch (layoutVersion) {
      case 27 -> LAYOUT_27;
      case 25, 26 -> throw new IllegalArgumentException(
          "Unsupported storage layout version: " + layoutVersion
              + ". Export the database with the older YouTrackDB version."
              + " Import that export with the new YouTrackDB version.");
      default -> throw new IllegalArgumentException(
          "Unsupported storage layout version: " + layoutVersion
              + (layoutVersion > 27
                  ? ". A newer YouTrackDB version created this database."
                      + " Open it with that version."
                  : ". This build supports storage layout version 27 only."));
    };
  }
}
