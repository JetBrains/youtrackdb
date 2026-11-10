package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

/**
 * Immutable storage-layout identities understood by this build.
 *
 * <p>The production creation default is independent of the supported set. This type describes
 * identity only and exposes no user-selectable storage option.
 */
public enum StorageFormat {
  LAYOUT_27(27), LAYOUT_28(28);

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
   * Resolves an exact stored layout value without conversion or fallback.
   *
   * @throws IllegalArgumentException if the stored layout is not supported
   */
  public static StorageFormat resolve(final int layoutVersion) {
    return switch (layoutVersion) {
      case 27 -> LAYOUT_27;
      case 28 -> LAYOUT_28;
      case 25, 26 -> throw new IllegalArgumentException(
          "Unsupported storage layout version: " + layoutVersion
              + ". Export the database with the older YouTrackDB version."
              + " Import that export with the new YouTrackDB version.");
      default -> throw new IllegalArgumentException(
          "Unsupported storage layout version: " + layoutVersion
              + (layoutVersion > 28
                  ? ". A newer YouTrackDB version created this database."
                      + " Open it with that version."
                  : ". This build supports storage layout versions 27 and 28 only."));
    };
  }
}
